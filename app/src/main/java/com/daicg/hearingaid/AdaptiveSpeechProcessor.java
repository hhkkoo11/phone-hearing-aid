package com.daicg.hearingaid;

/**
 * Low-latency, allocation-free speech shaping for the live monitoring path.
 * It uses three broad bands so it can suppress wind and hiss without chopping speech into frames.
 */
final class AdaptiveSpeechProcessor {
    private static final float MIN_FLOOR = 8.0f;

    private final int sampleRate;
    private final ProfileTuning tuning;
    private final float dcAlpha;
    private final float lowAlpha;
    private final float voiceAlpha;

    private float previousInput;
    private float dcOutput;
    private float lowState;
    private float voiceState;
    private float lowGain;
    private float midGain;
    private float highGain;
    private float compressionGain = 1.0f;
    private float limiterGain = 1.0f;
    private float lowNoiseFloor = 35.0f;
    private float midNoiseFloor = 28.0f;
    private float highNoiseFloor = 24.0f;
    private long processedSamples;
    private float lastPeakLevel;
    private float lastSpeechConfidence;
    private float lastWindConfidence;
    private float lastHissConfidence;

    AdaptiveSpeechProcessor(int sampleRate, HeadsetProfile profile) {
        this.sampleRate = Math.max(8000, sampleRate);
        this.tuning = ProfileTuning.forProfile(profile);
        this.dcAlpha = onePoleAlpha(70.0f, this.sampleRate);
        this.lowAlpha = onePoleAlpha(tuning.lowCutoffHz, this.sampleRate);
        this.voiceAlpha = onePoleAlpha(tuning.voiceUpperHz, this.sampleRate);
        this.lowGain = tuning.lowBaseGain;
        this.midGain = tuning.midBaseGain;
        this.highGain = tuning.highBaseGain;
    }

    float processInPlace(short[] buffer, int length, float userGain, float outputLimit) {
        if (buffer == null || length <= 0) {
            lastPeakLevel = 0.0f;
            return 0.0f;
        }

        int safeLength = Math.min(length, buffer.length);
        float safeUserGain = clamp(userGain, 0.2f, 24.0f);
        int limit = Math.round(Short.MAX_VALUE * clamp(outputLimit, 0.45f, 0.92f));
        double lowEnergy = 0.0;
        double midEnergy = 0.0;
        double highEnergy = 0.0;
        long outputAbsSum = 0L;
        float inputPeak = 0.0f;
        int outputPeak = 0;

        for (int i = 0; i < safeLength; i++) {
            float input = buffer[i];
            float highPassed = dcAlpha * (dcOutput + input - previousInput);
            previousInput = input;
            dcOutput = highPassed;

            lowState = lowAlpha * lowState + (1.0f - lowAlpha) * highPassed;
            voiceState = voiceAlpha * voiceState + (1.0f - voiceAlpha) * highPassed;
            float low = lowState;
            float mid = voiceState - lowState;
            float high = highPassed - voiceState;

            lowEnergy += low * low;
            midEnergy += mid * mid;
            highEnergy += high * high;
            inputPeak = Math.max(inputPeak, Math.abs(highPassed));

            float shaped = low * lowGain + mid * midGain + high * highGain;
            float startupRamp = startupRamp(processedSamples++);
            float scaled = shaped * safeUserGain * tuning.outputMakeup
                    * compressionGain * startupRamp;
            int sample = limitSample(scaled, limit);
            buffer[i] = (short) sample;
            int absSample = Math.abs(sample);
            outputPeak = Math.max(outputPeak, absSample);
            outputAbsSum += absSample;
        }

        float lowRms = rms(lowEnergy, safeLength);
        float midRms = rms(midEnergy, safeLength);
        float highRms = rms(highEnergy, safeLength);
        updateTargets(lowRms, midRms, highRms, inputPeak);

        lastPeakLevel = outputPeak / (float) Short.MAX_VALUE;
        return Math.min(1.0f, outputAbsSum / (float) safeLength / Short.MAX_VALUE);
    }

    float getLastPeakLevel() {
        return lastPeakLevel;
    }

