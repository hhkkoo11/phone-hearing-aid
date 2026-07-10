package com.daicg.hearingaid;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;

final class AppSettings {
    static final String PREFS = "hearing_aid_settings";
    static final String KEY_AUTO_MONITOR = "auto_monitor";
    static final String KEY_LOUD_WARNING = "loud_warning";
    static final String KEY_SYNC_PHONE_VOLUME = "sync_phone_volume";
    static final String KEY_VOICE_GUIDE = "voice_guide";
    static final String KEY_CALL_ASSIST = "call_assist";
    static final String KEY_CALL_ACTIVE = "call_active";
    static final String KEY_CALL_ASSIST_WAS_LISTENING = "call_assist_was_listening";
    static final String KEY_OUTDOOR_DATA_COLLECTION = "outdoor_data_collection";
    static final String KEY_CAPTURE_AUDIO_UNTIL = "capture_audio_until";
    static final String KEY_SCENE_MODE = "scene_mode";
    static final String KEY_WIRED_AUTO_START = "wired_auto_start";
    static final String KEY_AUTO_LISTEN_PAUSED = "auto_listen_paused";
    static final String KEY_SETUP_HINT_SHOWN = "setup_hint_shown";
    static final String KEY_LAST_PERMISSION_CHECK_PROMPT_AT = "last_permission_check_prompt_at";
    static final String KEY_SELF_VOICE_PROFILE_ENABLED = "self_voice_profile_enabled";
    static final String KEY_SELF_VOICE_PROFILE_ZCR = "self_voice_profile_zcr";
    static final String KEY_SELF_VOICE_PROFILE_DIFF = "self_voice_profile_diff";
    static final String KEY_SELF_VOICE_PROFILE_PEAK = "self_voice_profile_peak";
    static final String KEY_LONG_RANGE_PICKUP = "long_range_pickup";
    static final String KEY_INPUT_SOURCE_MODE = "input_source_mode";
    static final String KEY_GAIN = "gain";
    static final String KEY_VOLUME_STEP = "volume_step";
    static final String KEY_MODERATE_LOUDNESS_BOOST = "moderate_loudness_boost";
    static final String KEY_EXTRA_LOUD_MODE = "extra_loud_mode";
    static final String KEY_VOICE_ENHANCEMENT = "voice_enhancement";
    static final String KEY_FAR_PICKUP = "far_pickup";
    static final String KEY_ECHO_CANCELLATION = "echo_cancellation";
    static final String KEY_SELF_VOICE_REDUCTION = "self_voice_reduction";
    static final String KEY_NOISE_SUPPRESSION = "noise_suppression";
    static final String KEY_AI_NOISE_SUPPRESSION = "ai_noise_suppression";
    static final String KEY_AUTOMATIC_GAIN = "automatic_gain";
    static final float DEFAULT_GAIN = 48.0f;

    static final String MODE_BLUETOOTH_DAILY = "bluetooth_daily";
    static final String MODE_WIRED_INDOOR = "wired_indoor";
    static final String MODE_POCKET = "pocket";
    static final String MODE_SEVERE = "severe";
    static final String MODE_BONE_CONDUCTION = "bone_conduction";
    static final String INPUT_PHONE_MIC = "phone_mic";
    static final String INPUT_HEADSET_MIC = "headset_mic";
    static final String INPUT_AUTO = "auto";

    private AppSettings() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autoMonitorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_MONITOR, true);
    }

    static boolean loudWarningEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOUD_WARNING, true);
    }

    static boolean syncPhoneVolumeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SYNC_PHONE_VOLUME, true);
    }

    static boolean voiceGuideEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VOICE_GUIDE, true);
    }

    static boolean callAssistEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CALL_ASSIST, true);
    }

    static boolean callActive(Context context) {
        if (!prefs(context).getBoolean(KEY_CALL_ACTIVE, false)) {
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            clearCallState(context);
            return false;
        }
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            clearCallState(context);
            return false;
        }
        try {
            int state = telephonyManager.getCallState();
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                clearCallState(context);
                return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            clearCallState(context);
            return false;
        }
    }

    private static void clearCallState(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_CALL_ACTIVE, false)
                .putBoolean(KEY_CALL_ASSIST_WAS_LISTENING, false)
                .apply();
    }

    static boolean outdoorDataCollectionEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTDOOR_DATA_COLLECTION, false);
    }

    static boolean wiredAutoStartEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WIRED_AUTO_START, true);
    }

    static boolean autoListenPaused(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_LISTEN_PAUSED, false);
    }

    static String sceneMode(Context context) {
        return prefs(context).getString(KEY_SCENE_MODE, MODE_BLUETOOTH_DAILY);
    }

    static boolean selfVoiceProfileEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SELF_VOICE_PROFILE_ENABLED, false);
    }

    static boolean hasSelfVoiceProfile(Context context) {
        return prefs(context).contains(KEY_SELF_VOICE_PROFILE_ZCR)
                && prefs(context).contains(KEY_SELF_VOICE_PROFILE_DIFF)
                && prefs(context).contains(KEY_SELF_VOICE_PROFILE_PEAK);
    }

    static boolean longRangePickupEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LONG_RANGE_PICKUP, false);
    }

    static String inputSourceMode(Context context) {
        return prefs(context).getString(KEY_INPUT_SOURCE_MODE, INPUT_PHONE_MIC);
    }

    static float gain(Context context) {
        return Math.max(0.2f, Math.min(
                prefs(context).getFloat(KEY_GAIN, DEFAULT_GAIN),
                120.0f));
    }

    static int volumeStep(Context context) {
        return Math.max(0, Math.min(prefs(context).getInt(KEY_VOLUME_STEP, 1), 2));
    }

    static boolean extraLoudModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_EXTRA_LOUD_MODE, false);
    }

    static boolean moderateLoudnessBoostEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MODERATE_LOUDNESS_BOOST, true);
    }

    static boolean voiceEnhancementEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VOICE_ENHANCEMENT, false);
    }

    static boolean farPickupEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FAR_PICKUP, true);
    }

    static boolean echoCancellationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ECHO_CANCELLATION, false);
    }

    static boolean selfVoiceReductionEnabled(Context context) {
        return false;
    }

    static boolean noiseSuppressionEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOISE_SUPPRESSION, false);
    }

    static boolean aiNoiseSuppressionEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AI_NOISE_SUPPRESSION, false);
    }

    static boolean automaticGainEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC_GAIN, false);
    }
}
