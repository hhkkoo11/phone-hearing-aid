package com.daicg.hearingaid;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    static final String PREFS = "hearing_aid_settings";
    static final String KEY_AUTO_MONITOR = "auto_monitor";
    static final String KEY_LOUD_WARNING = "loud_warning";
    static final String KEY_SYNC_PHONE_VOLUME = "sync_phone_volume";
    static final String KEY_VOICE_GUIDE = "voice_guide";
    static final String KEY_SCENE_MODE = "scene_mode";
    static final String KEY_WIRED_AUTO_START = "wired_auto_start";
    static final String KEY_AUTO_LISTEN_PAUSED = "auto_listen_paused";
    static final String KEY_SETUP_HINT_SHOWN = "setup_hint_shown";
    static final String KEY_LAST_PERMISSION_CHECK_PROMPT_AT = "last_permission_check_prompt_at";
    static final String KEY_SELF_VOICE_PROFILE_ENABLED = "self_voice_profile_enabled";
    static final String KEY_SELF_VOICE_PROFILE_ZCR = "self_voice_profile_zcr";
    static final String KEY_SELF_VOICE_PROFILE_DIFF = "self_voice_profile_diff";
    static final String KEY_SELF_VOICE_PROFILE_PEAK = "self_voice_profile_peak";

    static final String MODE_BLUETOOTH_DAILY = "bluetooth_daily";
    static final String MODE_WIRED_INDOOR = "wired_indoor";
    static final String MODE_POCKET = "pocket";
    static final String MODE_SEVERE = "severe";
    static final String MODE_BONE_CONDUCTION = "bone_conduction";

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
}
