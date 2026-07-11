package com.daicg.hearingaid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;

public final class HearingEngine {
    public interface Listener {
        void onLevel(float level);
        void onError(String message);
        void onGainReduced(float gain);
        void onLoudListening(float gain);
    }

    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT_MONO = AudioFormat.CHANNEL_OUT_MONO;
    private static final int CHANNEL_OUT_STEREO = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int WIRED_SAMPLE_RATE = 48000;
    private static final int BLUETOOTH_SAMPLE_RATE = 44100;
    private static final int WIRED_FRAME_BUFFER = 144;
    private static final int BLUETOOTH_FRAME_BUFFER = 192;
    private static final float SELF_VOICE_THRESHOLD = 1300.0f;
    private static final float FAR_SELF_VOICE_THRESHOLD = 1750.0f;
    private static final float SELF_VOICE_COMPRESS_RATIO = 0.28f;

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;

    private volatile boolean running;
    private Thread audioThread;
    private float gain = 2.0f;
    private float outputLimit = 0.68f;
    private boolean noiseSuppressionEnabled;
    private boolean aiNoiseSuppressionEnabled;
    private boolean automaticGainEnabled;
    private boolean voiceEnhancementEnabled = true;
    private boolean farPickupEnabled = true;
    private boolean longRangePickupEnabled;
    private boolean echoCancellationEnabled;
    private boolean selfVoiceReductionEnabled;
    private boolean selfVoiceProfileEnabled;
    private float selfVoiceProfileZcr;
    private float selfVoiceProfileDiffRatio;
    private float selfVoiceProfilePeakRatio;
    private String inputSourceMode = AppSettings.INPUT_PHONE_MIC;
    private boolean boneConductionNoiseControlEnabled;
    private boolean feedbackProtectionEnabled = true;
    private boolean loudnessBoostEnabled = true;

    public HearingEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void setGain(float gain) {
        this.gain = Math.max(0.2f, Math.min(gain, 24.0f));
    }

    public void setOutputLimit(float outputLimit) {
        this.outputLimit = Math.max(0.45f, Math.min(outputLimit, 0.90f));
    }

    public void setNoiseSuppressionEnabled(boolean enabled) {
        this.noiseSuppressionEnabled = enabled;
    }

    public void setAiNoiseSuppressionEnabled(boolean enabled) {
        this.aiNoiseSuppressionEnabled = false;
    }

    public void setAutomaticGainEnabled(boolean enabled) {
        this.automaticGainEnabled = enabled;
    }

    public void setVoiceEnhancementEnabled(boolean enabled) {
        this.voiceEnhancementEnabled = enabled;
    }

    public void setFarPickupEnabled(boolean enabled) {
        this.farPickupEnabled = enabled;
    }

    public void setLongRangePickupEnabled(boolean enabled) {
        this.longRangePickupEnabled = enabled;
    }

    public void setEchoCancellationEnabled(boolean enabled) {
        this.echoCancellationEnabled = false;
    }

    public void setSelfVoiceReductionEnabled(boolean enabled) {
        this.selfVoiceReductionEnabled = enabled;
    }

    public void setSelfVoiceProfile(boolean enabled, float zcr, float diffRatio, float peakRatio) {
        this.selfVoiceProfileEnabled = enabled;
        this.selfVoiceProfileZcr = zcr;
        this.selfVoiceProfileDiffRatio = diffRatio;
        this.selfVoiceProfilePeakRatio = peakRatio;
    }

    public void setInputSourceMode(String mode) {
        if (AppSettings.INPUT_HEADSET_MIC.equals(mode) || AppSettings.INPUT_AUTO.equals(mode)) {
            this.inputSourceMode = mode;
        } else {
            this.inputSourceMode = AppSettings.INPUT_PHONE_MIC;
        }
    }

    public void setBoneConductionNoiseControlEnabled(boolean enabled) {
        this.boneConductionNoiseControlEnabled = enabled;
    }

    public void setFeedbackProtectionEnabled(boolean enabled) {
        this.feedbackProtectionEnabled = enabled;
    }