    float getLastSpeechConfidence() {
        return lastSpeechConfidence;
    }

    float getLastWindConfidence() {
        return lastWindConfidence;
    }

    float getLastHissConfidence() {
        return lastHissConfidence;
    }

    private void updateTargets(float lowRms, float midRms, float highRms, float inputPeak) {
        lowNoiseFloor = updateNoiseFloor(lowNoiseFloor, lowRms);
        midNoiseFloor = updateNoiseFloor(midNoiseFloor, midRms);
        highNoiseFloor = updateNoiseFloor(highNoiseFloor, highRms);

        float midSnr = midRms / Math.max(MIN_FLOOR, midNoiseFloor);
        float midDominance = midRms / Math.max(1.0f, lowRms * 0.55f + highRms * 0.75f);
        float totalRms = (float) Math.sqrt((lowRms * lowRms
                + midRms * midRms + highRms * highRms) / 3.0f);
        float crest = inputPeak / Math.max(1.0f, totalRms);

        float speechEnergy = smoothStep(1.20f, 3.20f, midSnr);
        float speechShape = smoothStep(0.65f, 1.65f, midDominance);
        float speechCrest = smoothStep(1.8f, 5.5f, crest);
        float speech = speechEnergy * (0.24f + 0.56f * speechShape + 0.20f * speechCrest);

        float lowDominance = lowRms / Math.max(1.0f, midRms + highRms * 0.35f);
        float wind = smoothStep(1.15f, 3.20f, lowDominance)
                * smoothStep(55.0f, 260.0f, lowRms)
                * (1.0f - speech * 0.55f);

        float highDominance = highRms / Math.max(1.0f, midRms);
        float hiss = smoothStep(0.85f, 2.40f, highDominance)
                * smoothStep(35.0f, 220.0f, highRms)
                * (1.0f - speech * 0.72f);

        lastSpeechConfidence = smooth(lastSpeechConfidence, speech, 0.18f, 0.08f);
        lastWindConfidence = smooth(lastWindConfidence, wind, 0.24f, 0.05f);
        lastHissConfidence = smooth(lastHissConfidence, hiss, 0.24f, 0.05f);

        float lowWiener = wienerGain(lowRms, lowNoiseFloor, tuning.minimumLowGain);
        float midWiener = wienerGain(midRms, midNoiseFloor, tuning.minimumMidGain);
        float highWiener = wienerGain(highRms, highNoiseFloor, tuning.minimumHighGain);

        float targetLow = tuning.lowBaseGain
                * mix(lowWiener, 1.0f, lastSpeechConfidence * 0.55f)
                * (1.0f - 0.82f * lastWindConfidence);
        float targetMid = tuning.midBaseGain
                * mix(midWiener, 1.0f, lastSpeechConfidence);
        float targetHigh = tuning.highBaseGain
                * mix(highWiener, 1.0f, lastSpeechConfidence * 0.82f)
                * (1.0f - 0.72f * lastHissConfidence);

        lowGain = smooth(lowGain, targetLow, 0.28f, 0.045f);
        midGain = smooth(midGain, targetMid, 0.22f, 0.055f);
        highGain = smooth(highGain, targetHigh, 0.30f, 0.040f);

        float targetCompression = compressionTarget(midRms, lastSpeechConfidence);
        compressionGain = smooth(compressionGain, targetCompression, 0.34f, 0.055f);
    }

    private int limitSample(float sample, int limit) {
        float absolute = Math.abs(sample);
        float desiredGain = absolute > limit ? limit / absolute : 1.0f;
        if (desiredGain < limiterGain) {
            limiterGain = desiredGain;
        } else {
            limiterGain += (1.0f - limiterGain) * 0.0018f;
        }
        int limited = Math.round(sample * limiterGain);
        if (limited > limit) {
            return limit;
        }
        if (limited < -limit) {
            return -limit;
        }
        return limited;
    }

    private float startupRamp(long sampleIndex) {
        float rampSamples = sampleRate * 0.14f;
        if (sampleIndex >= rampSamples) {
            return 1.0f;
        }
        return 0.08f + 0.92f * (sampleIndex / rampSamples);
    }

