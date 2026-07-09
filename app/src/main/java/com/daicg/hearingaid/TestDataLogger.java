package com.daicg.hearingaid;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TestDataLogger {
    private static final String FILE_NAME = "hearing-aid-outdoor-test.csv";
    private static final String AUDIO_FILE_PREFIX = "hearing-aid-audio-sample-";
    private static long lastLevelWriteAt;
    private static long lastStatsWriteAt;
    private static File activeAudioFile;
    private static FileOutputStream activeAudioStream;

    private TestDataLogger() {
    }

    static void appendLevel(Context context, float level) {
        if (!AppSettings.outdoorDataCollectionEnabled(context)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastLevelWriteAt < 500L) {
            return;
        }
        lastLevelWriteAt = now;
        append(context, "level", level);
    }

    static void appendFrameStats(Context context, short[] buffer, int length, int sampleRate) {
        if (!AppSettings.outdoorDataCollectionEnabled(context)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastStatsWriteAt >= 250L) {
            lastStatsWriteAt = now;
            appendStats(context, buffer, length, sampleRate);
        }
        appendAudioFrameIfRequested(context, buffer, length, sampleRate, now);
    }

    static void appendEvent(Context context, String event) {
        append(context, event, -1.0f);
    }

    static File dataFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return new File(dir, FILE_NAME);
    }

    static void requestAudioSample(Context context, long durationMillis) {
        long until = SystemClock.uptimeMillis() + durationMillis;
        AppSettings.prefs(context).edit()
                .putLong(AppSettings.KEY_CAPTURE_AUDIO_UNTIL, until)
                .apply();
        closeAudioStream();
        appendEvent(context, "audio_sample_start");
    }

    private static void append(Context context, String event, float level) {
        File file = dataFile(context);
        boolean needsHeader = !file.exists() || file.length() == 0L;
        try (FileWriter writer = new FileWriter(file, true)) {
            if (needsHeader) {
                writer.write("time,event,level_percent,gain,scene_mode,input_source,output_route,"
                        + "voice,far_pickup,long_range,noise,agc,self_voice,echo,version\n");
            }
            writer.write(csv(nowText()));
            writer.write(',');
            writer.write(csv(event));
            writer.write(',');
            writer.write(level >= 0.0f ? String.valueOf(Math.round(level * 100.0f)) : "");
            writer.write(',');
            writer.write(String.format(Locale.US, "%.1f", AppSettings.gain(context)));
            writer.write(',');
            writer.write(csv(AppSettings.sceneMode(context)));
            writer.write(',');
            writer.write(csv(AppSettings.inputSourceMode(context)));
            writer.write(',');
            writer.write(csv(outputRoute(context)));
            writer.write(',');
            writer.write(bool(AppSettings.voiceEnhancementEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.farPickupEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.longRangePickupEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.noiseSuppressionEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.automaticGainEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.selfVoiceReductionEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.echoCancellationEnabled(context)));
            writer.write(',');
            writer.write(csv(versionName(context)));
            writer.write('\n');
        } catch (IOException ignored) {
        }
    }

    private static void appendStats(Context context, short[] buffer, int length, int sampleRate) {
        File file = dataFile(context);
        boolean needsHeader = !file.exists() || file.length() == 0L;
        FrameStats stats = FrameStats.from(buffer, length);
        try (FileWriter writer = new FileWriter(file, true)) {
            if (needsHeader) {
                writer.write("time,event,level_percent,gain,scene_mode,input_source,output_route,"
                        + "voice,far_pickup,long_range,noise,agc,self_voice,echo,version,"
                        + "sample_rate,rms,peak,zero_cross_percent,clip_percent\n");
            }
            writer.write(csv(nowText()));
            writer.write(',');
            writer.write(csv("frame_stats"));
            writer.write(',');
            writer.write(String.valueOf(stats.levelPercent));
            writer.write(',');
            writer.write(String.format(Locale.US, "%.1f", AppSettings.gain(context)));
            writer.write(',');
            writer.write(csv(AppSettings.sceneMode(context)));
            writer.write(',');
            writer.write(csv(AppSettings.inputSourceMode(context)));
            writer.write(',');
            writer.write(csv(outputRoute(context)));
            writer.write(',');
            writer.write(bool(AppSettings.voiceEnhancementEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.farPickupEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.longRangePickupEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.noiseSuppressionEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.automaticGainEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.selfVoiceReductionEnabled(context)));
            writer.write(',');
            writer.write(bool(AppSettings.echoCancellationEnabled(context)));
            writer.write(',');
            writer.write(csv(versionName(context)));
            writer.write(',');
            writer.write(String.valueOf(sampleRate));
            writer.write(',');
            writer.write(String.valueOf(Math.round(stats.rms)));
            writer.write(',');
            writer.write(String.valueOf(stats.peak));
            writer.write(',');
            writer.write(String.format(Locale.US, "%.2f", stats.zeroCrossPercent));
            writer.write(',');
            writer.write(String.format(Locale.US, "%.2f", stats.clipPercent));
            writer.write('\n');
        } catch (IOException ignored) {
        }
    }

    private static void appendAudioFrameIfRequested(
            Context context, short[] buffer, int length, int sampleRate, long now) {
        long until = AppSettings.prefs(context).getLong(AppSettings.KEY_CAPTURE_AUDIO_UNTIL, 0L);
        if (until <= now) {
            if (activeAudioStream != null) {
                appendEvent(context, "audio_sample_stop");
                closeAudioStream();
            }
            return;
        }
        try {
            if (activeAudioStream == null) {
                activeAudioFile = new File(dataFile(context).getParentFile(),
                        AUDIO_FILE_PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                                + "-" + sampleRate + "hz-mono-16bit.pcm");
                activeAudioStream = new FileOutputStream(activeAudioFile, true);
                appendEvent(context, "audio_file=" + activeAudioFile.getAbsolutePath());
            }
            byte[] bytes = new byte[length * 2];
            for (int i = 0; i < length; i++) {
                short sample = buffer[i];
                bytes[i * 2] = (byte) (sample & 0xff);
                bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
            }
            activeAudioStream.write(bytes);
        } catch (IOException ignored) {
            closeAudioStream();
        }
    }

    private static void closeAudioStream() {
        if (activeAudioStream != null) {
            try {
                activeAudioStream.close();
            } catch (IOException ignored) {
            }
            activeAudioStream = null;
            activeAudioFile = null;
        }
    }

    private static String outputRoute(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return "unknown";
        }
        boolean wired = false;
        boolean bluetooth = false;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            wired |= type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
            bluetooth |= type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
        }
        if (wired) {
            return "wired";
        }
        if (bluetooth) {
            return "bluetooth";
        }
        return "none";
    }

    private static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static String bool(boolean value) {
        return value ? "1" : "0";
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static final class FrameStats {
        final int levelPercent;
        final float rms;
        final int peak;
        final float zeroCrossPercent;
        final float clipPercent;

        private FrameStats(int levelPercent, float rms, int peak, float zeroCrossPercent, float clipPercent) {
            this.levelPercent = levelPercent;
            this.rms = rms;
            this.peak = peak;
            this.zeroCrossPercent = zeroCrossPercent;
            this.clipPercent = clipPercent;
        }

        static FrameStats from(short[] buffer, int length) {
            if (length <= 0) {
                return new FrameStats(0, 0.0f, 0, 0.0f, 0.0f);
            }
            long sumSquares = 0L;
            int peak = 0;
            int zeroCross = 0;
            int clips = 0;
            int previous = buffer[0];
            for (int i = 0; i < length; i++) {
                int value = buffer[i];
                int abs = Math.abs(value);
                peak = Math.max(peak, abs);
                sumSquares += (long) value * value;
                if (i > 0 && ((previous < 0 && value >= 0) || (previous >= 0 && value < 0))) {
                    zeroCross++;
                }
                if (abs >= 32000) {
                    clips++;
                }
                previous = value;
            }
            float rms = (float) Math.sqrt(sumSquares / (double) length);
            int levelPercent = Math.min(100, Math.round((rms / 32768.0f) * 300.0f));
            float denominator = Math.max(1, length - 1);
            return new FrameStats(
                    levelPercent,
                    rms,
                    peak,
                    zeroCross * 100.0f / denominator,
                    clips * 100.0f / length);
        }
    }
}