    public void setLoudnessBoostEnabled(boolean enabled) {
        this.loudnessBoostEnabled = false;
    }

    public void start() {
        if (running) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError("\u9700\u8981\u9ea6\u514b\u98ce\u6743\u9650");
            return;
        }
        if (!hasWiredOutput() && !hasConnectedBluetoothAudioProfile()) {
            listener.onError("\u7cfb\u7edf\u672a\u8bc6\u522b\u5230\u8033\u673a\uff0c\u5df2\u963b\u6b62\u5916\u653e");
            return;
        }

        running = true;
        audioThread = new Thread(this::runAudioLoop, "HearingEngine");
        audioThread.start();
    }

    public void stop() {
        running = false;
        if (audioThread != null) {
            try {
                audioThread.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);

        boolean wiredRoute = hasWiredOutput();
        boolean bluetoothRoute = !wiredRoute && hasConnectedBluetoothAudioProfile();
        boolean aiNoiseEnabled = false;
        int sampleRate = bluetoothRoute ? BLUETOOTH_SAMPLE_RATE : WIRED_SAMPLE_RATE;
        int frameBuffer = bluetoothRoute ? BLUETOOTH_FRAME_BUFFER : WIRED_FRAME_BUFFER;
        int outputChannelMask = bluetoothRoute ? CHANNEL_OUT_STEREO : CHANNEL_OUT_MONO;
        int outputChannelCount = bluetoothRoute ? 2 : 1;

        int minIn = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, ENCODING);
        int minOut = AudioTrack.getMinBufferSize(sampleRate, outputChannelMask, ENCODING);
        if (minIn <= 0 || minOut <= 0) {
            postError("\u5f53\u524d\u8bbe\u5907\u4e0d\u652f\u6301\u5b9e\u65f6\u97f3\u9891");
            running = false;
            return;
        }

        int recordBuffer = Math.max(minIn, frameBuffer * 3);
        int trackBuffer = Math.max(minOut, frameBuffer * outputChannelCount * 2 * 3);

        AudioRecord record = null;
        AudioTrack track = null;
        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        AutomaticGainControl automaticGain = null;
        AiNoiseSuppressor aiNoiseSuppressor = null;
        boolean audioFocusGranted = false;

        try {
            audioFocusGranted = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

            record = new AudioRecord.Builder()
                    .setAudioSource(selectAudioSource())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_IN)
                            .build())
                    .setBufferSizeInBytes(recordBuffer)
                    .build();

            track = new AudioTrack.Builder()
                    .setAudioAttributes(buildOutputAttributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(ENCODING)
                            .setChannelMask(outputChannelMask)
                            .build())
                    .setBufferSizeInBytes(trackBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED
                    || track.getState() != AudioTrack.STATE_INITIALIZED) {
                postError("\u97f3\u9891\u8bbe\u5907\u521d\u59cb\u5316\u5931\u8d25");
                running = false;
                return;
            }

            AudioDeviceInfo preferredInput = findPreferredInputDevice();
            if (preferredInput != null) {
                record.setPreferredDevice(preferredInput);
            }

            trimPlaybackBuffer(track, frameBuffer, bluetoothRoute);
            track.setVolume(1.0f);

            AudioDeviceInfo preferredOutput = findPreferredOutputDevice();
            if (preferredOutput != null) {
                track.setPreferredDevice(preferredOutput);
            }

            int sessionId = record.getAudioSessionId();
            if (echoCancellationEnabled && AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                }
            }
            if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
            if (automaticGainEnabled && AutomaticGainControl.isAvailable()) {
                automaticGain = AutomaticGainControl.create(sessionId);
                if (automaticGain != null) {
                    automaticGain.setEnabled(true);
                }
            }

            short[] buffer = new short[frameBuffer];
            short[] stereoBuffer = bluetoothRoute ? new short[frameBuffer * 2] : null;
            VoiceProcessor voiceProcessor = new VoiceProcessor(
                    sampleRate,
                    farPickupEnabled,
                    longRangePickupEnabled,
                    selfVoiceProfileEnabled,
                    selfVoiceProfileZcr,
                    selfVoiceProfileDiffRatio,
                    selfVoiceProfilePeakRatio,
                    boneConductionNoiseControlEnabled);
            if (aiNoiseEnabled) {
                aiNoiseSuppressor = AiNoiseSuppressor.create();
            }
            boolean dataCollectionEnabled = AppSettings.outdoorDataCollectionEnabled(context);
            FeedbackGuard feedbackGuard = new FeedbackGuard();
            LoudnessGuard loudnessGuard = new LoudnessGuard();
            record.startRecording();
            track.play();

            while (running) {
                int read = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    continue;
                }
                if (aiNoiseSuppressor != null) {
                    aiNoiseSuppressor.processInPlace(buffer, read);
                }
                float level = processAndMeasure(buffer, read, gain,
                        outputLimit, voiceEnhancementEnabled, farPickupEnabled,
                        voiceProcessor, feedbackGuard);
                if (feedbackProtectionEnabled && feedbackGuard.shouldReduceGain()
                        && gain > 2.0f) {
                    gain = Math.max(2.0f, gain * 0.86f);
                    listener.onGainReduced(gain);
                    feedbackGuard.reset();
                }
                if (loudnessGuard.shouldWarn(level, gain)) {
                    listener.onLoudListening(gain);
                }
                if (dataCollectionEnabled) {
                    TestDataLogger.appendFrameStats(context, buffer, read, sampleRate);
                }
                listener.onLevel(level);
                if (stereoBuffer != null) {
                    for (int i = 0, j = 0; i < read; i++) {
                        short sample = buffer[i];
                        stereoBuffer[j++] = sample;
                        stereoBuffer[j++] = sample;
                    }
                    track.write(stereoBuffer, 0, read * 2, AudioTrack.WRITE_BLOCKING);
                } else {
                    track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
                }
            }
        } catch (Exception e) {
            postError(e.getMessage() == null
                    ? "\u5b9e\u65f6\u76d1\u542c\u542f\u52a8\u5931\u8d25"
                    : e.getMessage());
        } finally {
            if (audioFocusGranted) {
                audioManager.abandonAudioFocus(null);
            }
            if (aiNoiseSuppressor != null) {
                aiNoiseSuppressor.close();
            }
            releaseEffect(automaticGain);
            releaseEffect(noiseSuppressor);
            releaseEffect(echoCanceler);
            if (record != null) {
                try {
                    record.stop();
                } catch (Exception ignored) {
                }
                record.release();
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                track.release();
            }
            running = false;
        }
    }

    private static float processAndMeasure(short[] buffer, int length, float gain, float outputLimit,
            boolean enhanceVoice, boolean farPickup,
            VoiceProcessor voiceProcessor, FeedbackGuard feedbackGuard) {
        long sum = 0L;
        int peak = 0;
        int limit = Math.round(Short.MAX_VALUE * outputLimit);
        for (int i = 0; i < length; i++) {
            float input = buffer[i];
            if (enhanceVoice) {
                if (voiceProcessor != null) {
                    input = voiceProcessor.highPass(input);
                    input *= voiceProcessor.environmentGate(Math.abs(input), false);
                }
                float absInput = Math.abs(input);
                if (absInput < (farPickup ? 70.0f : 110.0f)) {
                    input *= farPickup ? 0.26f : 0.16f;
                } else if (farPickup && absInput < 760.0f) {
                    float t = (absInput - 70.0f) / 690.0f;
                    float boost = 1.28f - Math.max(0.0f, Math.min(1.0f, t)) * 0.18f;
                    input *= boost;
                }
                if (voiceProcessor != null && gain >= 7.0f) {
                    input = voiceProcessor.voiceShape(input);
                    input = voiceProcessor.softenSharpEdge(input);
                }
            }
            int sample = Math.round(input * gain);
            if (enhanceVoice) {
                sample = compressAndLimit(sample, limit);
                if (voiceProcessor != null) {
                    sample = voiceProcessor.deHissLight(sample);
                }
            }
            if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE;
            } else if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE;
            }
            buffer[i] = (short) sample;
            int absSample = Math.abs(sample);
            peak = Math.max(peak, absSample);
            sum += absSample;
        }
        float level = Math.min(1.0f, sum / (float) length / Short.MAX_VALUE);
        feedbackGuard.observe(level, peak / (float) Short.MAX_VALUE);
        return level;
    }

    private static float softenNearLoudVoice(float input, float absInput, float threshold, float ratio) {
        float sign = input < 0.0f ? -1.0f : 1.0f;
        float softened = threshold + ((absInput - threshold) * ratio);
        return sign * softened;
    }

    private static float protectCloseLoudSound(float input, boolean widePickup) {
        float absInput = Math.abs(input);
        float firstThreshold = widePickup ? 1150.0f : 820.0f;
        if (absInput <= firstThreshold) {
            return input;
        }
        float protectedInput = softenNearLoudVoice(input, absInput, firstThreshold, 0.42f);
        float protectedAbs = Math.abs(protectedInput);
        float secondThreshold = widePickup ? 1850.0f : 1450.0f;
        if (protectedAbs > secondThreshold) {
            protectedInput = softenNearLoudVoice(protectedInput, protectedAbs, secondThreshold, 0.26f);
        }
        return protectedInput;
    }

    public static float[] analyzeVoiceSignature(short[] samples, int length) {
        VoiceSignature signature = VoiceSignature.fromSamples(samples, length);
        if (signature.averageAbs < 120.0f || length < 8000) {
            return null;
        }
        return new float[]{
                signature.zeroCrossingRate,
                signature.diffRatio,
                signature.peakRatio
        };
    }

    private static int compressAndLimit(int sample, int limit) {
        return compressAndLimit(sample, limit, 0.42f, 0.24f);
    }

    private static int compressAndLimit(int sample, int limit, float kneeRatio, float overKneeRatio) {
        int sign = sample < 0 ? -1 : 1;
        int abs = Math.abs(sample);
        int knee = Math.round(limit * kneeRatio);
        if (abs > knee) {
            abs = knee + Math.round((abs - knee) * overKneeRatio);
        }
        if (abs > limit) {
            abs = limit;
        }
        return sign * abs;
    }

    private static LoudnessEnhancer createLoudnessEnhancer(int audioSessionId, float gain, boolean boneConduction) {
        try {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(audioSessionId);
            int targetGainMb;
            if (boneConduction) {
                targetGainMb = gain >= 100.0f ? 1700 : (gain >= 80.0f ? 1450 : 1150);
            } else {
                targetGainMb = gain >= 58.0f ? 900 : (gain >= 42.0f ? 700 : 450);
            }
            enhancer.setTargetGain(targetGainMb);
            enhancer.setEnabled(true);
            return enhancer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void trimPlaybackBuffer(AudioTrack track, int frameBuffer, boolean bluetoothRoute) {
        int targetFrames = bluetoothRoute ? frameBuffer * 3 : frameBuffer * 2;
        try {
            track.setBufferSizeInFrames(targetFrames);
        } catch (IllegalStateException ignored) {
        }
    }

    private static AudioAttributes buildOutputAttributes() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        if (Build.VERSION.SDK_INT >= 32) {
            builder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER);
        }
        return builder.build();
    }

    private void postError(String message) {
        listener.onError(message);
    }

    private int selectAudioSource() {
        if (echoCancellationEnabled) {
            return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
        return MediaRecorder.AudioSource.MIC;
    }

    private static void releaseEffect(android.media.audiofx.AudioEffect effect) {
        if (effect != null) {
            effect.release();
        }
    }

    public String describeOutputRoute() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        boolean wired = false;
        boolean bluetooth = false;
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            wired |= isWired(type);
            bluetooth |= isBluetooth(type);
        }
        bluetooth |= hasConnectedBluetoothAudioProfile();
        if (wired) {
            return "\u6709\u7ebf\u8033\u673a\u5df2\u8fde\u63a5";
        }
        if (bluetooth) {
            return "\u84dd\u7259\u8033\u673a\u5df2\u8fde\u63a5";
        }
        return "\u8bf7\u8fde\u63a5\u8033\u673a";
    }

    public boolean hasWiredOutput() {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isWired(device.getType())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasBluetoothOutput() {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isBluetooth(device.getType())) {
                return true;
            }
        }
        return hasConnectedBluetoothAudioProfile();
    }

    private AudioDeviceInfo findPreferredOutputDevice() {
        AudioDeviceInfo bluetooth = null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (isWired(type)) {
                return device;
            }
            if (isBluetooth(type)) {
                bluetooth = device;
            }
        }
        return bluetooth;
    }

    private AudioDeviceInfo findPreferredInputDevice() {
        if (AppSettings.INPUT_HEADSET_MIC.equals(inputSourceMode)) {
            AudioDeviceInfo headsetMic = findHeadsetInputDevice();
            if (headsetMic != null) {
                return headsetMic;
            }
        } else if (AppSettings.INPUT_AUTO.equals(inputSourceMode)) {
            AudioDeviceInfo headsetMic = findHeadsetInputDevice();
            if (headsetMic != null && !hasWiredOutput()) {
                return headsetMic;
            }
        }
        return findPhoneInputDevice();
    }

    private AudioDeviceInfo findPhoneInputDevice() {
        AudioDeviceInfo fallbackMic = null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return device;
            }
            if (fallbackMic == null && isPhoneMicrophone(type)) {
                fallbackMic = device;
            }
        }
        return fallbackMic;
    }

    private AudioDeviceInfo findHeadsetInputDevice() {
        AudioDeviceInfo bluetooth = null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                return device;
            }
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                bluetooth = device;
            }
        }
        return bluetooth;
    }

    public boolean hasConnectedBluetoothAudioProfile() {
        if (bluetoothAdapter == null || !hasBluetoothConnectPermission()) {
            return false;
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                return false;
            }
        } catch (SecurityException ignored) {
            return false;
        }
        try {
            return bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP)
                    == BluetoothAdapter.STATE_CONNECTED
                    || bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                    == BluetoothAdapter.STATE_CONNECTED
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEARING_AID)
                    == BluetoothAdapter.STATE_CONNECTED)
                    || getLeAudioConnectionState() == BluetoothAdapter.STATE_CONNECTED;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private int getLeAudioConnectionState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasBluetoothConnectPermission()) {
            return BluetoothAdapter.STATE_DISCONNECTED;
        }
        try {
            return bluetoothAdapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO);
        } catch (Throwable ignored) {
            return BluetoothAdapter.STATE_DISCONNECTED;
        }
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isWired(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                || type == AudioDeviceInfo.TYPE_LINE_ANALOG;
    }

    private static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }

    private static boolean isPhoneMicrophone(int type) {
        return type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                || type == AudioDeviceInfo.TYPE_TELEPHONY
                || type == AudioDeviceInfo.TYPE_FM_TUNER;
    }

    private static final class VoiceProcessor {
        private static final float HIGH_PASS_ALPHA = 0.985f;

        private float previousInput;
        private float previousOutput;
        private float previousPresenceInput;
        private float smoothedPresence;
        private float roomTail;
        private float highGainPrevious;
        private float boneOutdoorTail;
        private float boneOutdoorPrevious;
        private float outputSmoother;
        private float edgeSmoother;
        private float deHissOutput;
        private float extremeOutputSmoother;
        private float environmentFloor = 120.0f;
        private float echoTail;
        private float selfTalkDuck = 1.0f;
        private boolean selfVoiceProfileEnabled;
        private float selfVoiceProfileZcr;
        private float selfVoiceProfileDiffRatio;
        private float selfVoiceProfilePeakRatio;
        private boolean boneConductionNoiseControlEnabled;

        VoiceProcessor(int sampleRate, boolean farPickup, boolean longRangePickup, boolean profileEnabled,
                float profileZcr, float profileDiffRatio, float profilePeakRatio,
                boolean boneNoiseControl) {
            this.selfVoiceProfileEnabled = profileEnabled;
            this.selfVoiceProfileZcr = profileZcr;
            this.selfVoiceProfileDiffRatio = profileDiffRatio;
            this.selfVoiceProfilePeakRatio = profilePeakRatio;
            this.boneConductionNoiseControlEnabled = boneNoiseControl;
        }

        float highPass(float input) {
            float output = HIGH_PASS_ALPHA * (previousOutput + input - previousInput);
            previousInput = input;
            previousOutput = output;
            return output;
        }

        float voiceShape(float input) {
            float edge = input - previousPresenceInput;
            previousPresenceInput = input;
            smoothedPresence = smoothedPresence * 0.90f + edge * 0.10f;
            return input * 0.99f + smoothedPresence * 0.025f;
        }

        float softenSharpEdge(float input) {
            edgeSmoother = edgeSmoother * 0.42f + input * 0.58f;
            return edgeSmoother;
        }

        int deHissLight(int sample) {
            deHissOutput = deHissOutput * 0.62f + sample * 0.38f;
            int smoothed = Math.round(deHissOutput);
            if (smoothed > Short.MAX_VALUE) {
                return Short.MAX_VALUE;
            }
            if (smoothed < Short.MIN_VALUE) {
                return Short.MIN_VALUE;
            }
            return smoothed;
        }

        int smoothExtremeOutput(int sample, float gain) {
            if (gain < 48.0f) {
                return sample;
            }
            float keepPrevious = gain >= 90.0f ? 0.42f : 0.28f;
            extremeOutputSmoother = extremeOutputSmoother * keepPrevious + sample * (1.0f - keepPrevious);
            int smoothed = Math.round(extremeOutputSmoother);
            if (smoothed > Short.MAX_VALUE) {
                return Short.MAX_VALUE;
            }
            if (smoothed < Short.MIN_VALUE) {
                return Short.MIN_VALUE;
            }
            return smoothed;
        }

        float environmentGate(float absInput, boolean outdoorPickup) {
            float learnSpeed = absInput < environmentFloor * 3.0f ? 0.015f : 0.002f;
            environmentFloor += (absInput - environmentFloor) * learnSpeed;
            float quiet = Math.max(outdoorPickup ? 95.0f : 45.0f, environmentFloor * (outdoorPickup ? 1.35f : 0.75f));
            float speechStart = Math.max(outdoorPickup ? 300.0f : 120.0f, environmentFloor * (outdoorPickup ? 3.4f : 1.65f));
            if (absInput < quiet) {
                return outdoorPickup ? 0.12f : 0.55f;
            }
            if (absInput < speechStart) {
                float t = (absInput - quiet) / Math.max(1.0f, speechStart - quiet);
                return (outdoorPickup ? 0.22f : 0.72f) + t * (outdoorPickup ? 0.58f : 0.28f);
            }
            return 1.0f;
        }

        float reduceEchoTail(float input, boolean outdoorPickup) {
            float alpha = outdoorPickup ? 0.985f : 0.975f;
            echoTail = echoTail * alpha + input * (1.0f - alpha);
            return input - echoTail * (outdoorPickup ? 0.62f : 0.45f);
        }

        float highGainDeEcho(float input, float gain) {
            float tailAlpha = boneConductionNoiseControlEnabled ? 0.985f : 0.975f;
            roomTail = roomTail * tailAlpha + input * (1.0f - tailAlpha);
            float transientPart = input - roomTail * (boneConductionNoiseControlEnabled ? 0.58f : 0.42f);
            float edge = transientPart - highGainPrevious;
            highGainPrevious = transientPart;
            float clarity = gain >= 16.0f ? 0.05f : 0.08f;
            return transientPart + edge * clarity;
        }

        float boneOutdoorVoiceShape(float input, boolean speechLikeFrame) {
            boneOutdoorTail = boneOutdoorTail * 0.992f + input * 0.008f;
            float transientPart = input - boneOutdoorTail * (speechLikeFrame ? 0.24f : 0.72f);
            float edge = transientPart - boneOutdoorPrevious;
            boneOutdoorPrevious = transientPart;
            if (speechLikeFrame) {
                return transientPart * 1.12f + edge * 0.08f;
            }
            return transientPart * 0.48f;
        }

        boolean isLikelyNearSelfTalk(VoiceSignature signature, float gain) {
            if (!boneConductionNoiseControlEnabled) {
                return gain >= 10.0f
                        && signature.averageAbs > 1450.0f
                        && signature.peakRatio < 20.0f
                        && signature.diffRatio < 2.6f;
            }
            return signature.averageAbs > (gain >= 10.0f ? 420.0f : 560.0f)
                    && signature.peakRatio < 18.0f
                    && signature.diffRatio < 2.8f
                    && signature.zeroCrossingRate < 0.30f;
        }

        boolean isLikelyProximityBuzz(VoiceSignature signature, float gain) {
            if (!boneConductionNoiseControlEnabled) {
                return false;
            }
            float upperAverage = gain >= 10.0f ? 980.0f : 760.0f;
            return signature.averageAbs > 120.0f
                    && signature.averageAbs < upperAverage
                    && signature.diffRatio < 1.15f
                    && signature.peakRatio < 8.5f
                    && signature.zeroCrossingRate < 0.18f;
        }

        boolean isLikelyDiffuseNoise(VoiceSignature signature, boolean longRangePickup) {
            float averageLimit = longRangePickup ? 760.0f : 620.0f;
            return signature.averageAbs > 35.0f
                    && signature.averageAbs < averageLimit
                    && signature.diffRatio < 1.35f
                    && signature.peakRatio < 10.0f
                    && signature.zeroCrossingRate < 0.16f;
        }

        boolean isLikelySpeechFrame(VoiceSignature signature) {
            return signature.averageAbs > 75.0f
                    && signature.averageAbs < 3200.0f
                    && signature.peakRatio > 6.8f
                    && signature.peakRatio < 42.0f
                    && signature.diffRatio > 0.92f
                    && signature.diffRatio < 4.8f
                    && signature.zeroCrossingRate > 0.04f
                    && signature.zeroCrossingRate < 0.42f;
        }

        boolean isLikelyOutdoorMusicNoise(VoiceSignature signature, float gain) {
            float averageLimit = gain >= 16.0f ? 2200.0f : 1600.0f;
            return signature.averageAbs > 90.0f
                    && signature.averageAbs < averageLimit
                    && signature.peakRatio < 10.5f
                    && signature.diffRatio < 1.45f
                    && signature.zeroCrossingRate < 0.20f;
        }

        boolean isLikelyElectricNoise(VoiceSignature signature, float gain) {
            float averageLimit = gain >= 16.0f ? 900.0f : 620.0f;
            return signature.averageAbs > 28.0f
                    && signature.averageAbs < averageLimit
                    && signature.peakRatio < 7.2f
                    && signature.diffRatio < 1.05f
                    && signature.zeroCrossingRate < 0.18f;
        }

        boolean isLikelyHarshHiss(VoiceSignature signature, float gain) {
            float averageLimit = gain >= 16.0f ? 1500.0f : 980.0f;
            return signature.averageAbs > 22.0f
                    && signature.averageAbs < averageLimit
                    && signature.peakRatio < 13.0f
                    && signature.diffRatio > 2.15f
                    && signature.zeroCrossingRate > 0.20f;
        }

        int smoothOutput(int sample, boolean noiseFrame, boolean speechLikeFrame, float gain) {
            float target = sample;
            if (noiseFrame && !speechLikeFrame) {
                target *= gain >= 16.0f ? 0.18f : 0.26f;
            }
            float keepPrevious;
            if (noiseFrame) {
                keepPrevious = speechLikeFrame ? 0.44f : 0.72f;
            } else {
                keepPrevious = gain >= 16.0f ? 0.30f : 0.20f;
            }
            outputSmoother = outputSmoother * keepPrevious + target * (1.0f - keepPrevious);
            int cleaned = Math.round(outputSmoother);
            if (cleaned > Short.MAX_VALUE) {
                return Short.MAX_VALUE;
            }
            if (cleaned < Short.MIN_VALUE) {
                return Short.MIN_VALUE;
            }
            return cleaned;
        }

        float updateSelfTalkDuck(boolean nearSelfTalk, float gain) {
            float target;
            if (nearSelfTalk) {
                if (boneConductionNoiseControlEnabled) {
                    target = gain >= 10.0f ? 0.22f : 0.32f;
                } else {
                    target = gain >= 10.0f ? 0.52f : 0.68f;
                }
            } else {
                target = 1.0f;
            }
            float speed = target < selfTalkDuck ? 0.74f : 0.06f;
            selfTalkDuck += (target - selfTalkDuck) * speed;
            return selfTalkDuck;
        }
    }

    private static final class VoiceSignature {
        private final float averageAbs;
        private final float zeroCrossingRate;
        private final float diffRatio;
        private final float peakRatio;

        private VoiceSignature(float averageAbs, float zeroCrossingRate,
                float diffRatio, float peakRatio) {
            this.averageAbs = averageAbs;
            this.zeroCrossingRate = zeroCrossingRate;
            this.diffRatio = diffRatio;
            this.peakRatio = peakRatio;
        }

        static VoiceSignature fromSamples(short[] samples, int length) {
            if (samples == null || length <= 1) {
                return new VoiceSignature(0.0f, 0.0f, 0.0f, 0.0f);
            }
            long sumAbs = 0L;
            long sumDiff = 0L;
            int peak = 0;
            int zeroCrossings = 0;
            int previous = samples[0];
            for (int i = 0; i < length; i++) {
                int value = samples[i];
                int abs = Math.abs(value);
                sumAbs += abs;
                if (abs > peak) {
                    peak = abs;
                }
                if (i > 0) {
                    sumDiff += Math.abs(value - previous);
                    if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) {
                        zeroCrossings++;
                    }
                }
                previous = value;
            }
            float averageAbs = sumAbs / (float) length;
            float zeroCrossingRate = zeroCrossings / (float) (length - 1);
            float diffRatio = sumDiff / Math.max(1.0f, sumAbs);
            float peakRatio = peak / Math.max(1.0f, averageAbs);
            return new VoiceSignature(averageAbs, zeroCrossingRate, diffRatio, peakRatio);
        }

        boolean matches(float profileZcr, float profileDiffRatio, float profilePeakRatio) {
            if (averageAbs < 180.0f) {
                return false;
            }
            float zcrDistance = Math.abs(zeroCrossingRate - profileZcr) / 0.075f;
            float diffDistance = Math.abs(diffRatio - profileDiffRatio) / 0.55f;
            float peakDistance = Math.abs(peakRatio - profilePeakRatio) / 3.5f;
            return zcrDistance + diffDistance + peakDistance < 2.15f;
        }
    }

    private static final class FeedbackGuard {
        private int hotFrames;
        private int reduceCooldownFrames;

        void observe(float averageLevel, float peakLevel) {
            if (reduceCooldownFrames > 0) {
                reduceCooldownFrames--;
            }
            if (peakLevel > 0.92f && averageLevel > 0.48f) {
                hotFrames++;
            } else if (hotFrames > 0) {
                hotFrames = Math.max(0, hotFrames - 2);
            }
        }

        boolean shouldReduceGain() {
            return reduceCooldownFrames <= 0 && hotFrames > 96;
        }

        void reset() {
            hotFrames = 0;
            reduceCooldownFrames = 1800;
        }
    }

    private static final class LoudnessGuard {
        private int loudFrames;
        private long lastWarningAt;

        boolean shouldWarn(float level, float gain) {
            if (gain >= 10.0f && level > 0.36f) {
                loudFrames++;
            } else if (loudFrames > 0) {
                loudFrames--;
            }
            long now = System.currentTimeMillis();
            if (loudFrames > 280 && now - lastWarningAt > 5L * 60L * 1000L) {
                lastWarningAt = now;
                loudFrames = 0;
                return true;
            }
            return false;
        }
    }
}
