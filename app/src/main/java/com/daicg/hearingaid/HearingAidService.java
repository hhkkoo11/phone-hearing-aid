package com.daicg.hearingaid;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

public final class HearingAidService extends Service implements HearingEngine.Listener {
    private static final String ACTION_REFRESH = "com.daicg.hearingaid.REFRESH";
    static final String ACTION_LEVEL = "com.daicg.hearingaid.LEVEL";
    static final String EXTRA_LEVEL = "level";
    private static volatile boolean active;

    private HearingEngine engine;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastLevelBroadcastAt;
    private long lastFeedbackToastAt;
    private boolean screenReceiverRegistered;
    private boolean dataCollectionEnabled;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!dataCollectionEnabled) {
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                TestDataLogger.appendEvent(HearingAidService.this, "screen_off");
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                TestDataLogger.appendEvent(HearingAidService.this, "screen_on");
            }
        }
    };

    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, HearingAidService.class));
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, HearingAidService.class));
    }

    public static void refresh(Context context) {
        if (!active) {
            return;
        }
        try {
            Intent intent = new Intent(context, HearingAidService.class);
            intent.setAction(ACTION_REFRESH);
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannels(this);
        try {
            startForeground(NotificationHelper.LISTENING_NOTIFICATION_ID,
                    NotificationHelper.listeningNotification(this));
        } catch (SecurityException exception) {
            active = false;
            stopSelf();
            return;
        }
        if (AppSettings.autoListenPaused(this)) {
            stopSelf();
            return;
        }
        if (AppSettings.callActive(this)) {
            CallAssistReceiver.setVoiceCallVolumeMax(this);
            CallAudioEnhancer.enable();
            stopSelf();
            return;
        }
        registerScreenReceiver();
        ensureEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (AppSettings.autoListenPaused(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (AppSettings.callActive(this)) {
            CallAssistReceiver.setVoiceCallVolumeMax(this);
            CallAudioEnhancer.enable();
            stopSelf();
            return START_NOT_STICKY;
        }
        registerScreenReceiver();
        ensureEngine();
        if (ACTION_REFRESH.equals(intent == null ? null : intent.getAction())) {
            applySavedMode();
            return START_STICKY;
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
        unregisterScreenReceiver();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!AppSettings.autoListenPaused(this)) {
            HeadsetMonitorService.start(this);
            if (engine != null && !engine.isRunning()) {
                applySavedMode();
                engine.start();
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLevel(float level) {
        if (dataCollectionEnabled) {
            TestDataLogger.appendLevel(this, level);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLevelBroadcastAt < 80L) {
            return;
        }
        lastLevelBroadcastAt = now;
        Intent intent = new Intent(ACTION_LEVEL);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_LEVEL, level);
        sendBroadcast(intent);
    }

    @Override
    public void onError(String message) {
        mainHandler.post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            stopSelf();
        });
    }

    @Override
    public void onGainReduced(float gain) {
        long now = System.currentTimeMillis();
        if (now - lastFeedbackToastAt > 30L * 1000L) {
            lastFeedbackToastAt = now;
            mainHandler.post(() -> Toast.makeText(this,
                    "\u68c0\u6d4b\u5230\u5578\u53eb\u98ce\u9669\uff0c\u5df2\u81ea\u52a8\u964d\u4f4e\u589e\u76ca",
                    Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onLoudListening(float gain) {
        if (AppSettings.loudWarningEnabled(this)) {
            mainHandler.post(() -> Toast.makeText(this,
                    "\u5f53\u524d\u6536\u97f3\u8f83\u5927\uff0c\u5982\u679c\u523a\u8033\u8bf7\u964d\u4f4e\u97f3\u91cf",
                    Toast.LENGTH_SHORT).show());
        }
    }

    private void applySavedMode() {
        if (engine == null) {
            return;
        }
        String mode = AppSettings.sceneMode(this);
        float savedGain = AppSettings.gain(this);
        if (savedGain > 12.0f) {
            savedGain = AppSettings.DEFAULT_GAIN;
            AppSettings.prefs(this).edit()
                    .putFloat(AppSettings.KEY_GAIN, savedGain)
                    .putInt(AppSettings.KEY_VOLUME_STEP, 1)
                    .apply();
        }
        applySelfVoiceProfile();
        boolean profileEnabled = AppSettings.selfVoiceProfileEnabled(this)
                && AppSettings.hasSelfVoiceProfile(this);
        boolean voiceEnhancement = true;
        boolean farPickup = AppSettings.farPickupEnabled(this);
        boolean longRangePickup = AppSettings.longRangePickupEnabled(this);
        boolean echoCancellation = AppSettings.echoCancellationEnabled(this);
        boolean selfVoiceReduction = false;
        boolean noiseSuppression = AppSettings.noiseSuppressionEnabled(this);
        boolean aiNoiseSuppression = AppSettings.aiNoiseSuppressionEnabled(this);
        boolean automaticGain = AppSettings.automaticGainEnabled(this);
        dataCollectionEnabled = AppSettings.outdoorDataCollectionEnabled(this);
        engine.setInputSourceMode(AppSettings.inputSourceMode(this));
        engine.setAiNoiseSuppressionEnabled(aiNoiseSuppression);
        engine.setLoudnessBoostEnabled(AppSettings.moderateLoudnessBoostEnabled(this));
        setSystemMusicVolumeMax();
        if (AppSettings.MODE_WIRED_INDOOR.equals(mode)) {
            engine.setGain(Math.max(savedGain, AppSettings.DEFAULT_GAIN));
            engine.setOutputLimit(0.92f);
            engine.setBoneConductionNoiseControlEnabled(false);
            engine.setInputSourceMode(AppSettings.INPUT_PHONE_MIC);
            engine.setVoiceEnhancementEnabled(true);
            engine.setFarPickupEnabled(true);
            engine.setLongRangePickupEnabled(longRangePickup);
            engine.setEchoCancellationEnabled(echoCancellation);
            engine.setSelfVoiceReductionEnabled(selfVoiceReduction && profileEnabled);
            engine.setNoiseSuppressionEnabled(false);
            engine.setAiNoiseSuppressionEnabled(false);
            engine.setAutomaticGainEnabled(automaticGain);
        } else if (AppSettings.MODE_POCKET.equals(mode)) {
            engine.setGain(savedGain);
            engine.setOutputLimit(0.72f);
            engine.setBoneConductionNoiseControlEnabled(false);
            engine.setVoiceEnhancementEnabled(voiceEnhancement);
            engine.setFarPickupEnabled(farPickup);
            engine.setLongRangePickupEnabled(longRangePickup);
            engine.setEchoCancellationEnabled(echoCancellation);
            engine.setSelfVoiceReductionEnabled(selfVoiceReduction);
            engine.setNoiseSuppressionEnabled(noiseSuppression);
            engine.setAutomaticGainEnabled(automaticGain);
        } else if (AppSettings.MODE_SEVERE.equals(mode)) {
            engine.setGain(savedGain);
            engine.setOutputLimit(0.96f);
            engine.setBoneConductionNoiseControlEnabled(false);
            engine.setVoiceEnhancementEnabled(voiceEnhancement);
            engine.setFarPickupEnabled(farPickup);
            engine.setLongRangePickupEnabled(longRangePickup);
            engine.setEchoCancellationEnabled(echoCancellation);
            engine.setSelfVoiceReductionEnabled(selfVoiceReduction);
            engine.setNoiseSuppressionEnabled(noiseSuppression);
            engine.setAutomaticGainEnabled(automaticGain);
        } else if (AppSettings.MODE_BONE_CONDUCTION.equals(mode)) {
            engine.setGain(Math.max(savedGain, 7.0f));
            engine.setOutputLimit(0.96f);
            engine.setBoneConductionNoiseControlEnabled(true);
            engine.setVoiceEnhancementEnabled(voiceEnhancement);
            engine.setFarPickupEnabled(farPickup);
            engine.setLongRangePickupEnabled(longRangePickup);
            engine.setEchoCancellationEnabled(echoCancellation);
            engine.setSelfVoiceReductionEnabled(selfVoiceReduction);
            engine.setNoiseSuppressionEnabled(false);
            engine.setAiNoiseSuppressionEnabled(false);
            engine.setVoiceEnhancementEnabled(false);
            engine.setAutomaticGainEnabled(false);
            setSystemMusicVolumeMax();
        } else {
            engine.setGain(savedGain);
            engine.setOutputLimit(0.90f);
            engine.setBoneConductionNoiseControlEnabled(false);
            engine.setInputSourceMode(AppSettings.INPUT_PHONE_MIC);
            engine.setVoiceEnhancementEnabled(false);
            engine.setFarPickupEnabled(farPickup);
            engine.setLongRangePickupEnabled(longRangePickup);
            engine.setEchoCancellationEnabled(echoCancellation);
            engine.setSelfVoiceReductionEnabled(selfVoiceReduction);
            engine.setNoiseSuppressionEnabled(false);
            engine.setAiNoiseSuppressionEnabled(false);
            engine.setAutomaticGainEnabled(automaticGain);
        }
        engine.setFeedbackProtectionEnabled(true);
    }

    private void setSystemMusicVolumeMax() {
        android.media.AudioManager audioManager =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                    0);
        }
    }

    private void applySelfVoiceProfile() {
        if (engine == null) {
            return;
        }
        boolean enabled = AppSettings.selfVoiceProfileEnabled(this)
                && AppSettings.hasSelfVoiceProfile(this);
        engine.setSelfVoiceProfile(
                enabled,
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_ZCR, 0.0f),
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_DIFF, 0.0f),
                AppSettings.prefs(this).getFloat(AppSettings.KEY_SELF_VOICE_PROFILE_PEAK, 0.0f));
    }

    private void ensureEngine() {
        active = true;
        acquireWakeLock();
        if (engine == null) {
            engine = new HearingEngine(this, this);
        }
        applySavedMode();
        if (!engine.isRunning()) {
            engine.start();
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
        screenReceiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return;
        }
        unregisterReceiver(screenReceiver);
        screenReceiverRegistered = false;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneHearingAid:Listening");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
