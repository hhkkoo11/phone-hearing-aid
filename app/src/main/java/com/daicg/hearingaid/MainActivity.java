package com.daicg.hearingaid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements HearingEngine.Listener {
    private static final int REQUEST_AUDIO_PERMISSIONS = 1001;
    private static final int REQUEST_SETUP_PERMISSIONS = 1002;
    private static final String OFFICIAL_REPOSITORY_URL =
            "https://github.com/hhkkoo11/phone-hearing-aid";
    private static final String[] UPDATE_JSON_URLS = {
            "https://cdn.jsdelivr.net/gh/hhkkoo11/phone-hearing-aid@main/release/version.json",
            "https://raw.githubusercontent.com/hhkkoo11/phone-hearing-aid/main/release/version.json"
    };
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
    private Button pauseAutoButton;
    private SeekBar gainSeek;
    private Switch severeModeSwitch;
    private Switch pocketModeSwitch;
    private Switch wiredAutoStartSwitch;
    private Switch voiceSwitch;
    private Switch farPickupSwitch;
    private Switch echoSwitch;
    private Switch selfVoiceSwitch;
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
    private boolean echoCancellationEnabled;
    private boolean selfVoiceReductionEnabled;
    private boolean noiseSuppressionEnabled = true;
    private boolean automaticGainEnabled;
    private String activeAppliedMode;
    private String lastSpokenText = "";
    private long lastSpokenAtMillis;

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
                autoStartIfHeadsetAlreadyConnected();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stopBecauseOutputWasRemoved();
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                autoStartIfHeadsetAlreadyConnected();
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
                autoStartIfHeadsetAlreadyConnected();
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
                configureVoiceGuide();
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
        autoStartIfHeadsetAlreadyConnected();
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
        mainHandler.postDelayed(this::autoCheckPermissionHealthIfNeeded, 700);
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
            if (requestCode == REQUEST_SETUP_PERMISSIONS) {
                if (AppSettings.autoMonitorEnabled(this)) {
                    HeadsetMonitorService.start(this);
                }
                toast("\u6388\u6743\u5df2\u5904\u7406\uff0c\u540e\u53f0\u76d1\u6d4b\u5df2\u5c3d\u91cf\u5f00\u542f");
            }
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
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                adjustGainBy(-1.0f);
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

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.RIGHT);

        Button tutorialButton = new Button(this);
        tutorialButton.setText("\u6559\u7a0b");
        tutorialButton.setTextSize(15);
        tutorialButton.setAllCaps(false);
        tutorialButton.setOnClickListener(v -> showTutorialDialog());
        topActions.addView(tutorialButton, weightedButtonParams());

        Button settingsButton = new Button(this);
        settingsButton.setText("\u8bbe\u7f6e");
        settingsButton.setTextSize(15);
        settingsButton.setAllCaps(false);
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        topActions.addView(settingsButton, weightedButtonParams());
        root.addView(topActions, matchWidthFixedHeight(46));

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(0xFF315048);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(2), 0, dp(8));
        root.addView(statusText, matchWidthWrapHeight());

        TextView versionText = new TextView(this);
        versionText.setText("\u7248\u672c 2.8\uff1a\u66f4\u65b0\u4e0d\u5f3a\u5236");
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
                setAutoListenPaused(true, false);
                setRunningUi(false);
            } else {
                setAutoListenPaused(false, false);
                ensurePermissionsThenStart();
            }
        });
        root.addView(toggleButton, matchWidthFixedHeight(56));
        root.addView(makeHelpText("\u5f00\u59cb/\u505c\u6b62\uff1a\u628a\u624b\u673a\u6536\u5230\u7684\u58f0\u97f3\u9001\u5230\u8033\u673a\u91cc\u3002"), matchWidthWrapHeight());

        pauseAutoButton = new Button(this);
        pauseAutoButton.setTextSize(16);
        pauseAutoButton.setAllCaps(false);
        pauseAutoButton.setOnClickListener(v ->
                setAutoListenPaused(!AppSettings.autoListenPaused(this), true));
        root.addView(pauseAutoButton, matchWidthFixedHeight(48));
        root.addView(makeHelpText("\u542c\u6b4c/\u5237\u89c6\u9891\u65f6\u70b9\u6682\u505c\uff0c\u8033\u673a\u5c31\u5f53\u666e\u901a\u8033\u673a\u7528\uff1b\u9700\u8981\u52a9\u542c\u65f6\u518d\u6062\u590d\u3002"), matchWidthWrapHeight());

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
        modeLabel.setText("\u81ea\u52a8\u6a21\u5f0f");
        modeLabel.setTextSize(16);
        modeLabel.setTextColor(0xFF10231F);
        modeLabel.setPadding(0, dp(16), 0, dp(8));
        root.addView(modeLabel, matchWidthWrapHeight());
        root.addView(makeHelpText("\u81ea\u52a8\u8bc6\u522b\u8033\u673a\u7c7b\u578b\uff1a\u84dd\u7259\u8033\u673a\u7528\u65e5\u5e38\u4f18\u5316\uff0c\u6709\u7ebf/USB \u8033\u673a\u7528\u5ba4\u5185\u5bf9\u8bdd\u4f18\u5316\u3002"), matchWidthWrapHeight());

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

        TextView footerText = new TextView(this);
        footerText.setText("\u795d\u60a8\u4f7f\u7528\u987a\u5229\uff0c\u5982\u679c\u4f7f\u7528\u4e2d\u9047\u5230\u95ee\u9898\uff0c\u8bf7\u8054\u7cfb\u6211\u3002\u8f6f\u4ef6\u6c38\u4e45\u514d\u8d39\uff0c\u80fd\u5e2e\u52a9\u5230\u60a8\u662f\u6211\u7684\u8363\u5e78\u3002");
        footerText.setTextSize(14);
        footerText.setTextColor(0xFF5A6B66);
        footerText.setGravity(Gravity.CENTER);
        footerText.setPadding(0, dp(28), 0, dp(12));
        root.addView(footerText, matchWidthWrapHeight());

        scrollView.addView(root);
        setContentView(scrollView);
        applyAutoRouteMode(false);
        updatePauseButton();
        if (AppSettings.autoMonitorEnabled(this)) {
            HeadsetMonitorService.start(this);
        }
        mainHandler.postDelayed(this::showOneTimeSetupHintIfNeeded, 900);
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

    private TextView makeHelpText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFF66736F);
        view.setPadding(0, dp(4), 0, dp(6));
        return view;
    }

    private void showTutorialDialog() {
        int pad = dp(18);
        ScrollView scrollView = new ScrollView(this);
        TextView text = new TextView(this);
        text.setTextSize(16);
        text.setTextColor(0xFF10231F);
        text.setLineSpacing(dp(3), 1.05f);
        text.setPadding(pad, dp(6), pad, dp(10));
        text.setText(
                "\u4e00\u3001\u6700\u7b80\u5355\u7684\u7528\u6cd5\n"
                        + "1. \u5148\u8fde\u4e0a\u8033\u673a\u3002\u84dd\u7259\u548c\u6709\u7ebf\u90fd\u53ef\u4ee5\uff0cApp \u4f1a\u81ea\u52a8\u8bc6\u522b\u3002\n"
                        + "2. \u70b9\u201c\u5f00\u59cb\u52a9\u542c\u201d\u3002\n"
                        + "3. \u542c\u4e0d\u6e05\u5c31\u6309 +\uff0c\u89c9\u5f97\u523a\u8033\u5c31\u6309 -\u3002\n\n"
                        + "\u4e8c\u3001\u81ea\u52a8\u589e\u76ca\u662f\u4ec0\u4e48\n"
                        + "\u5b83\u5c31\u50cf\u201c\u81ea\u52a8\u8ffd\u97f3\u91cf\u201d\u3002\u522b\u4eba\u8bf4\u8bdd\u5c0f\uff0c\u5b83\u4f1a\u5e2e\u4f60\u62ac\u9ad8\uff1b\u58f0\u97f3\u592a\u5927\uff0c\u5b83\u4f1a\u5c3d\u91cf\u538b\u4f4f\u3002\n"
                        + "\u6700\u76f4\u89c2\u7684\u6548\u679c\uff1a\u5c0f\u58f0\u66f4\u5bb9\u6613\u542c\u89c1\u3002\n"
                        + "\u526f\u4f5c\u7528\uff1a\u7a7a\u8c03\u58f0\u3001\u98ce\u58f0\u3001\u8863\u670d\u6469\u64e6\u58f0\u4e5f\u53ef\u80fd\u88ab\u653e\u5927\u3002\u89c9\u5f97\u6742\u97f3\u591a\uff0c\u5c31\u5173\u6389\u5b83\u3002\n\n"
                        + "\u4e09\u3001\u91cd\u5ea6\u8033\u80cc\u600e\u4e48\u8c03\n"
                        + "\u5982\u679c\u542c\u529b\u4e0b\u964d\u6bd4\u8f83\u91cd\uff0c\u53ef\u4ee5\u6253\u5f00\u201c\u91cd\u5ea6\u8033\u80cc\u6a21\u5f0f\u201d\u3002\n"
                        + "\u7136\u540e\u4e00\u70b9\u70b9\u6309 +\uff0c\u4e0d\u8981\u4e00\u4e0b\u5b50\u62c9\u5230\u6700\u5927\u3002\n"
                        + "\u5982\u679c\u8fd8\u662f\u542c\u4e0d\u6e05\uff0c\u518d\u6253\u5f00\u201c\u8fdc\u8ddd\u79bb\u6536\u58f0\u201d\u3002\n\n"
                        + "\u56db\u3001\u5578\u53eb\u600e\u4e48\u529e\n"
                        + "\u5578\u53eb\u5c31\u662f\u8033\u673a\u91cc\u51fa\u73b0\u5c16\u53eb\u6216\u523a\u8033\u7684\u58f0\u97f3\u3002\n"
                        + "\u9047\u5230\u5578\u53eb\uff1a\u5148\u6309 - \u964d\u4f4e\u589e\u76ca\uff1b\u8fd8\u6709\u5578\u53eb\uff0c\u5173\u6389\u201c\u8fdc\u8ddd\u79bb\u6536\u58f0\u201d\uff1b\u518d\u4e0d\u884c\u5c31\u505c\u6b62\u52a9\u542c\u3002\n\n"
                        + "\u4e94\u3001\u53e3\u888b\u6a21\u5f0f\u4ec0\u4e48\u65f6\u5019\u7528\n"
                        + "\u624b\u673a\u653e\u53e3\u888b\u91cc\u65f6\u518d\u7528\u3002\u5b83\u4f1a\u51cf\u5c11\u8fdc\u8ddd\u79bb\u6536\u58f0\uff0c\u5c3d\u91cf\u538b\u4f4e\u8863\u670d\u6469\u64e6\u548c\u95f7\u58f0\u3002\n"
                        + "\u624b\u673a\u653e\u684c\u4e0a\u6216\u62ff\u5728\u624b\u91cc\u65f6\uff0c\u4e00\u822c\u4e0d\u9700\u8981\u5f00\u3002\n\n"
                        + "\u516d\u3001\u5b89\u5168\u63d0\u9192\n"
                        + "\u542c\u5230\u523a\u8033\u3001\u8033\u75db\u3001\u5934\u6655\uff0c\u7acb\u523b\u6309 - \u6216\u505c\u6b62\u4f7f\u7528\u3002\u8fd9\u4e2a App \u662f\u52a9\u542c\u8f85\u52a9\uff0c\u4e0d\u80fd\u4ee3\u66ff\u533b\u9662\u68c0\u67e5\u548c\u4e13\u4e1a\u52a9\u542c\u5668\u9a8c\u914d\u3002");
        scrollView.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("\u4f7f\u7528\u6559\u7a0b")
                .setView(scrollView)
                .setPositiveButton("\u77e5\u9053\u4e86", null)
                .show();
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
                        applyAutoRouteMode(true);
                    }
                });

        pocketModeSwitch = addSettingSwitch(
                content,
                "\u53e3\u888b\u6a21\u5f0f",
                AppSettings.MODE_POCKET.equals(AppSettings.sceneMode(this)),
                "\u624b\u673a\u653e\u53e3\u888b\u65f6\u7528\uff1a\u5173\u6389\u8fdc\u8ddd\u79bb\u6536\u58f0\uff0c\u964d\u4f4e\u4e00\u70b9\u8f93\u51fa\u4e0a\u9650\uff0c\u5c3d\u91cf\u51cf\u5c11\u8863\u670d\u6469\u64e6\u58f0\u548c\u95f7\u58f0\u3002",
                isChecked -> {
                    if (isChecked) {
                        applyPocketMode();
                    } else {
                        applyAutoRouteMode(true);
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

        echoSwitch = addSettingSwitch(
                content,
                "\u51cf\u5c11\u56de\u58f0",
                echoCancellationEnabled,
                "\u9ed8\u8ba4\u5173\u95ed\uff0c\u6536\u97f3\u66f4\u81ea\u7136\u3002\u5982\u679c\u542c\u5230\u660e\u663e\u56de\u58f0\u6216\u8033\u673a\u6f0f\u97f3\uff0c\u518d\u6253\u5f00\u5b83\u3002",
                this::setEchoCancellationEnabled);

        selfVoiceSwitch = addSettingSwitch(
                content,
                "\u964d\u4f4e\u81ea\u5df1\u58f0\u97f3",
                selfVoiceReductionEnabled,
                "\u84dd\u7259\u6a21\u5f0f\u9ed8\u8ba4\u6253\u5f00\uff1a\u538b\u4f4e\u4f7f\u7528\u8005\u81ea\u5df1\u8bf4\u8bdd\u7684\u6162\u56de\u58f0\uff0c\u522b\u4eba\u8d34\u8fd1\u624b\u673a\u8bf4\u8bdd\u4e5f\u53ef\u80fd\u4f1a\u5c0f\u4e00\u70b9\u3002",
                this::setSelfVoiceReductionEnabled);

        wiredAutoStartSwitch = addSettingSwitch(
                content,
                "\u8033\u673a\u8fde\u63a5\u81ea\u52a8\u5f00\u542f",
                isWiredAutoStartEnabled(),
                "\u68c0\u6d4b\u5230\u6709\u7ebf/USB \u6216\u84dd\u7259\u8033\u673a\u65f6\u81ea\u52a8\u5f00\u59cb\u52a9\u542c\uff1b\u6709\u7ebf\u4f18\u5148\uff0c\u6ca1\u6709\u6709\u7ebf\u624d\u7528\u84dd\u7259\u3002",
                isChecked -> {
                    AppSettings.prefs(this).edit()
                            .putBoolean(AppSettings.KEY_WIRED_AUTO_START, isChecked)
                            .apply();
                    if (isChecked) {
                        autoStartIfHeadsetAlreadyConnected();
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

        addSettingSwitch(
                content,
                "\u6682\u505c\u81ea\u52a8\u52a9\u542c",
                AppSettings.autoListenPaused(this),
                "\u542c\u6b4c\u3001\u5237\u89c6\u9891\u3001\u60f3\u5f53\u666e\u901a\u8033\u673a\u7528\u65f6\u6253\u5f00\u3002\u6062\u590d\u540e\uff0c\u68c0\u6d4b\u5230\u8033\u673a\u5c31\u4f1a\u81ea\u52a8\u52a9\u542c\u3002",
                isChecked -> setAutoListenPaused(isChecked, true));

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

        Button setupButton = makeSettingsButton("\u4e00\u6b21\u6027\u6388\u6743\u4e0e\u540e\u53f0\u8bbe\u7f6e");
        setupButton.setOnClickListener(v -> showBackgroundSetupDialog());
        content.addView(setupButton, matchWidthFixedHeight(48));
        content.addView(makeHelpText("\u91cd\u542f\u540e\u60f3\u81ea\u52a8\u76d1\u6d4b\u8033\u673a\uff0c\u8bf7\u5728\u8fd9\u91cc\u628a\u5fc5\u8981\u6743\u9650\u548c\u540e\u53f0\u8fd0\u884c\u8bbe\u597d\u3002"), matchWidthWrapHeight());

        Button permissionCheckButton = makeSettingsButton("\u68c0\u67e5\u6743\u9650\u72b6\u6001");
        permissionCheckButton.setOnClickListener(v -> showPermissionHealthDialog(false));
        content.addView(permissionCheckButton, matchWidthFixedHeight(48));
        content.addView(makeHelpText("\u68c0\u67e5\u9ea6\u514b\u98ce\u3001\u84dd\u7259\u3001\u901a\u77e5\u3001\u7701\u7535\u4e0d\u9650\u5236\u548c\u540e\u53f0\u76d1\u6d4b\u6709\u6ca1\u6709\u6253\u5f00\u3002"), matchWidthWrapHeight());

        Button aboutButton = makeSettingsButton("\u5173\u4e8e\u4e0e\u514d\u8d39\u58f0\u660e");
        aboutButton.setOnClickListener(v -> showAboutDialog());
        content.addView(aboutButton, matchWidthFixedHeight(48));
        content.addView(makeHelpText("\u663e\u793a\u5b98\u65b9\u4ed3\u5e93\u3001\u514d\u8d39\u58f0\u660e\u548c\u5b89\u88c5\u5305\u7b7e\u540d\uff0c\u9632\u6b62\u88ab\u5192\u5145\u6536\u8d39\u3002"), matchWidthWrapHeight());

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

    private void showOneTimeSetupHintIfNeeded() {
        if (AppSettings.prefs(this).getBoolean(AppSettings.KEY_SETUP_HINT_SHOWN, false)) {
            return;
        }
        AppSettings.prefs(this).edit()
                .putBoolean(AppSettings.KEY_SETUP_HINT_SHOWN, true)
                .apply();
        if (collectMissingRuntimePermissions().isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("\u5148\u505a\u4e00\u6b21\u6388\u6743")
                .setMessage("\u4e3a\u4e86\u91cd\u542f\u540e\u66f4\u5bb9\u6613\u81ea\u52a8\u76d1\u6d4b\u8033\u673a\uff0c\u9700\u8981\u5148\u5141\u8bb8\u9ea6\u514b\u98ce\u3001\u84dd\u7259\u548c\u901a\u77e5\u6743\u9650\u3002\n\n\u8fd9\u4e9b\u6743\u9650\u53ea\u7528\u4e8e\u672c\u5730\u6536\u97f3\u548c\u8033\u673a\u76d1\u6d4b\uff0c\u4e0d\u4e0a\u4f20\u58f0\u97f3\u3002")
                .setNegativeButton("\u4ee5\u540e\u518d\u8bf4", null)
                .setPositiveButton("\u53bb\u6388\u6743", (dialog, which) -> requestSetupPermissions())
                .show();
    }

    private void showBackgroundSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("\u6388\u6743\u4e0e\u540e\u53f0\u8bbe\u7f6e")
                .setMessage("\u80fd\u81ea\u52a8\u6253\u5f00\u7684\u6743\u9650\uff0cApp \u4f1a\u76f4\u63a5\u7533\u8bf7\u3002\n\n\u4f46\u201c\u81ea\u542f\u52a8\u201d\u548c\u201c\u7701\u7535\u4e0d\u9650\u5236\u201d\u662f\u624b\u673a\u7cfb\u7edf\u7ba1\u7684\uff0cApp \u4e0d\u80fd\u81ea\u5df1\u5077\u5077\u6253\u5f00\u3002\u5982\u679c\u91cd\u542f\u540e\u4e0d\u81ea\u52a8\u76d1\u6d4b\uff0c\u8bf7\u70b9\u4e0b\u9762\u6309\u94ae\uff0c\u5728\u7cfb\u7edf\u91cc\u5141\u8bb8\u672c App \u81ea\u542f\u52a8\u548c\u540e\u53f0\u8fd0\u884c\u3002")
                .setNegativeButton("\u5173\u95ed", null)
                .setNeutralButton("\u5148\u7533\u8bf7\u6743\u9650", (dialog, which) -> requestSetupPermissions())
                .setPositiveButton("\u6253\u5f00\u7cfb\u7edf\u8bbe\u7f6e", (dialog, which) -> openBackgroundSettings())
                .show();
    }

    private void autoCheckPermissionHealthIfNeeded() {
        if (engine == null || engine.isRunning()) {
            return;
        }
        List<String> issues = collectPermissionHealthIssues(true);
        if (issues.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastPromptAt = AppSettings.prefs(this)
                .getLong(AppSettings.KEY_LAST_PERMISSION_CHECK_PROMPT_AT, 0L);
        if (now - lastPromptAt < 6L * 60L * 60L * 1000L) {
            return;
        }
        AppSettings.prefs(this).edit()
                .putLong(AppSettings.KEY_LAST_PERMISSION_CHECK_PROMPT_AT, now)
                .apply();
        showPermissionHealthDialog(true);
    }

    private void showPermissionHealthDialog(boolean automatic) {
        List<String> issues = collectPermissionHealthIssues(false);
        if (issues.isEmpty()) {
            toast("\u6743\u9650\u72b6\u6001\u6b63\u5e38");
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("\u53d1\u73b0\u4ee5\u4e0b\u8bbe\u7f6e\u53ef\u80fd\u4f1a\u5f71\u54cd\u81ea\u52a8\u52a9\u542c\uff1a\n\n");
        for (String issue : issues) {
            message.append("\u2022 ").append(issue).append('\n');
        }
        message.append("\n\u70b9\u201c\u53bb\u5904\u7406\u201d\u540e\uff0cApp \u80fd\u7533\u8bf7\u7684\u6743\u9650\u4f1a\u76f4\u63a5\u7533\u8bf7\uff1b\u7cfb\u7edf\u4e0d\u5141\u8bb8\u81ea\u52a8\u6253\u5f00\u7684\u8bbe\u7f6e\uff0c\u4f1a\u5e26\u4f60\u53bb\u7cfb\u7edf\u9875\u9762\u624b\u52a8\u786e\u8ba4\u3002");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(automatic ? "\u6743\u9650\u4f53\u68c0\u63d0\u9192" : "\u6743\u9650\u72b6\u6001")
                .setMessage(message.toString())
                .setNegativeButton("\u4ee5\u540e\u518d\u8bf4", null)
                .setPositiveButton("\u53bb\u5904\u7406", (dialog, which) -> {
                    if (!collectMissingRuntimePermissions().isEmpty()) {
                        requestSetupPermissions();
                    } else {
                        openBackgroundSettings();
                    }
                });
        if (!automatic) {
            builder.setNeutralButton("\u6253\u5f00\u540e\u53f0\u8bbe\u7f6e", (dialog, which) -> openBackgroundSettings());
        }
        builder.show();
    }

    private List<String> collectPermissionHealthIssues(boolean automaticOnly) {
        List<String> issues = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("\u9ea6\u514b\u98ce\u6743\u9650\u6ca1\u5f00\uff1a\u6ca1\u6709\u5b83\u5c31\u4e0d\u80fd\u6536\u58f0\u3002");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("\u84dd\u7259\u6743\u9650\u6ca1\u5f00\uff1a\u53ef\u80fd\u68c0\u6d4b\u4e0d\u5230\u84dd\u7259\u8033\u673a\u3002");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("\u901a\u77e5\u6743\u9650\u6ca1\u5f00\uff1a\u540e\u53f0\u76d1\u6d4b\u53ef\u80fd\u4e0d\u7a33\u3002");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null
                    && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                issues.add("\u7701\u7535\u4f18\u5316\u8fd8\u5728\u9650\u5236\uff1a\u606f\u5c4f\u6216\u91cd\u542f\u540e\u53ef\u80fd\u88ab\u7cfb\u7edf\u5173\u6389\u3002");
            }
        }
        if (!AppSettings.autoMonitorEnabled(this)) {
            issues.add("\u540e\u53f0\u81ea\u52a8\u76d1\u6d4b\u8033\u673a\u5df2\u5173\u95ed\uff1a\u63d2\u4e0a\u8033\u673a\u4e0d\u4f1a\u81ea\u52a8\u7b49\u5f85\u5f00\u542f\u3002");
        }
        if (AppSettings.autoListenPaused(this)) {
            issues.add("\u81ea\u52a8\u52a9\u542c\u5904\u4e8e\u6682\u505c\u72b6\u6001\uff1a\u9002\u5408\u542c\u6b4c\u5237\u89c6\u9891\uff0c\u4f46\u4e0d\u4f1a\u81ea\u52a8\u5f00\u542f\u52a9\u542c\u3002");
        }
        if (!automaticOnly) {
            issues.add("\u8bf7\u786e\u8ba4\u7cfb\u7edf\u91cc\u5141\u8bb8\u201c\u81ea\u542f\u52a8\u201d\u6216\u201c\u540e\u53f0\u8fd0\u884c\u201d\uff1a\u8fd9\u4e2a\u5f00\u5173\u666e\u901a App \u4e0d\u80fd\u76f4\u63a5\u8bfb\u53d6\uff0c\u9700\u8981\u4eba\u624b\u52a8\u786e\u8ba4\u4e00\u6b21\u3002");
        }
        return issues;
    }

    private void showAboutDialog() {
        String sourceWarning = getPackageName().equals("com.daicg.hearingaid")
                ? "\u5305\u540d\uff1a\u5b98\u65b9\u5305\u540d"
                : "\u5305\u540d\uff1a\u975e\u5b98\u65b9\u5305\u540d\uff0c\u53ef\u80fd\u662f\u4e8c\u6b21\u5305\u88c5\u7248\u672c";
        new AlertDialog.Builder(this)
                .setTitle("\u5173\u4e8e\u4e0e\u514d\u8d39\u58f0\u660e")
                .setMessage("\u624b\u673a\u52a9\u542c\u5668\u662f\u516c\u76ca\u9879\u76ee\uff0c\u8f6f\u4ef6\u6c38\u4e45\u514d\u8d39\u3002\n\n"
                        + "\u7981\u6b62\u4efb\u4f55\u4eba\u628a\u672c App \u7528\u4e8e\u6536\u8d39\u9500\u552e\u3001\u4ed8\u8d39\u5b89\u88c5\u3001\u5e7f\u544a\u53d8\u73b0\u3001\u786c\u4ef6\u6346\u7ed1\u9500\u552e\u3001\u95e8\u5e97/\u516c\u53f8\u5546\u4e1a\u670d\u52a1\u3001\u95ed\u6e90\u4e8c\u6b21\u5305\u88c5\u6216\u5192\u5145\u539f\u521b\u3002\n\n"
                        + "\u5b98\u65b9\u4ed3\u5e93\uff1a\n" + OFFICIAL_REPOSITORY_URL + "\n\n"
                        + sourceWarning + "\n"
                        + "\u5f53\u524d\u7b7e\u540d\u6307\u7eb9\uff1a\n" + getSigningFingerprint())
                .setNegativeButton("\u5173\u95ed", null)
                .setPositiveButton("\u6253\u5f00\u5b98\u65b9\u4ed3\u5e93", (dialog, which) -> openOfficialRepository())
                .show();
    }

    private interface SettingChangeHandler {
        void onChanged(boolean isChecked);
    }

    private void checkForUpdate() {
        toast("\u6b63\u5728\u68c0\u67e5\u66f4\u65b0");
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(fetchUpdateJson());
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
                        .setMessage("\u624b\u673a\u73b0\u5728\u8fde\u4e0d\u4e0a\u66f4\u65b0\u670d\u52a1\u5668\uff0c\u53ef\u80fd\u662f\u7f51\u7edc\u6216 GitHub/CDN \u8bbf\u95ee\u4e0d\u7a33\u5b9a\u3002\n\n\u53ef\u4ee5\u6362\u4e00\u4e2a Wi-Fi \u540e\u518d\u8bd5\uff0c\u6216\u4ece\u5b98\u65b9 GitHub Release \u9875\u9762\u76f4\u63a5\u4e0b\u8f7d\u5b89\u88c5\u5305\u3002\n\n\u5b98\u65b9\u4ed3\u5e93\uff1a\n" + OFFICIAL_REPOSITORY_URL)
                        .setPositiveButton("\u77e5\u9053\u4e86", null)
                        .show());
            }
        }, "UpdateCheck").start();
    }

    private String fetchUpdateJson() throws Exception {
        Exception lastError = null;
        for (String updateUrl : UPDATE_JSON_URLS) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(updateUrl).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Cache-Control", "no-cache");
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
                }
                return body.toString();
            } catch (Exception e) {
                lastError = e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw lastError == null ? new IllegalStateException("Update check failed") : lastError;
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
                .setMessage(notes + "\n\n\u8fd9\u4e0d\u662f\u5f3a\u5236\u66f4\u65b0\uff0c\u4e0d\u66f4\u65b0\u4e5f\u53ef\u4ee5\u7ee7\u7eed\u4f7f\u7528\u5f53\u524d\u7248\u672c\u3002")
                .setNegativeButton("\u4ee5\u540e\u518d\u8bf4", null)
                .setPositiveButton("\u4e0b\u8f7d\u66f4\u65b0", (dialog, which) -> downloadUpdate(apkUrl))
                .setCancelable(true)
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
        List<String> permissions = collectMissingRuntimePermissions();
        if (permissions.isEmpty()) {
            startListening();
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_AUDIO_PERMISSIONS);
        }
    }

    private void requestSetupPermissions() {
        List<String> permissions = collectMissingRuntimePermissions();
        if (permissions.isEmpty()) {
            if (AppSettings.autoMonitorEnabled(this)) {
                HeadsetMonitorService.start(this);
            }
            toast("\u5fc5\u8981\u6743\u9650\u5df2\u7ecf\u5141\u8bb8");
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_SETUP_PERMISSIONS);
        }
    }

    private List<String> collectMissingRuntimePermissions() {
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
        return permissions;
    }

    private void openBackgroundSettings() {
        if (requestIgnoreBatteryOptimizationsIfNeeded()) {
            return;
        }
        Intent[] intents = {
                new Intent("miui.intent.action.OP_AUTO_START"),
                new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
        };
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        toast("\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u540e\u53f0\u8bbe\u7f6e");
    }

    private boolean requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return false;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openOfficialRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_REPOSITORY_URL)));
        } catch (Exception e) {
            toast("\u65e0\u6cd5\u6253\u5f00\u5b98\u65b9\u4ed3\u5e93");
        }
    }

    private String getSigningFingerprint() {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return "\u672a\u8bfb\u53d6\u5230";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(String.format(Locale.US, "%02X", value));
            }
            return builder.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            return "\u8bfb\u53d6\u5931\u8d25";
        }
    }

    private void startListening() {
        HearingAidService.stop(this);
        updateRouteStatus();
        applyAutoRouteMode(false);
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

    private void setAutoListenPaused(boolean paused, boolean announce) {
        AppSettings.prefs(this).edit()
                .putBoolean(AppSettings.KEY_AUTO_LISTEN_PAUSED, paused)
                .apply();
        if (paused) {
            if (engine.isRunning()) {
                engine.stop();
                setRunningUi(false);
            }
            HearingAidService.stop(this);
            if (announce) {
                toast("\u5df2\u6682\u505c\u81ea\u52a8\u52a9\u542c\uff0c\u73b0\u5728\u53ef\u4ee5\u5f53\u666e\u901a\u8033\u673a\u7528");
                speak("\u5df2\u6682\u505c\u81ea\u52a8\u52a9\u542c");
            }
        } else {
            if (AppSettings.autoMonitorEnabled(this)) {
                HeadsetMonitorService.start(this);
            }
            if (announce) {
                toast("\u5df2\u6062\u590d\u81ea\u52a8\u52a9\u542c");
                speak("\u5df2\u6062\u590d\u81ea\u52a8\u52a9\u542c");
            }
            autoStartIfHeadsetAlreadyConnected();
        }
        updatePauseButton();
        updateRouteStatus();
    }

    private void updatePauseButton() {
        if (pauseAutoButton == null) {
            return;
        }
        pauseAutoButton.setText(AppSettings.autoListenPaused(this)
                ? "\u6062\u590d\u81ea\u52a8\u52a9\u542c"
                : "\u6682\u505c\u81ea\u52a8\u52a9\u542c");
    }

    private void autoStartIfHeadsetAlreadyConnected() {
        if (!AppSettings.autoListenPaused(this)
                && isWiredAutoStartEnabled()
                && (engine.hasWiredOutput() || engine.hasBluetoothOutput())
                && !engine.isRunning()) {
            ensurePermissionsThenStart();
        }
    }

    private void handleWiredHeadsetState(int state) {
        if (state == 1) {
            if (!AppSettings.autoListenPaused(this)
                    && isWiredAutoStartEnabled()
                    && !engine.isRunning()) {
                toast("\u5df2\u68c0\u6d4b\u5230\u6709\u7ebf\u8033\u673a\uff0c\u81ea\u52a8\u5f00\u542f\u52a9\u542c");
                autoStartIfHeadsetAlreadyConnected();
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
        speakOncePerBurst(delta > 0 ? "\u589e\u5927" : "\u51cf\u5c0f");
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

    private void setEchoCancellationEnabled(boolean enabled) {
        echoCancellationEnabled = enabled;
        engine.setEchoCancellationEnabled(enabled);
        setSwitchChecked(echoSwitch, enabled);
    }

    private void setSelfVoiceReductionEnabled(boolean enabled) {
        selfVoiceReductionEnabled = enabled;
        engine.setSelfVoiceReductionEnabled(enabled);
        setSwitchChecked(selfVoiceSwitch, enabled);
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
        setEchoCancellationEnabled(false);
        setSelfVoiceReductionEnabled(true);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(2.5f);
        updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
        toast("\u5df2\u5207\u6362\u5230\u5b89\u5168\u6a21\u5f0f");
    }

    private void applyVoiceMode() {
        applyBluetoothDailyMode(true);
    }

    private void applyBluetoothDailyMode() {
        applyBluetoothDailyMode(true);
    }

    private void applyBluetoothDailyMode(boolean announce) {
        if (AppSettings.MODE_BLUETOOTH_DAILY.equals(activeAppliedMode)) {
            updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
            return;
        }
        activeAppliedMode = AppSettings.MODE_BLUETOOTH_DAILY;
        saveMode(AppSettings.MODE_BLUETOOTH_DAILY);
        engine.setOutputLimit(0.74f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(true);
        setEchoCancellationEnabled(false);
        setSelfVoiceReductionEnabled(true);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(4.0f);
        updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
        if (announce) {
            speak("\u84dd\u7259\u6a21\u5f0f");
            toast("\u5df2\u81ea\u52a8\u5207\u6362\u5230\u84dd\u7259\u6a21\u5f0f");
        }
    }

    private void applyWiredIndoorMode() {
        applyWiredIndoorMode(true);
    }

    private void applyWiredIndoorMode(boolean announce) {
        if (AppSettings.MODE_WIRED_INDOOR.equals(activeAppliedMode)) {
            updateModeFeedback(AppSettings.MODE_WIRED_INDOOR);
            return;
        }
        activeAppliedMode = AppSettings.MODE_WIRED_INDOOR;
        saveMode(AppSettings.MODE_WIRED_INDOOR);
        engine.setOutputLimit(0.78f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(false);
        setEchoCancellationEnabled(false);
        setSelfVoiceReductionEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(5.0f);
        updateModeFeedback(AppSettings.MODE_WIRED_INDOOR);
        if (announce) {
            speak("\u6709\u7ebf\u6a21\u5f0f");
            toast("\u5df2\u81ea\u52a8\u5207\u6362\u5230\u6709\u7ebf\u6a21\u5f0f");
        }
    }

    private void applyPocketMode() {
        activeAppliedMode = AppSettings.MODE_POCKET;
        saveMode(AppSettings.MODE_POCKET);
        engine.setOutputLimit(0.72f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(false);
        setEchoCancellationEnabled(false);
        setSelfVoiceReductionEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(false);
        setGainProgressForValue(4.0f);
        updateModeFeedback(AppSettings.MODE_POCKET);
        speak("\u53e3\u888b\u6a21\u5f0f\u5df2\u5f00\u542f");
        toast("\u5df2\u5207\u6362\u5230\u53e3\u888b\u6a21\u5f0f");
    }

    private void applySevereMode() {
        activeAppliedMode = AppSettings.MODE_SEVERE;
        saveMode(AppSettings.MODE_SEVERE);
        engine.setOutputLimit(0.86f);
        engine.setFeedbackProtectionEnabled(true);
        setVoiceEnhancementEnabled(true);
        setFarPickupEnabled(true);
        setEchoCancellationEnabled(false);
        setSelfVoiceReductionEnabled(false);
        setNoiseSuppressionEnabled(true);
        setAutomaticGainEnabled(true);
        setGainProgressForValue(7.0f);
        updateModeFeedback(AppSettings.MODE_SEVERE);
        speak("\u91cd\u5ea6\u6a21\u5f0f");
    }

    private void applyAutoRouteMode(boolean announce) {
        String currentMode = AppSettings.sceneMode(this);
        if (AppSettings.MODE_SEVERE.equals(currentMode) || AppSettings.MODE_POCKET.equals(currentMode)) {
            updateModeFeedback(currentMode);
            return;
        }
        if (engine.hasWiredOutput()) {
            applyWiredIndoorMode(announce);
        } else if (engine.hasBluetoothOutput()) {
            applyBluetoothDailyMode(announce);
        } else {
            updateModeFeedback(AppSettings.MODE_BLUETOOTH_DAILY);
        }
    }

    private void updateModeFeedback(String mode) {
        if (modeStatusText == null) {
            return;
        }
        setSwitchChecked(severeModeSwitch, AppSettings.MODE_SEVERE.equals(mode));
        setSwitchChecked(pocketModeSwitch, AppSettings.MODE_POCKET.equals(mode));
        if (AppSettings.autoListenPaused(this)) {
            modeStatusText.setText("\u5df2\u6682\u505c\u81ea\u52a8\u52a9\u542c\uff1a\u666e\u901a\u8033\u673a\u6a21\u5f0f");
        } else if (AppSettings.MODE_WIRED_INDOOR.equals(mode)) {
            modeStatusText.setText("\u81ea\u52a8\u6a21\u5f0f\uff1a\u6709\u7ebf/USB \u8033\u673a");
        } else if (AppSettings.MODE_POCKET.equals(mode)) {
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u53e3\u888b\u6a21\u5f0f");
        } else if (AppSettings.MODE_SEVERE.equals(mode)) {
            modeStatusText.setText("\u5f53\u524d\u6a21\u5f0f\uff1a\u91cd\u5ea6");
        } else if (!engine.hasBluetoothOutput() && !engine.hasWiredOutput()) {
            modeStatusText.setText("\u81ea\u52a8\u6a21\u5f0f\uff1a\u7b49\u5f85\u8033\u673a");
        } else {
            modeStatusText.setText("\u81ea\u52a8\u6a21\u5f0f\uff1a\u84dd\u7259\u8033\u673a");
        }
    }

    private void configureVoiceGuide() {
        textToSpeech.setLanguage(Locale.CHINA);
        textToSpeech.setSpeechRate(1.25f);
        textToSpeech.setPitch(0.72f);
        Voice preferredVoice = findPreferredChineseVoice();
        if (preferredVoice != null) {
            textToSpeech.setVoice(preferredVoice);
        }
    }

    private Voice findPreferredChineseVoice() {
        Set<Voice> voices = textToSpeech.getVoices();
        if (voices == null) {
            return null;
        }
        Voice fallback = null;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            Locale locale = voice.getLocale();
            if (locale == null || !"zh".equals(locale.getLanguage())) {
                continue;
            }
            if (voice.isNetworkConnectionRequired()) {
                continue;
            }
            if (fallback == null) {
                fallback = voice;
            }
            int score = scoreVoice(voice);
            if (score > bestScore) {
                bestScore = score;
                best = voice;
            }
        }
        return best != null ? best : fallback;
    }

    private int scoreVoice(Voice voice) {
        String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.US);
        Locale locale = voice.getLocale();
        int score = 0;
        if (Locale.CHINA.getCountry().equals(locale.getCountry())) {
            score += 20;
        }
        if (name.contains("xiao") || name.contains("miui") || name.contains("xiaomi")) {
            score += 40;
        }
        if (name.contains("female") || name.contains("woman") || name.contains("girl")) {
            score += 15;
        }
        if (name.contains("mandarin") || name.contains("putonghua") || name.contains("zh-cn")) {
            score += 10;
        }
        if (voice.getQuality() >= Voice.QUALITY_HIGH) {
            score += 8;
        }
        if (voice.getLatency() <= Voice.LATENCY_LOW) {
            score += 4;
        }
        return score;
    }

    private void speak(String text) {
        if (!isVoiceGuideEnabled() || textToSpeech == null) {
            return;
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hearing-aid-guide");
    }

    private void speakOncePerBurst(String text) {
        long now = System.currentTimeMillis();
        if (text.equals(lastSpokenText) && now - lastSpokenAtMillis < 1500L) {
            return;
        }
        lastSpokenText = text;
        lastSpokenAtMillis = now;
        speak(text);
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
        applyAutoRouteMode(false);
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
