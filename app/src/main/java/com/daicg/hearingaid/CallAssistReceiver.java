package com.daicg.hearingaid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.telephony.TelephonyManager;

public final class CallAssistReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }
        if (!AppSettings.callAssistEnabled(context)) {
            return;
        }
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)
                || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            handleCallStarted(context);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            handleCallEnded(context);
        }
    }

    static void handleCallStarted(Context context) {
        boolean wasListening = HearingAidService.isActive();
        AppSettings.prefs(context).edit()
                .putBoolean(AppSettings.KEY_CALL_ACTIVE, true)
                .putBoolean(AppSettings.KEY_CALL_ASSIST_WAS_LISTENING, wasListening)
                .apply();
        HearingAidService.stop(context);
        setVoiceCallVolumeMax(context);
        CallAudioEnhancer.enable();
    }

    static void handleCallEnded(Context context) {
        boolean shouldRestore = AppSettings.prefs(context)
                .getBoolean(AppSettings.KEY_CALL_ASSIST_WAS_LISTENING, false);
        AppSettings.prefs(context).edit()
                .putBoolean(AppSettings.KEY_CALL_ACTIVE, false)
                .putBoolean(AppSettings.KEY_CALL_ASSIST_WAS_LISTENING, false)
                .apply();
        if (shouldRestore
                && MainActivity.isVisible()
                && AppSettings.autoMonitorEnabled(context)
                && !AppSettings.autoListenPaused(context)
                && hasAnyHeadsetOutput(context)) {
            HearingAidService.start(context);
        }
        CallAudioEnhancer.disable();
    }

    static void setVoiceCallVolumeMax(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        try {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean hasAnyHeadsetOutput(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true;
            }
        }
        return false;
    }
}
