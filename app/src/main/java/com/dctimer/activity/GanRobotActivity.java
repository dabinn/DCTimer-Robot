package com.dctimer.activity;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.model.SmartCubeTraining;
import com.dctimer.util.GanRobotAutoConnector;
import com.dctimer.util.GanRobotBleClient;
import com.dctimer.util.GanRobotController;
import com.dctimer.util.GanRobotCodec;
import com.dctimer.util.GanRobotExecutor;
import com.dctimer.util.GanRobotProtocol;
import com.dctimer.util.GanRobotSessionState;
import com.dctimer.util.Utils;
import com.dctimer.widget.CustomToolbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.ref.WeakReference;

import cs.min2phase.CubieCube;
import cs.min2phase.Tools;
import cs.min2phase.Util;

public class GanRobotActivity extends AppCompatActivity {
    private static final String TAG = "GanRobotActivity";
    private static final int REQUEST_ENABLE_BLUETOOTH = 31;
    private static final int REQUEST_BLE_PERMISSION = 32;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_DISCONNECTING = 3;
    private static final long SCAN_TIMEOUT_MS = 10000L;
    private static final long DISCONNECT_TIMEOUT_MS = 4000L;
    private static final long AUTO_CONNECT_COOLDOWN_MS = 15000L;
    private static final long AUTO_SCAN_TIMEOUT_MS = 7000L;
    private static final String PREF_NAME = "dctimer";
    private static final String PREF_GAN_ROBOT_AUTO_CONNECT = "ganrobot_auto_connect";
    private static final int ROBOT_IDLE_ZERO_STREAK_EXECUTE = 5;
    private static final int ROBOT_IDLE_ZERO_STREAK_PROBE = 2;
    private static final long ROBOT_IDLE_TIMEOUT_MS_EXECUTE = 20000L;
    private static final long ROBOT_IDLE_TIMEOUT_MS_PROBE = 5000L;
    private static final long SMART_CUBE_PROBE_TIMEOUT_MS = 2500L;
    private static final long SMART_CUBE_STATE_POLL_MS = 5L;
    private static final String ORIENTATION_PROBE_ROLLBACK = "F' D'";
    public static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    public static final String EXTRA_PREFILL_SCRAMBLE = "extra_prefill_scramble";
    public static final String EXTRA_PREFILL_SCRAMBLE_DISPLAY = "extra_prefill_scramble_display";

    public static class OrientationPlan {
        public final String currentStateAfterProbe;
        public final Map<Character, Character> logicalToPhysicalFaceMap;

        OrientationPlan(String currentStateAfterProbe, Map<Character, Character> logicalToPhysicalFaceMap) {
            this.currentStateAfterProbe = currentStateAfterProbe;
            this.logicalToPhysicalFaceMap = logicalToPhysicalFaceMap;
        }
    }

    private static class ScrambleResolutionResult {
        final String standardScramble;
        final boolean useMainTargetState;

        ScrambleResolutionResult(String standardScramble, boolean useMainTargetState) {
            this.standardScramble = standardScramble;
            this.useMainTargetState = useMainTargetState;
        }
    }

    public static class RobotSolvePlan {
        final String algorithmLogical;
        final String strategyLabel;
        final int evaluatedCandidates;
        final long searchTimeMs;

        RobotSolvePlan(String algorithmLogical, String strategyLabel, int evaluatedCandidates, long searchTimeMs) {
            this.algorithmLogical = algorithmLogical;
            this.strategyLabel = strategyLabel;
            this.evaluatedCandidates = evaluatedCandidates;
            this.searchTimeMs = searchTimeMs;
        }
    }

    public static class RobotExecutionResult {
        final boolean success;
        final long executionTimeMs;

        RobotExecutionResult(boolean success, long executionTimeMs) {
            this.success = success;
            this.executionTimeMs = executionTimeMs;
        }
    }

    public static class SolveCandidate {
        final String algorithm;
        final int cost;
        final int length;
        final int evaluatedCandidates;
        final String profileName;

        SolveCandidate(String algorithm, int cost, int length, int evaluatedCandidates, String profileName) {
            this.algorithm = algorithm;
            this.cost = cost;
            this.length = length;
            this.evaluatedCandidates = evaluatedCandidates;
            this.profileName = profileName;
        }
    }

    private abstract static class BaseRobotGattCallback extends BluetoothGattCallback {
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            GanRobotBleClient.onCharacteristicWrite(characteristic, status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            GanRobotBleClient.onCharacteristicRead(characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic != null) {
                onCharacteristicChanged(gatt, characteristic, characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (characteristic == null || characteristic.getUuid() == null) {
                return;
            }
            if (GanRobotProtocol.CHARACTER_UUID_BUTTON.equals(characteristic.getUuid())) {
                GanRobotController.handleRobotButtonEvent(value);
            }
        }
    }

    private interface RobotGattEventHandler {
        void onDiscoverServicesFailed();

        void onDiscoverServicesException(SecurityException e);

        void onDisconnected();

        void onUnsupportedDevice();

        void onConnected();
    }

    private static class RobotGattEventCallback extends BaseRobotGattCallback {
        private final RobotGattEventHandler eventHandler;

        RobotGattEventCallback(RobotGattEventHandler eventHandler) {
            this.eventHandler = eventHandler;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    eventHandler.onDiscoverServicesException(e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                eventHandler.onDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                eventHandler.onDiscoverServicesFailed();
                return;
            }
            if (!GanRobotBleClient.attach(gatt)) {
                eventHandler.onUnsupportedDevice();
                return;
            }
            eventHandler.onConnected();
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> scannedAddresses = new HashSet<>();
    private final List<BluetoothDevice> scannedDevices = new ArrayList<>();
    private final List<String> scannedDeviceNames = new ArrayList<>();
    private static volatile int sharedConnectionState = STATE_DISCONNECTED;
    private static volatile long sharedLastAutoConnectAttemptElapsedMs;
    private static final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<GanRobotActivity> activeActivityRef = new WeakReference<>(null);
    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    private LinearLayout rootLayout;
    private TextView tvConnectionState;
    private TextView tvRobotStatus;
    private EditText etScramble;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnSend;
    private Button btnClear;
    private Button btnSolve;
    private CheckBox cbAutoConnect;
    private Spinner spinnerButtonAction;
    private ProgressBar progressConnecting;
    private int uiMode;
    private static boolean sharedIsSending;
    private static String sharedLatestRemainingStatusLine;
    private int pendingRobotButtonAction = GanRobotController.ACTION_NONE;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private AlertDialog scanDialog;
    private ProgressBar scanProgress;
    private ArrayAdapter<String> scanAdapter;
    private String prefillRawScramble = "";
    private String prefillDisplayScramble = "";
    private final Runnable forceDisconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (getConnectionState() == STATE_DISCONNECTING) {
                closeGatt();
                setConnectionState(STATE_DISCONNECTED);
                appendStatus(getString(R.string.gan_robot_status_disconnected));
            }
        }
    };

    public static GanRobotActivity getActiveActivity() {
        return activeActivityRef.get();
    }

    public static boolean isConnectedAndReady() {
        return sharedConnectionState == STATE_CONNECTED
                && GanRobotBleClient.isReady();
    }

    public void requestRobotButtonAction(int action) {
        if (action == GanRobotController.ACTION_NONE) {
            return;
        }
        pendingRobotButtonAction = action;
        performPendingRobotButtonActionIfPossible();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        setContentView(R.layout.activity_gan_robot);
        bindViews();
        setupToolbar();
        applyThemeColors();
        updateConnectionUi();
        if (getConnectionState() == STATE_CONNECTED) {
            appendStatus(getString(R.string.gan_robot_connected));
        } else {
            appendStatus(getString(R.string.gan_robot_disconnected));
        }
        pendingRobotButtonAction = GanRobotController.ACTION_NONE;
        String prefillScramble = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_PREFILL_SCRAMBLE);
        prefillRawScramble = TextUtils.isEmpty(prefillScramble) ? "" : prefillScramble.trim();
        String prefillDisplayScramble = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_PREFILL_SCRAMBLE_DISPLAY);
        String initialScramble = !TextUtils.isEmpty(prefillDisplayScramble)
                ? prefillDisplayScramble
                : convertStandardScrambleToDisplay(prefillScramble);
        this.prefillDisplayScramble = TextUtils.isEmpty(initialScramble) ? "" : initialScramble.trim();
        if (!TextUtils.isEmpty(initialScramble)) {
            etScramble.setText(initialScramble.trim());
            etScramble.setSelection(etScramble.length());
        }
        uiMode = getResources().getConfiguration().uiMode;
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        performPendingRobotButtonActionIfPossible();
    }

