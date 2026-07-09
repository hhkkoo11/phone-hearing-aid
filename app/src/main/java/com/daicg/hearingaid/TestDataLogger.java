package com.daicg.hearingaid;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TestDataLogger {
    private static final String FILE_NAME = "hearing-aid-outdoor-test.csv";
    private static long lastLevelWriteAt;

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
}
