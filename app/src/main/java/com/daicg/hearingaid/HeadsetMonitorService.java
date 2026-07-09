package com.daicg.hearingaid;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.IBinder;

public final class HeadsetMonitorService extends Service {
    private AudioManager audioManager;
    private boolean receiverRegistered;
    private boolean audioCallbackRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkHeadsetAndStart();
        }
    };

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            checkHeadsetAndStart();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            HearingEngine engine = new HearingEngine(HeadsetMonitorService.this, NoopListener.INSTANCE);
            if (!engine.hasWiredOutput() && !engine.hasBluetoothOutput()) {
                HearingAidService.stop(HeadsetMonitorService.this);
            }
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, HeadsetMonitorService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, HeadsetMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannels(this);
        try {
            startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID,
                    NotificationHelper.monitorNotification(this));
        } catch (RuntimeException exception) {
            stopSelf();
            return;
        }
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
            audioCallbackRegistered = true;
        }
        registerRouteReceiver();
        receiverRegistered = true;
        checkHeadsetAndStart();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRouteReceiver() {
        registerReceiver(receiver, routeFilter());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        checkHeadsetAndStart();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (audioManager != null && audioCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
            audioCallbackRegistered = false;
        }
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkHeadsetAndStart() {
        if (!AppSettings.autoMonitorEnabled(this)) {
            stopSelf();
            return;
        }
        if (AppSettings.autoListenPaused(this)) {
            HearingAidService.stop(this);
            return;
        }
        if (AppSettings.callActive(this)) {
            HearingAidService.stop(this);
            CallAssistReceiver.setVoiceCallVolumeMax(this);
            return;
        }
        HearingEngine engine = new HearingEngine(this, NoopListener.INSTANCE);
        if ((engine.hasWiredOutput() || engine.hasBluetoothOutput())
                && !HearingAidService.isActive()) {
            HearingAidService.start(this);
        }
    }

    private IntentFilter routeFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        return filter;
    }
}
