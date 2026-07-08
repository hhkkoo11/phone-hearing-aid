package com.daicg.hearingaid;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

public final class HearingAidService extends Service implements HearingEngine.Listener {
    private static volatile boolean active;

    private HearingEngine engine;

    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, HearingAidService.class));
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, HearingAidService.class));
    }

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannels(this);
        startForeground(NotificationHelper.LISTENING_NOTIFICATION_ID,
                NotificationHelper.listeningNotification(this));
        if (AppSettings.autoListenPaused(this)) {
            stopSelf();
            return;
        }
        active = true;
        engine = new HearingEngine(this, this);
        applySavedMode();
        engine.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (AppSettings.autoListenPaused(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!engine.isRunning()) {
            applySavedMode();
            engine.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        if (engine != null) {
            engine.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLevel(float level) {
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    @Override
    public void onGainReduced(float gain) {
        Toast.makeText(this, "\u68c0\u6d4b\u5230\u5578\u53eb\u98ce\u9669\uff0c\u5df2\u81ea\u52a8\u964d\u4f4e\u589e\u76ca",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLoudListening(float gain) {
        if (AppSettings.loudWarningEnabled(this)) {
            Toast.makeText(this, "\u5f53\u524d\u6536\u97f3\u8f83\u5927\uff0c\u5982\u679c\u523a\u8033\u8bf7\u964d\u4f4e\u97f3\u91cf",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void applySavedMode() {
        String mode = AppSettings.sceneMode(this);
        applySelfVoiceProfile();
        boolean profileEnabled = AppSettings.selfVoiceProfileEnabled(this)
                && AppSettings.hasSelfVoiceProfile(this);
        if (AppSettings.MODE_WIRED_INDOOR.equals(mode)) {
            engine.setGain(5.0f);
            engine.setOutputLimit(0.78f);
            engine.setVoiceEnhancementEnabled(true);
            engine.setFarPickupEnabled(false);
            engine.setEchoCancellationEnabled(false);
            engine.setSelfVoiceReductionEnabled(profileEnabled);
            engine.setNoiseSuppressionEnabled(true);
            engine.setAutomaticGainEnabled(false);
        } else if (AppSettings.MODE_POCKET.equals(mode)) {
            engine.setGain(4.0f);
            engine.setOutputLimit(0.72f);
            engine.setVoiceEnhancementEnabled(true);
            engine.setFarPickupEnabled(false);
            engine.setEchoCancellationEnabled(false);
            engine.setSelfVoiceReductionEnabled(false);
            engine.setNoiseSuppressionEnabled(true);
            engine.setAutomaticGainEnabled(false);
        } else if (AppSettings.MODE_SEVERE.equals(mode)) {
            engine.setGain(7.0f);
            engine.setOutputLimit(0.86f);
            engine.setVoiceEnhancementEnabled(true);
            engine.setFarPickupEnabled(true);
            engine.setEchoCancellationEnabled(false);
            engine.setSelfVoiceReductionEnabled(false);
            engine.setNoiseSuppressionEnabled(true);
            engine.setAutomaticGainEnabled(true);
        } else {
            engine.setGain(4.0f);
            engine.setOutputLimit(0.74f);
            engine.setVoiceEnhancementEnabled(true);
            engine.setFarPickupEnabled(true);
            engine.setEchoCancellationEnabled(false);
            engine.setSelfVoiceReductionEnabled(true);
            engine.setNoiseSuppressionEnabled(true);
            engine.setAutomaticGainEnabled(false);
        }
        engine.setFeedbackProtectionEnabled(true);
    }

    private void applySelfVoiceProfile() {
        boolean enabled = AppSettings.selfVoiceProfileEnabled(this)
                && AppSettings.hasSelfVoiceProfile(this);
        engine.setSelfVoiceProfile(
                enabled,
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_ZCR, 0.0f),
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_DIFF, 0.0f),
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_PEAK, 0.0f));
    }
}