    private void setupWindow() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.layout);
        tvConnectionState = findViewById(R.id.tv_connection_state);
        tvRobotStatus = findViewById(R.id.tv_robot_status);
        etScramble = findViewById(R.id.et_scramble);
        btnConnect = findViewById(R.id.btn_connect_robot);
        btnDisconnect = findViewById(R.id.btn_disconnect_robot);
        btnSend = findViewById(R.id.btn_send_scramble);
        btnClear = findViewById(R.id.btn_clear_scramble);
        btnSolve = findViewById(R.id.btn_solve_cube);
        cbAutoConnect = findViewById(R.id.cb_auto_connect_robot);
        progressConnecting = findViewById(R.id.progress_connecting);
        if (cbAutoConnect != null) {
            cbAutoConnect.setChecked(isAutoConnectEnabled(this));
            cbAutoConnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                saveAutoConnectEnabled(isChecked);
                if (isChecked) {
                    maybeAutoConnect(this);
                }
            });
        }

        spinnerButtonAction = findViewById(R.id.spinner_button_action);
        if (spinnerButtonAction != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{
                            getString(R.string.gan_robot_button_action_solve),
                            getString(R.string.gan_robot_button_action_scramble),
                            getString(R.string.gan_robot_button_action_none)
                    });
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerButtonAction.setAdapter(adapter);
            int savedAction = GanRobotController.getRobotButtonAction();
            int savedSelection = 0;
            if (savedAction == GanRobotController.ACTION_SCRAMBLE) {
                savedSelection = 1;
            } else if (savedAction == GanRobotController.ACTION_NONE) {
                savedSelection = 2;
            }
            spinnerButtonAction.setSelection(savedSelection);
            spinnerButtonAction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int action;
                    if (position == 0) {
                        action = GanRobotController.ACTION_SOLVE;
                    } else if (position == 1) {
                        action = GanRobotController.ACTION_SCRAMBLE;
                    } else {
                        action = GanRobotController.ACTION_NONE;
                    }
                    saveRobotButtonAction(action);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                beginConnectFlow();
            }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disableAutoConnectForManualDisconnect();
                disconnectRobot();
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitScramble();
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (etScramble != null) {
                    etScramble.setText("");
                }
            }
        });
        btnSolve.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GanRobotExecutor.solveFromSmartCubeState();
            }
        });
        etScramble.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                updateConnectionUi();
            }
        });
    }

    private void saveAutoConnectEnabled(boolean enabled) {
        SharedPreferences sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        sharedPreferences.edit().putBoolean(PREF_GAN_ROBOT_AUTO_CONNECT, enabled).apply();
    }

    private void disableAutoConnectForManualDisconnect() {
        saveAutoConnectEnabled(false);
        if (cbAutoConnect != null && cbAutoConnect.isChecked()) {
            cbAutoConnect.setChecked(false);
        }
    }

    private static boolean isAutoConnectEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences sharedPreferences = context.getApplicationContext().getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        return sharedPreferences.getBoolean(PREF_GAN_ROBOT_AUTO_CONNECT, false);
    }

    private void saveRobotButtonAction(int action) {
        SharedPreferences sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        sharedPreferences.edit().putInt(GanRobotController.PREF_KEY_BUTTON_ACTION, action).apply();
        GanRobotController.setRobotButtonAction(action);
    }

    private void setupToolbar() {
        CustomToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.action_gan_robot);
        setSupportActionBar(toolbar);
        toolbar.setBackgroundColor(APP.getBackgroundColor());
        toolbar.setItemColor(APP.getTextColor());
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
    }

    private void applyThemeColors() {
        rootLayout.setBackgroundColor(APP.getBackgroundColor());
        int gray = Utils.grayScale(APP.getBackgroundColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int visibility = gray > 200 ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && gray > 200) {
                visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(visibility);
            getWindow().setStatusBarColor(APP.getBackgroundColor());
            getWindow().setNavigationBarColor(APP.getBackgroundColor());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(gray > 200 ? 0x44000000 : APP.getBackgroundColor());
            getWindow().setNavigationBarColor(APP.getBackgroundColor());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode;
            if ((uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
            recreate();
        }
    }

    private void beginConnectFlow() {
        int state = getConnectionState();
        if (state == STATE_CONNECTING || state == STATE_CONNECTED || state == STATE_DISCONNECTING) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        String[] permissions = getBlePermissions();
        if (permissions.length > 0 && !hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_BLE_PERMISSION);
            return;
        }
        if (!isLocationEnabled()) {
            Toast.makeText(this, R.string.ble_location_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        openScanDialog();
    }

    private void openScanDialog() {
        if (scanDialog != null && scanDialog.isShowing()) {
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_gan_robot_devices, null);
        ListView listView = view.findViewById(R.id.lv_devices);
        scanProgress = view.findViewById(R.id.progress);
        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, scannedDeviceNames);
        listView.setAdapter(scanAdapter);
        listView.setOnItemClickListener((adapterView, itemView, i, l) -> {
            if (i < 0 || i >= scannedDevices.size()) {
                return;
            }
            BluetoothDevice device = scannedDevices.get(i);
            if (scanDialog != null) {
                scanDialog.dismiss();
            }
            connectRobot(device);
        });

        scanDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.gan_robot_scan_title)
                .setView(view)
                .setNegativeButton(R.string.btn_cancel, null)
                .setOnDismissListener(dialogInterface -> stopScan())
                .show();
        startScan();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void startScan() {
        scannedAddresses.clear();
        scannedDevices.clear();
        scannedDeviceNames.clear();
        if (scanAdapter != null) {
            scanAdapter.notifyDataSetChanged();
        }
        if (scanProgress != null) {
            scanProgress.setVisibility(View.VISIBLE);
        }
        appendStatus(getString(R.string.gan_robot_scanning));
        mainHandler.removeCallbacks(stopScanRunnable);
        mainHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bluetoothLeScanner != null) {
                bluetoothLeScanner.startScan(scanCallback);
                return;
            }
        }
        bluetoothAdapter.startLeScan(leScanCallback);
    }

    private void stopScan() {
        mainHandler.removeCallbacks(stopScanRunnable);
        if (scanProgress != null) {
            scanProgress.setVisibility(View.GONE);
        }
        if (bluetoothAdapter == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bluetoothLeScanner != null) {
                bluetoothLeScanner.stopScan(scanCallback);
                bluetoothLeScanner = null;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                bluetoothAdapter.stopLeScan(leScanCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Stop scan failed", e);
        }
        if (scannedDevices.isEmpty()) {
            appendStatus(getString(R.string.gan_robot_no_device));
        }
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            onDeviceScanned(device, null);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onDeviceScanned(result == null ? null : result.getDevice(), result == null ? null : result.getScanRecord());
        }
    };

    private void onDeviceScanned(final BluetoothDevice device, final ScanRecord scanRecord) {
        if (device == null) {
            return;
        }
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            return;
        }
        if (!GanRobotProtocol.isCandidate(name, scanRecord)) {
            return;
        }
        String address = device.getAddress();
        if (TextUtils.isEmpty(address) || !scannedAddresses.add(address)) {
            return;
        }
        scannedDevices.add(device);
        scannedDeviceNames.add(String.format(Locale.US, "%s (%s)", name, address));
        runOnUiThread(() -> {
            if (scanAdapter != null) {
                scanAdapter.notifyDataSetChanged();
            }
        });
    }

    private void connectRobot(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        closeGatt();
        setConnectionState(STATE_CONNECTING);
        appendStatus(getString(R.string.gan_robot_connecting));
        try {
            GanRobotBleClient.connect(this, device, gattCallback);
        } catch (SecurityException e) {
            setConnectionState(STATE_DISCONNECTED);
            appendStatus(getString(R.string.connect_fail));
            Log.e(TAG, "connectGatt failed", e);
            Toast.makeText(this, R.string.connect_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectRobot() {
        if (!GanRobotBleClient.hasGatt()) {
            mainHandler.removeCallbacks(forceDisconnectRunnable);
            setConnectionState(STATE_DISCONNECTED);
            return;
        }
        mainHandler.removeCallbacks(forceDisconnectRunnable);
        setConnectionState(STATE_DISCONNECTING);
        appendStatus(getString(R.string.gan_robot_disconnecting));
        try {
            GanRobotBleClient.disconnect();
            mainHandler.postDelayed(forceDisconnectRunnable, DISCONNECT_TIMEOUT_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "disconnect failed", e);
            closeGatt();
            setConnectionState(STATE_DISCONNECTED);
        }
    }

    private static void closeGatt() {
        GanRobotBleClient.close();
    }

    private final BluetoothGattCallback gattCallback = new RobotGattEventCallback(new RobotGattEventHandler() {
        @Override
        public void onDiscoverServicesFailed() {
            runOnUiThread(() -> {
                appendStatus(getString(R.string.connect_fail));
                disconnectRobot();
            });
        }

        @Override
        public void onDiscoverServicesException(SecurityException e) {
            Log.e(TAG, "discoverServices failed", e);
            runOnUiThread(() -> {
                appendStatus(getString(R.string.connect_fail));
                disconnectRobot();
            });
        }

        @Override
        public void onDisconnected() {
            runOnUiThread(() -> {
                mainHandler.removeCallbacks(forceDisconnectRunnable);
                closeGatt();
                setConnectionState(STATE_DISCONNECTED);
                appendStatus(getString(R.string.gan_robot_status_disconnected));
            });
        }

        @Override
        public void onUnsupportedDevice() {
            runOnUiThread(() -> {
                appendStatus(getString(R.string.ble_device_not_supported));
                disconnectRobot();
            });
        }

        @Override
        public void onConnected() {
            runOnUiThread(() -> {
                mainHandler.removeCallbacks(forceDisconnectRunnable);
                setConnectionState(STATE_CONNECTED);
                appendStatus(getString(R.string.gan_robot_connected));
            });
        }
    });

    private void submitScramble() {
        if (getConnectionState() != STATE_CONNECTED || !GanRobotBleClient.isReady()) {
            Toast.makeText(this, R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        if (sharedIsSending) {
            Toast.makeText(this, R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        final String displayScramble = etScramble.getText() == null ? "" : etScramble.getText().toString();
        final String orientationLabel = getActiveScrambleOrientationLabel();
        ScrambleResolutionResult scrambleResolution = resolveStandardScrambleForSubmit(displayScramble);
        final String scramble = scrambleResolution.standardScramble;
        final boolean useMainTargetState = scrambleResolution.useMainTargetState;
        GanRobotSessionState.setLatestMainScramble(scramble);
        GanRobotSessionState.setUseMainTargetState(useMainTargetState);
        runOnUiThread(() -> appendStatus("Scramble orientation: " + orientationLabel));

        GanRobotExecutor.executeScramble(scramble, useMainTargetState);
    }

    private void performRobotButtonAction(int action) {
        if (action == GanRobotController.ACTION_SOLVE) {
            GanRobotExecutor.solveFromSmartCubeState();
            return;
        }
        if (action == GanRobotController.ACTION_SCRAMBLE) {
            submitScramble();
        }
    }

    private void performPendingRobotButtonActionIfPossible() {
        if (pendingRobotButtonAction == GanRobotController.ACTION_NONE) {
            return;
        }
        if (getConnectionState() != STATE_CONNECTED || sharedIsSending || GanRobotSessionState.isRobotMoving()
                || !GanRobotBleClient.isReady()) {
            return;
        }
        int action = pendingRobotButtonAction;
        pendingRobotButtonAction = GanRobotController.ACTION_NONE;
        performRobotButtonAction(action);
    }

    public static String waitForRobotSmartCubeStateSnapshot(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            String cubeState = GanRobotSessionState.getLatestSmartCubeState();
            if (!TextUtils.isEmpty(cubeState)) {
                return cubeState;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return GanRobotSessionState.getLatestSmartCubeState();
    }

    public static String prependProbeRollback(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return ORIENTATION_PROBE_ROLLBACK;
        }
        return ORIENTATION_PROBE_ROLLBACK + " " + algorithm.trim();
    }

    public static boolean isSmartCubeModeActive() {
        return APP.enterTime == 3;
    }

    public static void executeStateToStatePlan(String currentCubeState, String targetFacelet, String planLabel) throws Exception {
        long totalStartMs = SystemClock.elapsedRealtime();
        long probeStartMs = totalStartMs;
        OrientationPlan orientationPlan = runOrientationProbePlan(currentCubeState);
        long probeTimeMs = SystemClock.elapsedRealtime() - probeStartMs;

        long pathStartMs = SystemClock.elapsedRealtime();
        RobotSolvePlan solvePlan = buildStateToStateAlgorithm(orientationPlan.currentStateAfterProbe, targetFacelet);
        long pathTimeMs = SystemClock.elapsedRealtime() - pathStartMs;

        String algorithm = remapAlgorithmWithFaceMap(solvePlan.algorithmLogical, orientationPlan.logicalToPhysicalFaceMap);
        int logicalMoveCount = countAlgorithmMoves(algorithm);
        int robotMoveCount = TextUtils.isEmpty(algorithm) ? 0 : GanRobotCodec.estimateRobotCost(algorithm);
        postOnMainThread(() -> appendStatusSafely("Orientation probe done (D/F)"));
        postOnMainThread(() -> appendStatusSafely("Solve strategy: " + solvePlan.strategyLabel + " (" + solvePlan.evaluatedCandidates + " candidates/" + solvePlan.searchTimeMs + "ms)"));
        postOnMainThread(() -> appendStatusSafely("Robot convert: " + logicalMoveCount + " -> " + robotMoveCount + " moves"));
        postOnMainThread(() -> appendStatusSafely(planLabel + ": " + algorithm));
        RobotExecutionResult executionResult = executeAlgorithm(algorithm);
        if (executionResult.success) {
            long totalTimeMs = SystemClock.elapsedRealtime() - totalStartMs;
            postOnMainThread(() -> appendStatusSafely("Timing(ms) probe=" + probeTimeMs
                    + ", path=" + pathTimeMs
                    + ", move=" + executionResult.executionTimeMs
                    + ", total=" + totalTimeMs));
        }
    }

    public static RobotExecutionResult executeAlgorithm(String algorithm) {
        if (TextUtils.isEmpty(algorithm) || TextUtils.isEmpty(algorithm.trim())) {
            postOnMainThread(() -> {
                appendStatusSafely(robotContext().getString(R.string.gan_robot_send_success));
                Toast.makeText(robotContext(), R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(true, 0L);
        }
        List<byte[]> packets;
        try {
            packets = GanRobotCodec.encodeScramble(algorithm);
        } catch (IllegalArgumentException e) {
            postOnMainThread(() -> {
                appendStatusSafely(robotContext().getString(R.string.gan_robot_invalid_scramble, e.getMessage()));
                Toast.makeText(robotContext(), R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(false, 0L);
        }
        if (packets.isEmpty()) {
            postOnMainThread(() -> Toast.makeText(robotContext(), R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show());
            return new RobotExecutionResult(false, 0L);
        }
        long executeStartMs = SystemClock.elapsedRealtime();
        setSending(true);
        sharedLatestRemainingStatusLine = null;
        postOnMainThread(() -> appendStatusSafely(robotContext().getString(R.string.gan_robot_waiting_execution, packets.size())));
        try {
            for (int i = 0; i < packets.size(); i++) {
                ensureGattConnected();
                writeMovePacket(packets.get(i));
                waitRobotIdle();
                final int chunk = i + 1;
                postOnMainThread(() -> appendStatusSafely("Chunk " + chunk + "/" + packets.size() + " done"));
            }
            postOnMainThread(() -> {
                appendStatusSafely(robotContext().getString(R.string.gan_robot_send_success));
                Toast.makeText(robotContext(), R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(true, SystemClock.elapsedRealtime() - executeStartMs);
        } catch (Exception e) {
            Log.e(TAG, "execute scramble failed", e);
            postOnMainThread(() -> {
                appendStatusSafely(robotContext().getString(R.string.gan_robot_send_failed, e.getMessage()));
                Toast.makeText(robotContext(), R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(false, SystemClock.elapsedRealtime() - executeStartMs);
        } finally {
            setSending(false);
            sharedLatestRemainingStatusLine = null;
        }
    }

    private static RobotSolvePlan buildStateToStateAlgorithm(String startFacelet, String targetFacelet) {
        String start = normalizeFacelet(startFacelet);
        String target = normalizeFacelet(targetFacelet);
        if (TextUtils.equals(start, target)) {
            return new RobotSolvePlan("", "already-at-target", 0, 0);
        }
        String scrambleFacelet = Tools.getScrambleFacelet(start, target);
        if (scrambleFacelet == null) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_send_failed_short));
        }
        RobotSolvePlan solvePlan = buildRobotOptimizedStateSolution(scrambleFacelet);
        String algorithm = solvePlan.algorithmLogical;
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_send_failed_short));
        }
        if (algorithm.startsWith("Error")) {
            throw new IllegalStateException(algorithm);
        }
        return new RobotSolvePlan(algorithm.trim(), solvePlan.strategyLabel, solvePlan.evaluatedCandidates, solvePlan.searchTimeMs);
    }

    private static RobotSolvePlan buildRobotOptimizedStateSolution(String scrambleFacelet) {
        long searchStartMs = SystemClock.elapsedRealtime();
        final long totalTimeBudgetMs = 460L;
        ExecutorService solverPool = Executors.newFixedThreadPool(4);
        SolveCandidate bestCandidate = null;
        int evaluatedCandidates = 0;
        try {
            List<Callable<SolveCandidate>> tasks = new ArrayList<>();
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "fast",
                    26,
                    15000L,
                    0L,
                    2,
                    4,
                    180L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "balance",
                    28,
                    26000L,
                    50L,
                    2,
                    5,
                    220L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "deepA",
                    30,
                    45000L,
                    100L,
                    2,
                    6,
                    260L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "deepB",
                    30,
                    50000L,
                    100L,
                    2,
                    8,
                    300L
            ));
            List<Future<SolveCandidate>> futures = solverPool.invokeAll(tasks, totalTimeBudgetMs, TimeUnit.MILLISECONDS);
            for (Future<SolveCandidate> future : futures) {
                if (future == null || !future.isDone() || future.isCancelled()) {
                    continue;
                }
                SolveCandidate candidate = future.get();
                if (candidate == null || TextUtils.isEmpty(candidate.algorithm) || candidate.algorithm.startsWith("Error")) {
                    continue;
                }
                evaluatedCandidates += candidate.evaluatedCandidates;
                if (bestCandidate == null
                        || candidate.cost < bestCandidate.cost
                        || (candidate.cost == bestCandidate.cost && candidate.length < bestCandidate.length)) {
                    bestCandidate = candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parallel fallback search failed, fallback to single profile", e);
        } finally {
            solverPool.shutdownNow();
        }

        long searchTimeMs = SystemClock.elapsedRealtime() - searchStartMs;
        if (bestCandidate != null) {
            return new RobotSolvePlan(
                    bestCandidate.algorithm,
                    "fallback-parallel-" + bestCandidate.profileName,
                    Math.max(evaluatedCandidates, bestCandidate.evaluatedCandidates),
                    searchTimeMs
            );
        }

        SolveCandidate single = runFallbackSearchProfile(
                scrambleFacelet,
                "single",
                30,
                50000L,
                100L,
                2,
                12,
                350L
        );
        searchTimeMs = SystemClock.elapsedRealtime() - searchStartMs;
        return new RobotSolvePlan(single.algorithm, "fallback-cost-optimized", single.evaluatedCandidates, searchTimeMs);
    }

    private static SolveCandidate runFallbackSearchProfile(
            String scrambleFacelet,
            String profileName,
            int maxDepth,
            long probeMax,
            long probeMin,
            int verbose,
            int maxCandidateChecks,
            long maxSearchTimeMs
    ) {
        long startMs = SystemClock.elapsedRealtime();
        cs.min2phase.Search search = new cs.min2phase.Search();
        String best = search.solution(scrambleFacelet, maxDepth, probeMax, probeMin, verbose);
        int evaluated = 1;
        if (TextUtils.isEmpty(best) || best.startsWith("Error")) {
            return new SolveCandidate(best, Integer.MAX_VALUE, Integer.MAX_VALUE, evaluated, profileName);
        }
        best = best.trim();
        int bestCost = GanRobotCodec.estimateRobotCost(best);
        int bestLength = countAlgorithmMoves(best);
        for (int i = 0; i < maxCandidateChecks; i++) {
            if (SystemClock.elapsedRealtime() - startMs >= maxSearchTimeMs) {
                break;
            }
            String candidate = search.next(probeMax, probeMin, verbose);
            if (candidate == null || candidate.startsWith("Error")) {
                break;
            }
            evaluated++;
            candidate = candidate.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            int candidateCost = GanRobotCodec.estimateRobotCost(candidate);
            int candidateLength = countAlgorithmMoves(candidate);
            if (candidateCost < bestCost || (candidateCost == bestCost && candidateLength < bestLength)) {
                best = candidate;
                bestCost = candidateCost;
                bestLength = candidateLength;
            }
        }
        return new SolveCandidate(best, bestCost, bestLength, evaluated, profileName);
    }

    private static int countAlgorithmMoves(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return 0;
        }
        String trimmed = algorithm.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private ScrambleResolutionResult resolveStandardScrambleForSubmit(String displayScramble) {
        String normalizedInputDisplay = normalizeScrambleString(displayScramble);
        String normalizedPrefillDisplay = normalizeScrambleString(prefillDisplayScramble);
        if (!TextUtils.isEmpty(prefillRawScramble)
                && !TextUtils.isEmpty(normalizedInputDisplay)
                && !TextUtils.isEmpty(normalizedPrefillDisplay)
                && TextUtils.equals(normalizedInputDisplay, normalizedPrefillDisplay)) {
            return new ScrambleResolutionResult(prefillRawScramble, true);
        }
        return new ScrambleResolutionResult(convertDisplayScrambleToStandard(displayScramble), false);
    }

    public static String resolveTargetStateForSubmit() {
        String targetState = normalizeFacelet(GanRobotSessionState.getLatestMainTargetState());
        if (TextUtils.isEmpty(targetState)) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_send_failed_short));
        }
        return targetState;
    }

    private String normalizeScrambleString(String scramble) {
        if (TextUtils.isEmpty(scramble)) {
            return "";
        }
        return scramble
                .replace('\u2019', '\'')
                .replace('\uFF07', '\'')
                .replace('\n', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.US);
    }

    private static String normalizeFacelet(String facelet) {
        if (TextUtils.isEmpty(facelet)) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_solve_need_cube_state));
        }
        String normalized = facelet.trim();
        if (normalized.length() != 54) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        if (!normalized.matches("^[URFDLB]{54}$")) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        return normalized;
    }

    public static OrientationPlan runOrientationProbePlan(String currentCubeState) throws Exception {
        String stateBeforeProbe = normalizeFacelet(currentCubeState);

        writeProbeMove("D");
        String stateAfterD = waitForSmartCubeStateChange(stateBeforeProbe, SMART_CUBE_PROBE_TIMEOUT_MS, "D");
        char logicalFaceForPhysicalD = detectAppliedFaceClockwise(stateBeforeProbe, stateAfterD);
        if (logicalFaceForPhysicalD == 0) {
            throw new IllegalStateException("Cannot infer orientation from D probe");
        }

        writeProbeMove("F");
        String stateAfterF = waitForSmartCubeStateChange(stateAfterD, SMART_CUBE_PROBE_TIMEOUT_MS, "F");
        char logicalFaceForPhysicalF = detectAppliedFaceClockwise(stateAfterD, stateAfterF);
        if (logicalFaceForPhysicalF == 0) {
            throw new IllegalStateException("Cannot infer orientation from F probe");
        }

        Map<Character, Character> logicalToPhysical = buildLogicalToPhysicalFaceMap(logicalFaceForPhysicalD, logicalFaceForPhysicalF);
        return new OrientationPlan(stateAfterF, logicalToPhysical);
    }

    private static void writeProbeMove(String move) throws Exception {
        List<byte[]> packets = GanRobotCodec.encodeScramble(move);
        if (packets.isEmpty()) {
            throw new IllegalStateException("Probe move is empty");
        }
        for (byte[] packet : packets) {
            ensureGattConnected();
            writeMovePacket(packet);
            waitRobotIdleForProbe();
        }
    }

    private static String waitForSmartCubeStateChange(String previousState, long timeoutMs, String probeName) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String prev = normalizeFacelet(previousState);
        while (SystemClock.elapsedRealtime() < deadline) {
            String now = GanRobotSessionState.getLatestSmartCubeState();
            if (!TextUtils.isEmpty(now)) {
                String normalizedNow = normalizeFacelet(now);
                if (!TextUtils.equals(prev, normalizedNow)) {
                    return normalizedNow;
                }
            }
            Thread.sleep(SMART_CUBE_STATE_POLL_MS);
        }
        throw new IllegalStateException("Smart cube probe timeout on " + probeName);
    }

    private static char detectAppliedFaceClockwise(String beforeState, String afterState) {
        char[] faces = new char[] {'U', 'R', 'F', 'D', 'L', 'B'};
        for (char face : faces) {
            String transformed = applyFaceClockwise(beforeState, face);
            if (TextUtils.equals(transformed, afterState)) {
                return face;
            }
        }
        return 0;
    }

    private static String applyFaceClockwise(String facelet, char face) {
        CubieCube cube = new CubieCube();
        if (Util.toCubieCube(facelet, cube) != 0) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        int moveIndex = toClockwiseMoveIndex(face);
        CubieCube moved = cube.move(moveIndex);
        return Util.toFaceCube(moved);
    }

    private static int toClockwiseMoveIndex(char face) {
        switch (face) {
            case 'U':
                return 0;
            case 'R':
                return 3;
            case 'F':
                return 6;
            case 'D':
                return 9;
            case 'L':
                return 12;
            case 'B':
                return 15;
            default:
                throw new IllegalArgumentException("Unsupported face: " + face);
        }
    }

    private static Map<Character, Character> buildLogicalToPhysicalFaceMap(char logicalForPhysicalD, char logicalForPhysicalF) {
        int[] physicalUp = negate(faceToVector(logicalForPhysicalD));
        int[] physicalFront = faceToVector(logicalForPhysicalF);
        if (dot(physicalUp, physicalFront) != 0) {
            throw new IllegalStateException("Invalid orientation probe result");
        }
        // Right-hand rule: right = up x front
        int[] physicalRight = cross(physicalUp, physicalFront);
        if (norm1(physicalRight) != 1) {
            throw new IllegalStateException("Invalid orientation probe basis");
        }

        Map<Character, Character> physicalToLogical = new HashMap<>();
        physicalToLogical.put('U', vectorToFace(physicalUp));
        physicalToLogical.put('D', vectorToFace(negate(physicalUp)));
        physicalToLogical.put('F', vectorToFace(physicalFront));
        physicalToLogical.put('B', vectorToFace(negate(physicalFront)));
        physicalToLogical.put('R', vectorToFace(physicalRight));
        physicalToLogical.put('L', vectorToFace(negate(physicalRight)));

        Map<Character, Character> logicalToPhysical = new HashMap<>();
        for (Map.Entry<Character, Character> entry : physicalToLogical.entrySet()) {
            logicalToPhysical.put(entry.getValue(), entry.getKey());
        }
        return logicalToPhysical;
    }

    public static String remapAlgorithmWithFaceMap(String algorithm, Map<Character, Character> logicalToPhysicalFaceMap) {
        if (TextUtils.isEmpty(algorithm) || logicalToPhysicalFaceMap == null || logicalToPhysicalFaceMap.isEmpty()) {
            return algorithm;
        }
        String[] tokens = algorithm.trim().split("\\s+");
        StringBuilder builder = new StringBuilder(algorithm.length() + 8);
        for (String token : tokens) {
            if (TextUtils.isEmpty(token)) {
                continue;
            }
            char logicalFace = token.charAt(0);
            Character physicalFace = logicalToPhysicalFaceMap.get(logicalFace);
            if (physicalFace == null) {
                throw new IllegalStateException("Orientation remap missing face: " + logicalFace);
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(physicalFace);
            if (token.length() > 1) {
                builder.append(token.substring(1));
            }
        }
        return builder.toString();
    }

    private static int[] faceToVector(char face) {
        switch (face) {
            case 'U':
                return new int[] {0, 1, 0};
            case 'D':
                return new int[] {0, -1, 0};
            case 'F':
                return new int[] {0, 0, 1};
            case 'B':
                return new int[] {0, 0, -1};
            case 'R':
                return new int[] {1, 0, 0};
            case 'L':
                return new int[] {-1, 0, 0};
            default:
                throw new IllegalArgumentException("Unknown face " + face);
        }
    }

    private static char vectorToFace(int[] vector) {
        if (vector[0] == 0 && vector[1] == 1 && vector[2] == 0) return 'U';
        if (vector[0] == 0 && vector[1] == -1 && vector[2] == 0) return 'D';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == 1) return 'F';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == -1) return 'B';
        if (vector[0] == 1 && vector[1] == 0 && vector[2] == 0) return 'R';
        if (vector[0] == -1 && vector[1] == 0 && vector[2] == 0) return 'L';
        throw new IllegalArgumentException("Invalid vector");
    }

    private static int[] negate(int[] v) {
        return new int[] {-v[0], -v[1], -v[2]};
    }

    private static int dot(int[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static int[] cross(int[] a, int[] b) {
        return new int[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static int norm1(int[] v) {
        return Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]);
    }

    private static void writeMovePacket(byte[] packet) throws Exception {
        GanRobotBleClient.writeMovePacket(robotContext(), packet);
        postOnMainThread(() -> appendStatusSafely("TX fff3: " + toHex(packet)));
    }

    private static int waitRobotIdle() throws Exception {
        return waitRobotIdleInternal(ROBOT_IDLE_TIMEOUT_MS_EXECUTE, ROBOT_IDLE_ZERO_STREAK_EXECUTE, true);
    }

    private static int waitRobotIdleForProbe() throws Exception {
        return waitRobotIdleInternal(ROBOT_IDLE_TIMEOUT_MS_PROBE, ROBOT_IDLE_ZERO_STREAK_PROBE, false);
    }

    private static int waitRobotIdleInternal(long timeoutMs, int zeroStreakTarget, boolean logStatus) throws Exception {
        boolean seenNonZero = false;
        int zeroStreak = 0;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int lastValue = 0;
        int lastLoggedValue = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            GanRobotBleClient.StatusSample sample = GanRobotBleClient.readMovesRemaining(robotContext());
            lastValue = sample.movesRemaining;
            if (logStatus && lastValue != lastLoggedValue) {
                final int currentValue = lastValue;
                postOnMainThread(() -> {
                    GanRobotActivity act = activeActivityRef == null ? null : activeActivityRef.get();
                    if (act != null) act.upsertRemainingStatus(currentValue);
                });
                lastLoggedValue = lastValue;
            }
            if (lastValue > 0) {
                seenNonZero = true;
                zeroStreak = 0;
            } else {
                zeroStreak++;
                if (seenNonZero || zeroStreak >= zeroStreakTarget) {
                    return lastValue;
                }
            }
        }
        throw new IllegalStateException(robotContext().getString(R.string.gan_robot_status_timeout));
    }

    private static void ensureGattConnected() {
        if (sharedConnectionState != STATE_CONNECTED || !GanRobotBleClient.isReady()) {
            throw new IllegalStateException(robotContext().getString(R.string.gan_robot_wait_connect));
        }
    }

    private static void setSending(final boolean sending) {
        sharedIsSending = sending;
        notifyConnectionUiChanged();
    }

    public static boolean isSending() {
        return sharedIsSending;
    }

    private void setConnectionState(int state) {
        sharedConnectionState = state;
        updateConnectionUi();
    }

    private int getConnectionState() {
        return sharedConnectionState;
    }

    private void updateConnectionUi() {
        if (tvConnectionState == null) {
            return;
        }
        int stateText;
        int state = getConnectionState();
        switch (state) {
            case STATE_CONNECTING:
                stateText = R.string.gan_robot_connecting;
                break;
            case STATE_CONNECTED:
                stateText = R.string.gan_robot_connected;
                break;
            case STATE_DISCONNECTING:
                stateText = R.string.gan_robot_disconnecting;
                break;
            default:
                stateText = R.string.gan_robot_disconnected;
                break;
        }
        tvConnectionState.setText(stateText);
        boolean connected = state == STATE_CONNECTED;
        boolean connecting = state == STATE_CONNECTING || state == STATE_DISCONNECTING;
        btnConnect.setVisibility(connected ? View.GONE : View.VISIBLE);
        btnDisconnect.setVisibility(connected ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!connecting && !sharedIsSending);
        btnDisconnect.setEnabled(connected && !sharedIsSending);
        boolean hasInput = etScramble != null
                && etScramble.getText() != null
                && !TextUtils.isEmpty(etScramble.getText().toString().trim());
        btnSend.setEnabled(!sharedIsSending && hasInput);
        if (btnClear != null) {
            btnClear.setEnabled(!sharedIsSending);
        }
        if (btnSolve != null) {
            btnSolve.setEnabled(!sharedIsSending && connected);
        }
        if (cbAutoConnect != null) {
            cbAutoConnect.setEnabled(!connecting);
        }
        progressConnecting.setVisibility(connecting ? View.VISIBLE : View.GONE);
        performPendingRobotButtonActionIfPossible();
    }

    private void appendStatus(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        String current = tvRobotStatus.getText() == null ? "" : tvRobotStatus.getText().toString();
        String next = current.isEmpty()
                ? message
                : current + "\n" + message;
        tvRobotStatus.setText(next);
    }

    private void upsertRemainingStatus(int remaining) {
        String nextLine = "RX fff2: remaining=" + remaining;
        String current = tvRobotStatus.getText() == null ? "" : tvRobotStatus.getText().toString();
        if (!TextUtils.isEmpty(sharedLatestRemainingStatusLine) && !TextUtils.isEmpty(current)) {
            String[] lines = current.split("\\n");
            StringBuilder rebuilt = new StringBuilder(current.length());
            for (String line : lines) {
                if (TextUtils.equals(line, sharedLatestRemainingStatusLine)) {
                    continue;
                }
                if (rebuilt.length() > 0) {
                    rebuilt.append('\n');
                }
                rebuilt.append(line);
            }
            current = rebuilt.toString();
        }
        sharedLatestRemainingStatusLine = nextLine;
        String next = TextUtils.isEmpty(current) ? nextLine : current + "\n" + nextLine;
        tvRobotStatus.setText(next);
    }

    private boolean isTrainingScrambleMode() {
        return SmartCubeTraining.isSmart333Training(APP.scrambleIdx);
    }

    private int getActiveScrambleOrientation() {
        return isTrainingScrambleMode() ? APP.smartCubeTrainingOrientation : APP.smartCubeSolveOrientation;
    }

    private String getActiveScrambleOrientationLabel() {
        int orientation = getActiveScrambleOrientation();
        String[] faces = getResources().getStringArray(R.array.opt_smart_cube_faces);
        int[] pair = Utils.getSmartCubeOrientationPair(orientation);
        return getString(R.string.smart_cube_orientation_format, faces[pair[0]], faces[pair[1]]);
    }

    private String convertDisplayScrambleToStandard(String displayScramble) {
        return convertScrambleOrientation(displayScramble, false);
    }

    private String convertStandardScrambleToDisplay(String standardScramble) {
        return convertScrambleOrientation(standardScramble, true);
    }

    private String convertScrambleOrientation(String scramble, boolean standardToDisplay) {
        if (TextUtils.isEmpty(scramble)) {
            return scramble;
        }
        int orientation = getActiveScrambleOrientation();
        if (orientation == 0) {
            return scramble;
        }
        String[] moves = scramble.replace('\n', ' ').trim().split("\\s+");
        StringBuilder converted = new StringBuilder(scramble.length() + 8);
        for (String move : moves) {
            if (TextUtils.isEmpty(move)) {
                continue;
            }
            int moveIndex = parseScrambleMoveIndex(move);
            if (moveIndex < 0) {
                appendScrambleToken(converted, move);
                continue;
            }
            int mappedMove = standardToDisplay
                    ? Utils.orientSmartCubeMove(moveIndex, orientation)
                    : Utils.unorientSmartCubeMove(moveIndex, orientation);
            String mappedToken = formatScrambleMoveIndex(mappedMove);
            appendScrambleToken(converted, TextUtils.isEmpty(mappedToken) ? move : mappedToken);
        }
        return converted.toString();
    }

    private void appendScrambleToken(StringBuilder builder, String token) {
        if (TextUtils.isEmpty(token)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(token);
    }

    private int parseScrambleMoveIndex(String move) {
        if (TextUtils.isEmpty(move) || move.length() < 1) {
            return -1;
        }
        int axis;
        switch (move.charAt(0)) {
            case 'U': axis = 0; break;
            case 'R': axis = 3; break;
            case 'F': axis = 6; break;
            case 'D': axis = 9; break;
            case 'L': axis = 12; break;
            case 'B': axis = 15; break;
            default: return -1;
        }
        if (move.length() >= 2) {
            char suffix = move.charAt(1);
            if (suffix == '2') {
                axis += 1;
            } else if (suffix == '\'') {
                axis += 2;
            }
        }
        return axis;
    }

    private String formatScrambleMoveIndex(int moveIdx) {
        if (moveIdx < 0 || moveIdx >= 18) {
            return "";
        }
        char face = "URFDLB".charAt(moveIdx / 3);
        int power = moveIdx % 3;
        if (power == 1) {
            return face + "2";
        }
        if (power == 2) {
            return face + "'";
        }
        return String.valueOf(face);
    }

    private static String toHex(byte[] value) {
        if (value == null || value.length == 0) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", value[i] & 0xff));
        }
        return builder.toString();
    }

    private String[] getBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        }
        return new String[0];
    }

    private boolean hasPermissions(String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public static void maybeAutoConnect(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (shouldSkipAutoConnect(appContext)) {
            return;
        }
        BluetoothAdapter adapter = getEnabledBluetoothAdapter(appContext);
        if (adapter == null) {
            return;
        }
        markAutoConnectAttempt();
        if (GanRobotAutoConnector.connectBondedDeviceIfAvailable(appContext, adapter,
                GanRobotActivity::connectRobotSilently)) {
            return;
        }
        GanRobotAutoConnector.startScan(appContext, adapter, AUTO_SCAN_TIMEOUT_MS, autoConnectHandler,
                GanRobotActivity::connectRobotSilently);
    }

    private static boolean shouldSkipAutoConnect(Context context) {
        return !isAutoConnectEnabled(context)
                || sharedConnectionState != STATE_DISCONNECTED
                || GanRobotBleClient.hasGatt()
                || isAutoConnectCooldownActive()
                || !hasAutoConnectPermissions(context)
                || !isLocationEnabled(context);
    }

    private static boolean isAutoConnectCooldownActive() {
        long now = SystemClock.elapsedRealtime();
        return now - sharedLastAutoConnectAttemptElapsedMs < AUTO_CONNECT_COOLDOWN_MS;
    }

    private static void markAutoConnectAttempt() {
        sharedLastAutoConnectAttemptElapsedMs = SystemClock.elapsedRealtime();
    }

    private static BluetoothAdapter getEnabledBluetoothAdapter(Context context) {
        if (context == null) {
            return null;
        }
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return null;
        }
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        return adapter != null && adapter.isEnabled() ? adapter : null;
    }

    private static void connectRobotSilently(Context context, BluetoothDevice device) {
        if (context == null || device == null) {
            return;
        }
        closeGatt();
        sharedConnectionState = STATE_CONNECTING;
        notifyConnectionUiChanged();
        try {
            GanRobotBleClient.connect(context, device, autoGattCallback);
        } catch (SecurityException e) {
            closeGatt();
            sharedConnectionState = STATE_DISCONNECTED;
            notifyConnectionUiChanged();
        }
    }

    private static boolean hasAutoConnectPermissions(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 31) {
            permissions = new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            permissions = new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        }
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLocationEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private static void notifyConnectionUiChanged() {
        GanRobotActivity activity = activeActivityRef.get();
        if (activity != null) {
            activity.runOnUiThread(activity::updateConnectionUi);
        }
    }

    private static void resetAutoConnection() {
        closeGatt();
        sharedConnectionState = STATE_DISCONNECTED;
        notifyConnectionUiChanged();
    }

    public static void postOnMainThread(Runnable r) {
        autoConnectHandler.post(r);
    }

    public static Context robotContext() {
        GanRobotActivity act = activeActivityRef == null ? null : activeActivityRef.get();
        return act != null ? act : APP.getInstance();
    }

    public static void appendStatusSafely(String msg) {
        GanRobotActivity act = activeActivityRef == null ? null : activeActivityRef.get();
        if (act != null) {
            act.appendStatus(msg);
        }
    }

    private static void showAutoConnectSuccessToast() {
        GanRobotActivity activity = activeActivityRef.get();
        if (activity != null) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, R.string.gan_robot_connected, Toast.LENGTH_SHORT).show()
            );
        } else {
            Context context = APP.getInstance();
            if (context != null) {
                autoConnectHandler.post(() ->
                        Toast.makeText(context, R.string.gan_robot_connected, Toast.LENGTH_SHORT).show()
                );
            }
        }
    }

    private static final BluetoothGattCallback autoGattCallback = new RobotGattEventCallback(new RobotGattEventHandler() {
        @Override
        public void onDiscoverServicesFailed() {
            resetAutoConnection();
        }

        @Override
        public void onDiscoverServicesException(SecurityException e) {
            resetAutoConnection();
        }

        @Override
        public void onDisconnected() {
            resetAutoConnection();
        }

        @Override
        public void onUnsupportedDevice() {
            resetAutoConnection();
        }

        @Override
        public void onConnected() {
            sharedConnectionState = STATE_CONNECTED;
            notifyConnectionUiChanged();
            showAutoConnectSuccessToast();
        }
    });

    @Override
    protected void onResume() {
        super.onResume();
        activeActivityRef = new WeakReference<>(this);
        updateConnectionUi();
        maybeAutoConnect(this);
        performPendingRobotButtonActionIfPossible();
    }

    @Override
    protected void onPause() {
        if (activeActivityRef.get() == this) {
            activeActivityRef.clear();
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                beginConnectFlow();
            } else {
                appendStatus(getString(R.string.gan_robot_status_bluetooth_disabled));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSION) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.permission_deny, Toast.LENGTH_SHORT).show();
                    appendStatus(getString(R.string.permission_deny));
                    return;
                }
            }
            beginConnectFlow();
        }
    }

    @Override
    public void onBackPressed() {
        if (scanDialog != null && scanDialog.isShowing()) {
            scanDialog.dismiss();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        mainHandler.removeCallbacks(forceDisconnectRunnable);
        super.onDestroy();
    }

}
