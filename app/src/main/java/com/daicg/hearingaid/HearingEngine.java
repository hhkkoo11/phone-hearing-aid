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
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

public final class HearingEngine {
    public interface Listener {
        void onLevel(float level);
        void onError(String message);
        void onGainReduced(float gain);
        void onLoudListening(float gain);
    }

    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int WIRED_SAMPLE_RATE = 48000;
    private static final int BLUETOOTH_SAMPLE_RATE = 44100;
    private static final int WIRED_FRAME_BUFFER = 144;
    private static final int BLUETOOTH_FRAME_BUFFER = 192;

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;

    private volatile boolean running;
    private Thread audioThread;
    private float gain = 2.0f;
    private float outputLimit = 0.68f;
    private boolean noiseSuppressionEnabled = true;
    private boolean automaticGainEnabled;
    private boolean voiceEnhancementEnabled = true;
    private boolean farPickupEnabled = true;
    private boolean feedbackProtectionEnabled = true;

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

    public void setAutomaticGainEnabled(boolean enabled) {
        this.automaticGainEnabled = enabled;
    }

    public void setVoiceEnhancementEnabled(boolean enabled) {
        this.voiceEnhancementEnabled = enabled;
    }

    public void setFarPickupEnabled(boolean enabled) {
        this.farPickupEnabled = enabled;
    }

    public void setFeedbackProtectionEnabled(boolean enabled) {
        this.feedbackProtectionEnabled = enabled;
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
        int sampleRate = bluetoothRoute ? BLUETOOTH_SAMPLE_RATE : WIRED_SAMPLE_RATE;
        int frameBuffer = bluetoothRoute ? BLUETOOTH_FRAME_BUFFER : WIRED_FRAME_BUFFER;

        int minIn = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, ENCODING);
        int minOut = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, ENCODING);
        if (minIn <= 0 || minOut <= 0) {
            postError("\u5f53\u524d\u8bbe\u5907\u4e0d\u652f\u6301\u5b9e\u65f6\u97f3\u9891");
            running = false;
            return;
        }

        int recordBuffer = Math.max(minIn, frameBuffer * 3);
        int trackBuffer = Math.max(minOut, frameBuffer * 3);

        AudioRecord record = null;
        AudioTrack track = null;
        NoiseSuppressor noiseSuppressor = null;
        AutomaticGainControl automaticGain = null;

        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(farPickupEnabled
                            ? MediaRecorder.AudioSource.MIC
                            : MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_IN)
                            .build())
                    .setBufferSizeInBytes(recordBuffer)
                    .build();

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(bluetoothRoute
                                    ? AudioAttributes.USAGE_MEDIA
                                    : AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_OUT)
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

            AudioDeviceInfo preferredOutput = findPreferredOutputDevice();
            if (preferredOutput != null) {
                track.setPreferredDevice(preferredOutput);
            }

            int sessionId = record.getAudioSessionId();
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
            VoiceProcessor voiceProcessor = new VoiceProcessor();
            FeedbackGuard feedbackGuard = new FeedbackGuard();
            LoudnessGuard loudnessGuard = new LoudnessGuard();
            record.startRecording();
            track.play();
            trimPlaybackBuffer(track, frameBuffer);

            while (running) {
                int read = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    continue;
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
                listener.onLevel(level);
                track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Exception e) {
            postError(e.getMessage() == null
                    ? "\u5b9e\u65f6\u76d1\u542c\u542f\u52a8\u5931\u8d25"
                    : e.getMessage());
        } finally {
            releaseEffect(automaticGain);
            releaseEffect(noiseSuppressor);
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
            boolean enhanceVoice, boolean farPickup, VoiceProcessor voiceProcessor,
            FeedbackGuard feedbackGuard) {
        long sum = 0L;
        int peak = 0;
        int limit = Math.round(Short.MAX_VALUE * outputLimit);
        for (int i = 0; i < length; i++) {
            float input = buffer[i];
            if (enhanceVoice) {
                input = voiceProcessor.highPass(input);
                input = voiceProcessor.voiceShape(input);
                float absInput = Math.abs(input);
                if (farPickup && absInput > 45.0f && absInput < 2200.0f) {
                    input *= absInput < 760.0f ? 1.58f : 1.42f;
                }
                if (absInput < (farPickup ? 45.0f : 90.0f)) {
                    input *= 0.35f;
                }
            }
            int sample = Math.round(input * gain);
            if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE;
            } else if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE;
            }
            if (enhanceVoice) {
                sample = compressAndLimit(sample, limit);
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

    private static int compressAndLimit(int sample, int limit) {
        int sign = sample < 0 ? -1 : 1;
        int abs = Math.abs(sample);
        int knee = Math.round(limit * 0.70f);
        if (abs > knee) {
            abs = knee + Math.round((abs - knee) * 0.25f);
        }
        if (abs > limit) {
            abs = limit;
        }
        return sign * abs;
    }

    private void postError(String message) {
        listener.onError(message);
    }

    private static void releaseEffect(android.media.audiofx.AudioEffect effect) {
        if (effect != null) {
            effect.release();
        }
    }

    private static void trimPlaybackBuffer(AudioTrack track, int frameBuffer) {
        try {
            track.setBufferSizeInFrames(frameBuffer * 3);
        } catch (RuntimeException ignored) {
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
            return "\u6709\u7ebf\u8033\u673a\u5df2\u8fde\u63a5\uff0c\u4f4e\u5ef6\u8fdf\u4f18\u5148";
        }
        if (bluetooth) {
            return "\u84dd\u7259\u8033\u673a\u5df2\u8fde\u63a5\uff0cApp \u5185\u90e8\u5df2\u538b\u4f4e\u5ef6\u8fdf";
        }
        return "\u672a\u68c0\u6d4b\u5230\u8033\u673a\uff0c\u8bf7\u8fde\u63a5\u6709\u7ebf\u6216\u84dd\u7259\u8033\u673a";
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

    @SuppressLint("MissingPermission")
    public boolean hasConnectedBluetoothAudioProfile() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP)
                == BluetoothProfile.STATE_CONNECTED
                || bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                == BluetoothProfile.STATE_CONNECTED
                || bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEARING_AID)
                == BluetoothProfile.STATE_CONNECTED
                || getLeAudioConnectionState() == BluetoothProfile.STATE_CONNECTED;
    }

    private int getLeAudioConnectionState() {
        try {
            return bluetoothAdapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO);
        } catch (Throwable ignored) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
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
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    private static final class VoiceProcessor {
        private static final float HIGH_PASS_ALPHA = 0.972f;

        private float previousInput;
        private float previousOutput;
        private float previousPresenceInput;
        private float smoothedPresence;

        float highPass(float input) {
            float output = HIGH_PASS_ALPHA * (previousOutput + input - previousInput);
            previousInput = input;
            previousOutput = output;
            return output;
        }

        float voiceShape(float input) {
            float edge = input - previousPresenceInput;
            previousPresenceInput = input;
            smoothedPresence = smoothedPresence * 0.72f + edge * 0.28f;
            return input * 1.04f + smoothedPresence * 0.34f;
        }
    }

    private static final class FeedbackGuard {
        private int hotFrames;

        void observe(float averageLevel, float peakLevel) {
            if (peakLevel > 0.82f && averageLevel > 0.32f) {
                hotFrames++;
            } else if (hotFrames > 0) {
                hotFrames--;
            }
        }

        boolean shouldReduceGain() {
            return hotFrames > 36;
        }

        void reset() {
            hotFrames = 0;
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
