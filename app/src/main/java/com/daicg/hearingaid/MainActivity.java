package com.daicg.hearingaid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements HearingEngine.Listener {
    private static final int REQUEST_AUDIO_PERMISSIONS = 1001;
    private static final String UPDATE_JSON_URL =
            "https://raw.githubusercontent.com/hhkkoo11/phone-hearing-aid/main/release/version.json";
    private static volatile boolean visible;

    private HearingEngine engine;
    private AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech textToSpeech;
    private TextView statusText;
    private TextView gainText;
    private TextView modeStatusText;
    private ProgressBar levelMeter;
    private Button toggleButton;
    private Button bluetoothModeButton;
    private Button wiredModeButton;
    private Button severeModeButton;
    private Button pocketModeButton;
    private SeekBar gainSeek;
    private Switch severeModeSwitch;
    private Switch wiredAutoStartSwitch;
    private Switch voiceSwitch;
    private Switch farPickupSwitch;
    private Switch noiseSwitch;
    private Switch agcSwitch;
    private Switch autoMonitorSwitch;
    private Switch loudWarningSwitch;
    private Switch syncPhoneVolumeSwitch;
    private Switch voiceGuideSwitch;
    private boolean suppressVolumeSync;
    private int lastSeenSystemVolume = -1;
    private long updateDownloadId = -1L;
    private Uri pendingInstallUri;
    private boolean downloadReceiverRegistered;
    private boolean suppressSwitchCallback;
    private boolean voiceEnhancementEnabled = true;
    private boolean farPickupEnabled = true;
    private boolean noiseSuppressionEnabled = true;
    private boolean automaticGainEnabled;

    private final Runnable volumeSyncPoller = new Runnable() {
        @Override
        public void run() {
            syncGainFromSystemVolumeIfChanged();
            mainHandler.postDelayed(this, 350);
        }
    };

    private final ContentObserver volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            syncGainFromSystemVolume();
        }
    };

    private final BroadcastReceiver routeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRouteStatus();
            String action = intent.getAction();
            if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                int state = intent.getIntExtra("state", -1);
                handleWiredHeadsetState(state);
            } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
                stopBecauseOutputWasRemoved();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                updateRouteStatus();
                autoStartIfWiredAlreadyConnected();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stopBecauseOutputWasRemoved();
            }
        }
    };

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != updateDownloadId) {
                return;
            }
            handleUpdateDownloadComplete(id);
        }
    };

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            runOnUiThread(() -> {
                updateRouteStatus();
                autoStartIfWiredAlreadyConnected();
            });
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            runOnUiThread(() -> {
                updateRouteStatus();
                if (!engine.hasWiredOutput() && !engine.hasBluetoothOutput()) {
                    stopBecauseOutputWasRemoved();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new HearingEngine(this, this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.CHINA);
                textToSpeech.setSpeechRate(0.92f);
            }
        });
        buildUi();
        registerRouteReceiver();
        getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver);
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
        updateRouteStatus();
        autoStartIfWiredAlreadyConnected();
        mainHandler.post(volumeSyncPoller);
    }

    public static boolean isVisible() {
        return visible;
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        HearingAidService.stop(this);
        mainHandler.post(volumeSyncPoller);
        if (pendingInstallUri != null && canInstallUnknownApps()) {
            Uri uri = pendingInstallUri;
            pendingInstallUri = null;
            installDownloadedApk(uri);
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        mainHandler.removeCallbacks(volumeSyncPoller);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(volumeObserver);
        mainHandler.removeCallbacks(volumeSyncPoller);
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        unregisterReceiver(routeReceiver);
        if (downloadReceiverRegistered) {
            unregisterReceiver(downloadReceiver);
            downloadReceiverRegistered = false;
        }
        engine.stop();
        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onLevel(float level) {
        runOnUiThread(() -> levelMeter.setProgress(Math.round(level * 100)));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            toast(message);
            setRunningUi(false);
        });
    }

    @Override
    public void onGainReduced(float gain) {
        runOnUiThread(() -> {
            setGainProgressForValue(gain);
            toast("\u68c0\u6d4b\u5230\u5578\u53eb\u98ce\u9669\uff0c\u5df2\u81ea\u52a8\u964d\u4f4e\u589e\u76ca");
        });
    }

    @Override
    public void onLoudListening(float gain) {
        runOnUiThread(() ->
                toast("\u5f53\u524d\u6536\u97f3\u8f83\u5927\uff0c\u5982\u679c\u523a\u8033\u8bf7\u6309 - \u964d\u4f4e\u97f3\u91cf"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO_PERMISSIONS) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            toast("\u9700\u8981\u9ea6\u514b\u98ce\u6743\u9650\u624d\u80fd\u4f7f\u7528");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isSyncPhoneVolumeEnabled()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                adjustGainBy(1.0f);
                speak("\u97f3\u91cf\u589e\u5927");
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                adjustGainBy(-1.0f);
                speak("\u97f3\u91cf\u51cf\u5c0f");
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildUi() {
        int pad = dp(20);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(0xFFF5F7F6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFFF5F7F6);

        Button settingsButton = new Button(this);
        settingsButton.setText("\u8bbe\u7f6e");
        settingsButton.setTextSize(15);
        settingsButton.setAllCaps(false);
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                dp(112),
                dp(44));
        settingsParams.gravity = Gravity.RIGHT;
        root.addView(settingsButton, settingsParams);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(0xFF315048);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(2), 0, dp(8));
        root.addView(statusText, matchWidthWrapHeight());

        TextView versionText = new TextView(this);
        versionText.setText("\u7248\u672c 1.2\uff1a\u8bbe\u7f6e\u5df2\u96c6\u4e2d");
        versionText.setTextSize(13);
        versionText.setTextColor(0xFF5A6B66);
        versionText.setGravity(Gravity.CENTER);
        versionText.setPadding(0, 0, 0, dp(8));
        root.addView(versionText, matchWidthWrapHeight());

        modeStatusText = new TextView(this);
        modeStatusText.setTextSize(17);
        modeStatusText.setTextColor(0xFF10231F);
        modeStatusText.setGravity(Gravity.CENTER);
        modeStatusText.setPadding(0, 0, 0, dp(10));
        root.addView(modeStatusText, matchWidthWrapHeight());

        toggleButton = new Button(this);
        toggleButton.setText("\u5f00\u59cb\u52a9\u542c");
        toggleButton.setTextSize(18);
        toggleButton.setAllCaps(false);
        toggleButton.setOnClickListener(v -> {
            if (engine.isRunning()) {
                engine.stop();
                setRunningUi(false);
            } else {
                ensurePermissionsThenStart();
            }
        });
        root.addView(toggleButton, matchWidthFixedHeight(56));
        root.addView(makeHelpText("\u5f00\u59cb/\u505c\u6b62\uff1a\u628a\u624b\u673a\u6536\u5230\u7684\u58f0\u97f3\u9001\u5230\u8033\u673a\u91cc\u3002"), matchWidthWrapHeight());

        gainText = new TextView(this);
        gainText.setText("\u589e\u76ca 2.0x");
        gainText.setTextSize(16);
        gainText.setTextColor(0xFF10231F);
        gainText.setPadding(0, dp(28), 0, dp(8));
        root.addView(gainText, matchWidthWrapHeight());

        gainSeek = new SeekBar(this);
        gainSeek.setMax(238);
        gainSeek.setProgress(18);
        gainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gain = 0.2f + progress / 10.0f;
                engine.setGain(gain);
                gainText.setText(String.format("\u589e\u76ca %.1fx", gain));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(gainSeek, matchWidthWrapHeight());

        LinearLayout gainButtons = new LinearLayout(this);
        gainButtons.setOrientation(LinearLayout.HORIZONTAL);
        gainButtons.setGravity(Gravity.CENTER);
        gainButtons.setPadding(0, dp(10), 0, dp(4));

        Button minusButton = makeGainButton("-");
        minusButton.setOnClickListener(v -> adjustGainBy(-1.0f));
        gainButtons.addView(minusButton, weightedButtonParams());

        Button plusButton = makeGainButton("+");
        plusButton.setOnClickListener(v -> adjustGainBy(1.0f));
        gainButtons.addView(plusButton, weightedButtonParams());

        root.addView(gainButtons, matchWidthFixedHeight(78));
        root.addView(makeHelpText("+ / -\uff1a\u8c03\u5927\u6216\u8c03\u5c0f\u58f0\u97f3\uff0c\u6bcf\u6b21\u8c03\u4e00\u5c0f\u683c\u3002"), matchWidthWrapHeight());

        TextView modeLabel = new TextView(this);
        modeLabel.setText("\u542c\u89c9\u6a21\u5f0f");
        modeLabel.setTextSize(16);
        modeLabel.setTextColor(0xFF10231F);
        modeLabel.setPadding(0, dp(16), 0, dp(8));
        root.addView(modeLabel, matchWidthWrapHeight());

        LinearLayout modeButtons = new LinearLayout(this);
        modeButtons.setOrientation(LinearLayout.HORIZONTAL);
        modeButtons.setGravity(Gravity.CENTER);

        bluetoothModeButton = makeModeButton("\u84dd\u7259\u65e5\u5e38");
        bluetoothModeButton.setOnClickListener(v -> applyBluetoothDailyMode());
        modeButtons.addView(bluetoothModeButton, weightedButtonParams());

        wiredModeButton = makeModeButton("\u6709\u7ebf\u5ba4\u5185");
        wiredModeButton.setOnClickListener(v -> applyWiredIndoorMode());
        modeButtons.addView(wiredModeButton, weightedButtonParams());

        severeModeButton = makeModeButton("\u91cd\u5ea6");
        severeModeButton.setOnClickListener(v -> applySevereMode());
        modeButtons.addView(severeModeButton, weightedButtonParams());

        root.addView(modeButtons, matchWidthFixedHeight(56));
        root.addView(makeHelpText("\u84dd\u7259\u65e5\u5e38\uff1a\u5916\u51fa\u548c\u5bb6\u91cc\u90fd\u80fd\u7528\u3002\u6709\u7ebf\u5ba4\u5185\uff1a\u5ba4\u5185\u5bf9\u8bdd\u66f4\u91cd\u4eba\u58f0\u3002\u91cd\u5ea6\uff1a\u66f4\u5927\u58f0\uff0c\u4f46\u6709\u9650\u5e45\u4fdd\u62a4\u3002"), matchWidthWrapHeight());

        pocketModeButton = new Button(this);
        pocketModeButton.setText("\u53e3\u888b\u6a21\u5f0f");
        pocketModeButton.setTextSize(16);
        pocketModeButton.setAllCaps(false);
        pocketModeButton.setOnClickListener(v -> applyPocketMode());
        root.addView(pocketModeButton, matchWidthFixedHeight(48));
        root.addView(makeHelpText("\u53e3\u888b\u6a21\u5f0f\uff1a\u624b\u673a\u653e\u53e3\u888b\u65f6\u7528\uff0c\u5c3d\u91cf\u51cf\u5c11\u8863\u670d\u6469\u64e6\u58f0\u548c\u95f7\u58f0\u3002"), matchWidthWrapHeight());

        Button maxButton = new Button(this);
        maxButton.setText("\u6700\u5927\u6863");
        maxButton.setTextSize(16);
        maxButton.setAllCaps(false);
        maxButton.setOnClickListener(v -> {
            engine.setOutputLimit(0.90f);
            setGainProgressForValue(24.0f);
            toast("\u5df2\u5230\u6700\u5927\u6863\uff0c\u8bf7\u6ce8\u610f\u9632\u6b62\u5578\u53eb\u548c\u8033\u75db");
        });
        root.addView(maxButton, matchWidthFixedHeight(48));
        root.addView(makeHelpText("\u6700\u5927\u6863\uff1a\u5df2\u7ecf\u662f\u8fd9\u4e2a App \u7684\u6700\u5927\u653e\u5927\u3002\u5982\u679c\u5578\u53eb\u6216\u523a\u8033\uff0c\u9a6c\u4e0a\u6309 -\u3002"), matchWidthWrapHeight());

        TextView levelLabel = new TextView(this);
        levelLabel.setText("\u8f93\u5165\u7535\u5e73");
        levelLabel.setTextSize(16);
        levelLabel.setTextColor(0xFF10231F);
        levelLabel.setPadding(0, dp(26), 0, dp(8));
        root.addView(levelLabel, matchWidthWrapHeight());

        levelMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelMeter.setMax(100);
        root.addView(levelMeter, matchWidthFixedHeight(22));
        root.addView(makeHelpText("\u8f93\u5165\u7535\u5e73\uff1a\u8fd9\u6761\u662f\u624b\u673a\u9ea6\u514b\u98ce\u73b0\u5728\u6536\u5230\u7684\u58f0\u97f3\u5927\u5c0f\uff0c\u4e0d\u662f\u8033\u673a\u97f3\u91cf\u3002"), matchWidthWrapHeight());

        TextView hint = new TextView(this);
        hint.setText("\u5efa\u8bae\u5148\u5f00\u91cd\u5ea6\u8033\u80cc\u6a21\u5f0f\u548c\u8fdc\u8ddd\u79bb\u6536\u58f0\uff0c\u518d\u6162\u6162\u8c03\u589e\u76ca\u3002\u5982\u679c\u51fa\u73b0\u5578\u53eb\uff0c\u5148\u964d\u4f4e\u589e\u76ca\u6216\u5173\u95ed\u8fdc\u8ddd\u79bb\u6536\u58f0\u3002");
        hint.setTextSize(14);
        hint.setTextColor(0xFF5A6B66);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint, matchWidthWrapHeight());

        scrollView.addView(root);
        setContentView(scrollView);
        applyBluetoothDailyMode();
        if (AppSettings.autoMonitorEnabled(this)) {
            HeadsetMonitorService.start(this);
        }
    }

    private Switch makeSwitch(String text, boolean checked) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(0xFF10231F);
        view.setChecked(checked);
        view.setPadding(0, dp(18), 0, 0);
        return view;
    }

    private Button makeGainButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(34);
        button.setAllCaps(false);
        return button;
    }

    private Button makeModeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setAllCaps(false);
        return button;
    }

    private TextView makeHelpText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFF66736F);
        view.setPadding(0, dp(4), 0, dp(6));
        return view;
    }

    private void showSettingsDialog() {
        int pad = dp(18);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(4), pad, dp(8));

        severeModeSwitch = addSettingSwitch(
                content,
                "\u91cd\u5ea6\u8033\u80cc\u6a21\u5f0f",
                AppSettings.MODE_SEVERE.equals(AppSettings.sceneMode(this)),
                "\u66f4\u5927\u58f0\uff0c\u4f46\u4ecd\u4fdd\u7559\u9650\u5e45\u548c\u5578\u53eb\u4fdd\u62a4\u3002",
                isChecked -> {
                    if (isChecked) {
                        applySevereMode();
                    } else {
                        applyBluetoothDailyMode();
                    }
                });

        voiceSwitch = addSettingSwitch(
                content,
                "\u4eba\u58f0\u6e05\u6670",
                voiceEnhancementEnabled,
                "\u4e3b\u8981\u8ba9\u8bf4\u8bdd\u58f0\u66f4\u6e05\u695a\uff0c\u5c11\u4e00\u70b9\u95f7\u3002",
                this::setVoiceEnhancementEnabled);

        farPickupSwitch = addSettingSwitch(
                content,
                "\u8fdc\u8ddd\u79bb\u6536\u58f0",
                farPickupEnabled,
                "\u60f3\u542c\u8fdc\u4e00\u70b9\u7684\u4eba\u8bf4\u8bdd\u5c31\u6253\u5f00\uff0c\u4f46\u6742\u97f3\u4e5f\u4f1a\u53d8\u591a\u3002",
                this::setFarPickupEnabled);

        wiredAutoStartSwitch = addSettingSwitch(
                content,
                "\u6709\u7ebf\u8033\u673a\u63d2\u5165\u81ea\u52a8\u5f00\u542f",
                isWiredAutoStartEnabled(),
                "\u68c0\u6d4b\u5230\u6807\u51c6\u6709\u7ebf\u6216 USB \u97f3\u9891\u8f93\u51fa\u65f6\u81ea\u52a8\u5f00\u59cb\u52a9\u542c\u3002",
                isChecked -> {
                    AppSettings.prefs(this).edit()
                            .putBoolean(AppSettings.KEY_WIRED_AUTO_START, isChecked)
                            .apply();
                    if (isChecked) {
                        autoStartIfWiredAlreadyConnected();
                    }
                });

        noiseSwitch = addSettingSwitch(
                content,
                "\u964d\u566a",
                noiseSuppressionEnabled,
                "\u5c3d\u91cf\u538b\u4f4e\u98ce\u58f0\u3001\u7a7a\u8c03\u58f0\u3001\u5e95\u566a\u3002",
                this::setNoiseSuppressionEnabled);

        agcSwitch = addSettingSwitch(
                content,
                "\u81ea\u52a8\u589e\u76ca",
                automaticGainEnabled,
                "\u5c0f\u58f0\u65f6\u81ea\u52a8\u62ac\u9ad8\uff0c\u4f46\u6709\u65f6\u4f1a\u628a\u6742\u97f3\u4e5f\u62ac\u9ad8\u3002",
                this::setAutomaticGainEnabled);

        autoMonitorSwitch = addSettingSwitch(
                content,
                "\u540e\u53f0\u81ea\u52a8\u76d1\u6d4b\u8033\u673a",
                AppSettings.autoMonitorEnabled(this),
                "\u4e0d\u6253\u5f00 App \u4e5f\u4f1a\u7b49\u8033\u673a\u8fde\u63a5\uff0c\u5e73\u65f6\u53ea\u662f\u4f4e\u529f\u8017\u5f85\u547d\u3002",
                isChecked -> {
                    AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_AUTO_MONITOR, isChecked).apply();
                    if (isChecked) {
                        HeadsetMonitorService.start(this);
                    } else {
                        HeadsetMonitorService.stop(this);
                        HearingAidService.stop(this);
                    }
                });

        loudWarningSwitch = addSettingSwitch(
                content,
                "\u6536\u97f3\u8fc7\u5927\u65f6\u63d0\u9192",
                AppSettings.loudWarningEnabled(this),
                "\u58f0\u97f3\u6301\u7eed\u504f\u5927\u65f6\u63d0\u9192\uff0c\u89c9\u5f97\u70e6\u53ef\u4ee5\u5173\u6389\u3002",
                isChecked -> AppSettings.prefs(this).edit()
                        .putBoolean(AppSettings.KEY_LOUD_WARNING, isChecked)
                        .apply());

        syncPhoneVolumeSwitch = addSettingSwitch(
                content,
                "\u8ddf\u968f\u624b\u673a\u97f3\u91cf",
                isSyncPhoneVolumeEnabled(),
                "App \u91cc\u7684 + / - \u548c\u624b\u673a\u97f3\u91cf\u952e\u4f1a\u4e00\u8d77\u8c03\u3002",
                isChecked -> {
                    AppSettings.prefs(this).edit()
                            .putBoolean(AppSettings.KEY_SYNC_PHONE_VOLUME, isChecked)
                            .apply();
                    if (isChecked) {
                        syncSystemVolumeFromGain();
                    }
                });

        voiceGuideSwitch = addSettingSwitch(
                content,
                "\u6309\u94ae\u8bed\u97f3\u64ad\u62a5",
                isVoiceGuideEnabled(),
                "\u70b9\u6309\u94ae\u65f6\u5927\u58f0\u8bf4\u51fa\u5f53\u524d\u64cd\u4f5c\uff0c\u4e0d\u60f3\u542c\u53ef\u4ee5\u5173\u6389\u3002",
                isChecked -> AppSettings.prefs(this).edit()
                        .putBoolean(AppSettings.KEY_VOICE_GUIDE, isChecked)
                        .apply());

        Button hearingTestButton = makeSettingsButton("\u7b80\u6613\u542c\u529b\u6d4b\u8bd5");
        hearingTestButton.setOnClickListener(v -> showHearingTestDialog());
        content.addView(hearingTestButton, matchWidthFixedHeight(48));
        content.addView(makeHelpText("\u64ad\u653e\u4e0d\u540c\u9891\u7387\u7684\u6d4b\u8bd5\u97f3\uff0c\u7528\u6765\u8bb0\u5f55\u54ea\u4e9b\u97f3\u66f4\u96be\u542c\u5230\u3002"), matchWidthWrapHeight());

        Button updateButton = makeSettingsButton("\u68c0\u67e5\u66f4\u65b0");
        updateButton.setOnClickListener(v -> checkForUpdate());
        content.addView(updateButton, matchWidthFixedHeight(48));
        content.addView(makeHelpText("\u6709\u65b0\u7248\u672c\u65f6\u4f1a\u5148\u4e0b\u8f7d\uff0c\u518d\u8df3\u5230\u7cfb\u7edf\u5b89\u88c5\u9875\u3002"), matchWidthWrapHeight());

        scrollView.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("\u8bbe\u7f6e")
                .setView(scrollView)
                .setPositiveButton("\u5b8c\u6210", null)
                .show();
    }

    private Switch addSettingSwitch(
            LinearLayout content,
            String title,
            boolean checked,
            String help,
            SettingChangeHandler handler) {
        Switch view = makeSwitch(title, checked);
        view.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!suppressSwitchCallback) {
                handler.onChanged(isChecked);
            }
        });
        content.addView(view, matchWidthWrapHeight());
        content.addView(makeHelpText(help), matchWidthWrapHeight());
        return view;
    }

    private Button makeSettingsButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setAllCaps(false);
        return button;
    }

    private interface SettingChangeHandler {
        void onChanged(boolean isChecked);
    }

    private void checkForUpdate() {
        toast("\u6b63\u5728\u68c0\u67e5\u66f4\u65b0");
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_JSON_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("HTTP " + responseCode);
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                } finally {
                    connection.disconnect();
                }
                JSONObject json = new JSONObject(body.toString());
                int remoteCode = json.getInt("versionCode");
                String remoteName = json.optString("versionName", String.valueOf(remoteCode));
                String apkUrl = json.getString("apkUrl");
                String notes = json.optString("notes", "\u6ca1\u6709\u586b\u5199\u66f4\u65b0\u8bf4\u660e");
                int localCode = getCurrentVersionCode();
                runOnUiThread(() -> {
                    if (remoteCode > localCode) {
                        showUpdateDialog(remoteName, notes, apkUrl);
                    } else {
                        toast("\u5df2\u7ecf\u662f\u6700\u65b0\u7248");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("\u6682\u65f6\u65e0\u6cd5\u68c0\u67e5\u66f4\u65b0")
                        .setMessage("\u5f53\u524d\u7248\u672c\u5df2\u5177\u5907 App \u5185\u66f4\u65b0\u80fd\u529b\uff0c\u4f46\u8fd8\u9700\u8981\u628a\u66f4\u65b0\u5730\u5740\u914d\u6210\u771f\u5b9e\u7684\u4e91\u7aef version.json\u3002\n\n\u5f53\u524d\u5730\u5740\uff1a" + UPDATE_JSON_URL)
                        .setPositiveButton("\u77e5\u9053\u4e86", null)
                        .show());
            }
        }, "UpdateCheck").start();
    }

    private int getCurrentVersionCode() throws PackageManager.NameNotFoundException {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (int) info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private void showUpdateDialog(String versionName, String notes, String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("\u53d1\u73b0\u65b0\u7248\u672c " + versionName)
                .setMessage(notes)
                .setNegativeButton("\u4ee5\u540e\u518d\u8bf4", null)
                .setPositiveButton("\u4e0b\u8f7d\u66f4\u65b0", (dialog, which) -> downloadUpdate(apkUrl))
                .show();
    }

    private void downloadUpdate(String apkUrl) {
        try {
            registerDownloadReceiverIfNeeded();
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("\u624b\u673a\u52a9\u542c\u5668\u66f4\u65b0");
            request.setDescription("\u6b63\u5728\u4e0b\u8f7d\u65b0\u7248\u672c");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    "hearing-aid-update.apk");
            updateDownloadId = manager.enqueue(request);
            toast("\u5df2\u5f00\u59cb\u4e0b\u8f7d\u66f4\u65b0");
        } catch (Exception e) {
            toast("\u4e0b\u8f7d\u66f4\u65b0\u5931\u8d25");
        }
    }

    private void registerDownloadReceiverIfNeeded() {
        if (downloadReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        downloadReceiverRegistered = true;
    }

    private void handleUpdateDownloadComplete(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                toast("\u66f4\u65b0\u5305\u4e0b\u8f7d\u5931\u8d25");
                return;
            }
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = cursor.getInt(statusIndex);
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                toast("\u66f4\u65b0\u5305\u4e0b\u8f7d\u5931\u8d25");
                return;
            }
        }
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) {
            toast("\u627e\u4e0d\u5230\u5df2\u4e0b\u8f7d\u7684\u66f4\u65b0\u5305");
            return;
        }
        installDownloadedApk(uri);
    }

    private void installDownloadedApk(Uri uri) {
        if (!canInstallUnknownApps()) {
            pendingInstallUri = uri;
            new AlertDialog.Builder(this)
                    .setTitle("\u9700\u8981\u5141\u8bb8\u5b89\u88c5\u66f4\u65b0")
                    .setMessage("\u7cfb\u7edf\u8981\u6c42\u5148\u5141\u8bb8\u672c App \u5b89\u88c5\u4e0b\u8f7d\u7684\u66f4\u65b0\u5305\u3002\u6253\u5f00\u540e\u8bf7\u6253\u5f00\u5141\u8bb8\uff0c\u518d\u8fd4\u56de App\u3002")
                    .setNegativeButton("\u53d6\u6d88", null)
                    .setPositiveButton("\u53bb\u5141\u8bb8", (dialog, which) -> openInstallPermissionSettings())
                    .show();
            return;
        }
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(uri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
        } catch (Exception e) {
            toast("\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u5b89\u88c5\u9875");
        }
    }

    private boolean canInstallUnknownApps() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void openInstallPermissionSettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void ensurePermissionsThenStart() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) {
            startListening();
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_AUDIO_PERMISSIONS);
        }
    }

    private void startListening() {
        HearingAidService.stop(this);
        updateRouteStatus();
        engine.start();
        setRunningUi(true);
    }

    private boolean isWiredAutoStartEnabled() {
        return wiredAutoStartSwitch != null
                ? wiredAutoStartSwitch.isChecked()
                : AppSettings.wiredAutoStartEnabled(this);
    }

    private boolean isSyncPhoneVolumeEnabled() {
        return syncPhoneVolumeSwitch != null
                ? syncPhoneVolumeSwitch.isChecked()
                : AppSettings.syncPhoneVolumeEnabled(this);
    }

    private boolean isVoiceGuideEnabled() {
        return voiceGuideSwitch != null
                ? voiceGuideSwitch.isChecked()
                : AppSettings.voiceGuideEnabled(this);
    }

    private void autoStartIfWiredAlreadyConnected() {
        if (isWiredAutoStartEnabled()
                && engine.hasWiredOutput() && !engine.isRunning()) {
            ensurePermissionsThenStart();
        }
    }

    private void handleWiredHeadsetState(int state) {
        if (state == 1) {
            if (isWiredAutoStartEnabled()
                    && !engine.isRunning()) {
                toast("\u5df2\u68c0\u6d4b\u5230\u6709\u7ebf\u8033\u673a\uff0c\u81ea\u52a8\u5f00\u542f\u52a9\u542c");
                ensurePermissionsThenStart();
            }
        } else if (state == 0) {
            stopBecauseOutputWasRemoved();
        }
    }

    private void stopBecauseOutputWasRemoved() {
        if (engine.isRunning()) {
            engine.stop();
            setRunningUi(false);
            levelMeter.setProgress(0);
            toast("\u8033\u673a\u5df2\u79fb\u9664\uff0c\u5df2\u505c\u6b62\u52a9\u542c");
        }
    }

    private void setRunningUi(boolean running) {
        toggleButton.setText(running ? "\u505c\u6b62\u52a9\u542c" : "\u5f00\u59cb\u52a9\u542c");
    }

    private void setGainProgressForValue(float gain) {
        int progress = Math.round((gain - 0.2f) * 10.0f);
        gainSeek.setProgress(Math.max(0, Math.min(progress, gainSeek.getMax())));
        syncSystemVolumeFromGain();
    }

    private void adjustGainBy(float delta) {
        float currentGain = 0.2f + gainSeek.getProgress() / 10.0f;
        setGainProgressForValue(currentGain + delta);
        speak(delta > 0 ? "\u97f3\u91cf\u589e\u5927" : "\u97f3\u91cf\u51cf\u5c0f");
    }

    private void syncSystemVolumeFromGain() {
        if (suppressVolumeSync || !isSyncPhoneVolumeEnabled()) {
            return;
        }
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float ratio = gainSeek.getProgress() / (float) Math.max(1, gainSeek.getMax());
        int targetVolume = Math.max(1, Math.round(ratio * maxVolume));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
        lastSeenSystemVolume = targetVolume;
    }

    private void syncGainFromSystemVolume() {
        if (!isSyncPhoneVolumeEnabled() || gainSeek == null) {
            return;
        }
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastSeenSystemVolume = currentVolume;
        int progress = Math.round((currentVolume / (float) Math.max(1, maxVolume)) * gainSeek.getMax());
        suppressVolumeSync = true;
        try {
            gainSeek.setProgress(Math.max(0, Math.min(progress, gainSeek.getMax())));
        } finally {
            suppressVolumeSync = false;
        }
    }

    private void syncGainFromSystemVolumeIfChanged() {
        if (!isSyncPhoneVolumeEnabled() || gainSeek == null) {
            return;
        }
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (currentVolume != lastSeenSystemVolume) {
            syncGainFromSystemVolume();
        }
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.0f);
        int margin = dp(6);
        params.setMargins(margin, 0, margin, 0);
        return params;
    }

    private void saveMode(String mode) {
        AppSettings.prefs(this).edit().putString(AppSettings.KEY_SCENE_MODE, mode).apply();
    }

    private void setVoiceEnhancementEnabled(boolean enabled) {
        voiceEnhancementEnabled = enabled;
        engine.setVoiceEnhancementEnabled(enabled);
        setSwitchChecked(voiceSwitch, enabled);
    }

    private void setFarPickupEnabled(boolean enabled) {
        farPickupEnabled = enabled;
        engine.setFarPickupEnabled(enabled);
        setSwitchChecked(farPickupSwitch, enabled);
    }

    private void setNoiseSuppressionEnabled(boolean enabled) {
        noiseSuppressionEnabled = enabled;
        engine.setNoiseSuppressionEnabled(enabled);
        setSwitchChecked(noiseSwitch, enabled);
    }

    private void setAutomaticGainEnabled(boolean enabled) {
        automaticGainEnabled = enabled;
        engine.setAutomaticGainEnabled(enabled);
        setSwitchChecked(agcSwitch, enabled);
    }

    private void applySafeMode() {
        saveMode(AppSettings.MODE_BLUETOOTH_DAILY);
        engine.setOutputLimit(0.62f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(2.5f);
        updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
        toast("\u5df2\u5207\u6362\u5230\u5b89\u5168\u6a21\u5f0f");
    }

    private void applyVoiceMode() {
        applyBluetoothDailyMode();
    }

    private void applyBluetoothDailyMode() {
        saveMode(AppSettings.MODE_BLUETOOTH_DAILY);
        engine.setOutputLimit(0.74f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(true);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(4.0f);
        updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
        speak("\u84dd\u7259\u65e5\u5e38\u6a21\u5f0f");
        toast("\u5df2\u5207\u6362\u5230\u84dd\u7259\u65e5\u5e38\u6a21\u5f0f");
    }

    private void applyWiredIndoorMode() {
        saveMode(AppSettings.MODE_WIRED_INDOOR);
        engine.setOutputLimit(0.78f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(5.0f);
        updateModeFeedback(AppSettings.MODE_WIRED_INDOOR);
        speak("\u6709\u7ebf\u5ba4\u5185\u6a21\u5f0f");
        toast("\u5df2\u5207\u6362\u5230\u6709\u7ebf\u5ba4\u5185\u6a21\u5f0f");
    }

    private void applyPocketMode() {
        saveMode(AppSettings.MODE_POCKET);
        engine.setOutputLimit(0.72f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(4.0f);
        updateModeFeedback(AppSettings.MODE_POCKET);
        speak("\u53e3\u888b\u6a21\u5f0f\u5df2\u5f00\u542f");
        toast("\u5df2\u5207\u6362\u5230\u53e3\u888b\u6a21\u5f0f");
    }

    private void applySevereMode() {
        saveMode(AppSettings.MODE_SEVERE);
        engine.setOutputLimit(0.86f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(true);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(true);
        setGainProgressForValue(7.0f);
        updateModeFeedback(AppSettings.MODE_SEVERE);
        speak("\u91cd\u5ea6\u6a21\u5f0f");
    }

    private void updateModeFeedback(String mode) {
        if (modeStatusText == null) {
            return;
        }
        bluetoothModeButton.setText("\u84dd\u7259\u65e5\u5e38");
        wiredModeButton.setText("\u6709\u7ebf\u5ba4\u5185");
        severeModeButton.setText("\u91cd\u5ea6");
        pocketModeButton.setText("\u53e3\u888b\u6a21\u5f0f");
        setSwitchChecked(severeModeSwitch, AppSettings.MODE_SEVERE.equals(mode));
        styleModeButton(bluetoothModeButton, false);
        styleModeButton(wiredModeButton, false);
        styleModeButton(severeModeButton, false);
        styleModeButton(pocketModeButton, false);
        if (AppSettings.MODE_WIRED_INDOOR.equals(mode)) {
            wiredModeButton.setText("\u2713 \u6709\u7ebf\u5ba4\u5185");
            styleModeButton(wiredModeButton, true);
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u6709\u7ebf\u5ba4\u5185");
        } else if (AppSettings.MODE_POCKET.equals(mode)) {
            pocketModeButton.setText("\u2713 \u53e3\u888b\u6a21\u5f0f");
            styleModeButton(pocketModeButton, true);
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u53e3\u888b\u6a21\u5f0f");
        } else if (AppSettings.MODE_SEVERE.equals(mode)) {
            severeModeButton.setText("\u2713 \u91cd\u5ea6");
            styleModeButton(severeModeButton, true);
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u91cd\u5ea6");
        } else {
            bluetoothModeButton.setText("\u2713 \u84dd\u7259\u65e5\u5e38");
            styleModeButton(bluetoothModeButton, true);
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u84dd\u7259\u65e5\u5e38");
        }
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setTextSize(selected ? 17 : 15);
        button.setTextColor(selected ? 0xFFFFFFFF : 0xFF10231F);
        button.setBackgroundColor(selected ? 0xFF1B6B55 : 0xFFE3ECE8);
    }

    private void speak(String text) {
        if (!isVoiceGuideEnabled() || textToSpeech == null) {
            return;
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hearing-aid-guide");
    }

    private void showHearingTestDialog() {
        String[] items = {
                "250 Hz \u4f4e\u97f3",
                "500 Hz \u4f4e\u4e2d\u97f3",
                "1000 Hz \u4eba\u58f0\u4e2d\u5fc3",
                "2000 Hz \u8bf4\u8bdd\u6e05\u6670\u5ea6",
                "4000 Hz \u8f85\u97f3\u548c\u6e05\u6670\u5ea6",
                "8000 Hz \u9ad8\u9891"
        };
        int[] freqs = {250, 500, 1000, 2000, 4000, 8000};
        new AlertDialog.Builder(this)
                .setTitle("\u7b80\u6613\u542c\u529b\u6d4b\u8bd5")
                .setMessage("\u8bf7\u5148\u628a\u97f3\u91cf\u8c03\u5c0f\u3002\u70b9\u4e00\u4e2a\u9891\u7387\u4f1a\u64ad\u653e 1 \u79d2\u6d4b\u8bd5\u97f3\uff0c\u8bb0\u4e0b\u54ea\u4e9b\u542c\u4e0d\u5230\u6216\u4e0d\u6e05\u695a\u3002")
                .setItems(items, (dialog, which) -> playTestTone(freqs[which]))
                .setPositiveButton("\u5173\u95ed", null)
                .show();
    }

    private void playTestTone(int frequency) {
        new Thread(() -> {
            int sampleRate = 44100;
            int samples = sampleRate;
            short[] data = new short[samples];
            double amplitude = 0.20 * Short.MAX_VALUE;
            for (int i = 0; i < samples; i++) {
                double ramp = Math.min(1.0, Math.min(i / 1200.0, (samples - i) / 1200.0));
                data[i] = (short) (Math.sin(2.0 * Math.PI * frequency * i / sampleRate)
                        * amplitude * ramp);
            }
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(data, 0, data.length);
            track.play();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            track.release();
        }, "HearingTestTone").start();
    }

    private void setSwitchChecked(Switch view, boolean checked) {
        if (view != null) {
            suppressSwitchCallback = true;
            try {
                view.setChecked(checked);
            } finally {
                suppressSwitchCallback = false;
            }
        }
    }

    private void registerRouteReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(routeReceiver, filter);
    }

    private void updateRouteStatus() {
        statusText.setText(engine.describeOutputRoute());
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthFixedHeight(int dp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(dp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
