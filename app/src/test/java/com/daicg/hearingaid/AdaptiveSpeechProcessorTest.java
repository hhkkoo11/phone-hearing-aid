package com.daicg.hearingaid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdaptiveSpeechProcessorTest {
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 240;

    @Test
    public void detectsKnownBoneConductionNamesWithoutMisclassifyingEarbuds() {
        assertTrue(HeadsetProfileDetector.isBoneConductionName("Shokz OpenRun Pro"));
        assertTrue(HeadsetProfileDetector.isBoneConductionName("韶音骨传导耳机"));
        assertEquals(
                HeadsetProfile.BLUETOOTH_IN_EAR,
                HeadsetProfileDetector.detect(false, "Redmi Buds 5 Pro", false));
        assertEquals(
                HeadsetProfile.WIRED,
                HeadsetProfileDetector.detect(true, "USB Audio", true));
    }

    @Test
    public void silenceRemainsSilent() {
        AdaptiveSpeechProcessor processor = new AdaptiveSpeechProcessor(
                SAMPLE_RATE, HeadsetProfile.WIRED);
        short[] frame = new short[FRAME_SIZE];
        for (int i = 0; i < 80; i++) {
            processor.processInPlace(frame, frame.length, 14.0f, 0.90f);
        }
        for (short sample : frame) {
            assertEquals(0, sample);
        }
    }

    @Test
    public void outputNeverExceedsConfiguredLimit() {
        AdaptiveSpeechProcessor processor = new AdaptiveSpeechProcessor(
                SAMPLE_RATE, HeadsetProfile.WIRED);
        short[] frame = new short[FRAME_SIZE];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) (Math.sin(i * 0.31) * 30000.0);
        }
        processor.processInPlace(frame, frame.length, 24.0f, 0.82f);
        int maximum = 0;
        for (short sample : frame) {
            maximum = Math.max(maximum, Math.abs((int) sample));
        }
        assertTrue(maximum <= Math.round(Short.MAX_VALUE * 0.82f));
    }

    @Test
    public void speechIsPreservedMoreThanWindAndHiss() {
        double speechRatio = measuredGain(
                HeadsetProfile.WIRED,
                index -> speechSample(index));
        double windRatio = measuredGain(
                HeadsetProfile.WIRED,
                index -> 850.0 * Math.sin(2.0 * Math.PI * 90.0 * index / SAMPLE_RATE));
        double hissRatio = measuredGain(
                HeadsetProfile.WIRED,
                index -> 620.0 * Math.sin(2.0 * Math.PI * 9200.0 * index / SAMPLE_RATE));

        assertTrue("speech=" + speechRatio + " wind=" + windRatio,
                speechRatio > windRatio * 1.20);
        assertTrue("speech=" + speechRatio + " hiss=" + hissRatio,
                speechRatio > hissRatio * 1.20);
    }

    @Test
    public void boneProfileProvidesMoreSpeechMakeupThanInEarProfile() {
        double boneRatio = measuredGain(
                HeadsetProfile.BLUETOOTH_BONE,
                index -> speechSample(index));
        double inEarRatio = measuredGain(
                HeadsetProfile.BLUETOOTH_IN_EAR,
                index -> speechSample(index));
        assertTrue(boneRatio > inEarRatio * 1.12);
    }

    private static double measuredGain(HeadsetProfile profile, Signal signal) {
        AdaptiveSpeechProcessor processor = new AdaptiveSpeechProcessor(SAMPLE_RATE, profile);
        long sampleIndex = 0L;
        double inputEnergy = 0.0;
        double outputEnergy = 0.0;
        int measuredSamples = 0;
        for (int frameIndex = 0; frameIndex < 180; frameIndex++) {
            short[] frame = new short[FRAME_SIZE];
            double frameInputEnergy = 0.0;
            for (int i = 0; i < frame.length; i++, sampleIndex++) {
                double value = signal.value(sampleIndex);
                frame[i] = (short) Math.round(value);
                frameInputEnergy += value * value;
            }
            processor.processInPlace(frame, frame.length, 1.0f, 0.90f);
            if (frameIndex >= 140) {
                inputEnergy += frameInputEnergy;
                for (short sample : frame) {
                    outputEnergy += sample * (double) sample;
                }
                measuredSamples += frame.length;
            }
        }
        double inputRms = Math.sqrt(inputEnergy / measuredSamples);
        double outputRms = Math.sqrt(outputEnergy / measuredSamples);
        return outputRms / Math.max(1.0, inputRms);
    }

    private static double speechSample(long index) {
        double seconds = index / (double) SAMPLE_RATE;
        double syllableEnvelope = 0.58 + 0.42 * Math.sin(2.0 * Math.PI * 4.2 * seconds);
        return syllableEnvelope * (
                350.0 * Math.sin(2.0 * Math.PI * 180.0 * seconds)
                        + 420.0 * Math.sin(2.0 * Math.PI * 720.0 * seconds)
                        + 500.0 * Math.sin(2.0 * Math.PI * 1450.0 * seconds)
                        + 330.0 * Math.sin(2.0 * Math.PI * 2650.0 * seconds));
    }

    private interface Signal {
        double value(long index);
    }
}