    private float compressionTarget(float midRms, float speechConfidence) {
        if (speechConfidence < 0.20f) {
            return 0.72f;
        }
        if (speechConfidence < 0.40f) {
            return 0.88f;
        }
        if (midRms < 140.0f) {
            return tuning.quietSpeechBoost;
        }
        if (midRms < 420.0f) {
            return mix(tuning.quietSpeechBoost, 1.08f, (midRms - 140.0f) / 280.0f);
        }
        if (midRms < 1100.0f) {
            return mix(1.08f, 0.94f, (midRms - 420.0f) / 680.0f);
        }
        if (midRms < 2600.0f) {
            return mix(0.94f, 0.72f, (midRms - 1100.0f) / 1500.0f);
        }
        return 0.62f;
    }

    private static float updateNoiseFloor(float current, float measured) {
        float bounded = Math.max(MIN_FLOOR, measured);
        float speed = bounded < current ? 0.16f : 0.0015f;
        return current + (bounded - current) * speed;
    }

    private static float wienerGain(float signalRms, float noiseRms, float minimum) {
        float signalPower = signalRms * signalRms;
        float noisePower = noiseRms * noiseRms;
        float cleanRatio = (signalPower - noisePower) / Math.max(1.0f, signalPower);
        return Math.max(minimum, (float) Math.sqrt(Math.max(0.0f, cleanRatio)));
    }

    private static float smooth(float current, float target, float downwardSpeed, float upwardSpeed) {
        float speed = target < current ? downwardSpeed : upwardSpeed;
        return current + (target - current) * speed;
    }

    private static float smoothStep(float low, float high, float value) {
        float t = clamp((value - low) / Math.max(0.0001f, high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float mix(float start, float end, float amount) {
        return start + (end - start) * clamp(amount, 0.0f, 1.0f);
    }

    private static float rms(double energy, int length) {
        return (float) Math.sqrt(energy / Math.max(1, length));
    }

    private static float onePoleAlpha(float cutoffHz, int sampleRate) {
        return (float) Math.exp(-2.0 * Math.PI * cutoffHz / sampleRate);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class ProfileTuning {
        private final float lowCutoffHz;
        private final float voiceUpperHz;
        private final float lowBaseGain;
        private final float midBaseGain;
        private final float highBaseGain;
        private final float minimumLowGain;
        private final float minimumMidGain;
        private final float minimumHighGain;
        private final float quietSpeechBoost;
        private final float outputMakeup;

        private ProfileTuning(float lowCutoffHz, float voiceUpperHz,
                float lowBaseGain, float midBaseGain, float highBaseGain,
                float minimumLowGain, float minimumMidGain, float minimumHighGain,
                float quietSpeechBoost, float outputMakeup) {
            this.lowCutoffHz = lowCutoffHz;
            this.voiceUpperHz = voiceUpperHz;
            this.lowBaseGain = lowBaseGain;
            this.midBaseGain = midBaseGain;
            this.highBaseGain = highBaseGain;
            this.minimumLowGain = minimumLowGain;
            this.minimumMidGain = minimumMidGain;
            this.minimumHighGain = minimumHighGain;
            this.quietSpeechBoost = quietSpeechBoost;
            this.outputMakeup = outputMakeup;
        }

        private static ProfileTuning forProfile(HeadsetProfile profile) {
            if (profile == HeadsetProfile.BLUETOOTH_BONE) {
                return new ProfileTuning(
                        300.0f, 3900.0f,
                        0.58f, 1.40f, 0.68f,
                        0.18f, 0.66f, 0.20f,
                        1.34f, 1.16f);
            }
            if (profile == HeadsetProfile.BLUETOOTH_IN_EAR) {
                return new ProfileTuning(
                        240.0f, 4200.0f,
                        0.72f, 1.18f, 0.62f,
                        0.22f, 0.58f, 0.24f,
                        1.25f, 0.96f);
            }
            return new ProfileTuning(
                    220.0f, 4300.0f,
                    0.78f, 1.22f, 0.70f,
                    0.28f, 0.60f, 0.30f,
                    1.28f, 1.00f);
        }
    }
}
