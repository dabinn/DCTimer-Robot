package com.dctimer.activity;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.ParcelUuid;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.model.SmartCubeTraining;
import com.dctimer.util.GanRobotCodec;
import com.dctimer.util.BluetoothTools;
import com.dctimer.util.RobotSessionState;
import com.dctimer.util.Utils;
import com.dctimer.widget.CustomToolbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cs.min2phase.CubieCube;
import cs.min2phase.Tools;
import cs.min2phase.Util;

public class GanRobotActivity extends AppCompatActivity {
    private static final String TAG = "GanRobotActivity";
    private static final UUID SERVICE_UUID_GAN_ROBOT = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTER_UUID_STATUS = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTER_UUID_MOVE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_ENABLE_BLUETOOTH = 31;
    private static final int REQUEST_BLE_PERMISSION = 32;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_DISCONNECTING = 3;
    private static final long SCAN_TIMEOUT_MS = 10000L;
    private static final long GATT_TIMEOUT_MS = 8000L;
    private static final int ROBOT_IDLE_ZERO_STREAK = 5;
    private static final long SMART_CUBE_PROBE_TIMEOUT_MS = 2500L;
    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    public static final String EXTRA_PREFILL_SCRAMBLE = "extra_prefill_scramble";
    public static final String EXTRA_PREFILL_SCRAMBLE_DISPLAY = "extra_prefill_scramble_display";

    private static class RobotStatusSample {
        final int movesRemaining;
        final byte[] raw;

        RobotStatusSample(int movesRemaining, byte[] raw) {
            this.movesRemaining = movesRemaining;
            this.raw = raw;
        }
    }

    private static class OrientationPlan {
        final String currentStateAfterProbe;
        final Map<Character, Character> logicalToPhysicalFaceMap;

        OrientationPlan(String currentStateAfterProbe, Map<Character, Character> logicalToPhysicalFaceMap) {
            this.currentStateAfterProbe = currentStateAfterProbe;
            this.logicalToPhysicalFaceMap = logicalToPhysicalFaceMap;
        }
    }

    private static class ScrambleResolutionResult {
        final String standardScramble;

        ScrambleResolutionResult(String standardScramble) {
            this.standardScramble = standardScramble;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> scannedAddresses = new HashSet<>();
    private final List<BluetoothDevice> scannedDevices = new ArrayList<>();
    private final List<String> scannedDeviceNames = new ArrayList<>();
    private static final Object SHARED_GATT_IO_LOCK = new Object();
    private static volatile int sharedConnectionState = STATE_DISCONNECTED;
    private static volatile BluetoothGatt sharedBluetoothGatt;
    private static volatile BluetoothGattCharacteristic sharedStatusCharacteristic;
    private static volatile BluetoothGattCharacteristic sharedMoveCharacteristic;
    private static CountDownLatch sharedWriteLatch;
    private static int sharedWriteStatus = BluetoothGatt.GATT_FAILURE;
    private static CountDownLatch sharedReadLatch;
    private static int sharedReadStatus = BluetoothGatt.GATT_FAILURE;
    private static byte[] sharedReadValue;
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
    private ProgressBar progressConnecting;
    private int uiMode;
    private boolean isSending;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private AlertDialog scanDialog;
    private ProgressBar scanProgress;
    private ArrayAdapter<String> scanAdapter;
    private String prefillRawScramble = "";
    private String prefillDisplayScramble = "";
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
        progressConnecting = findViewById(R.id.progress_connecting);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                beginConnectFlow();
            }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
                solveFromSmartCubeState();
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
        if (!isGanRobotCandidate(name, scanRecord)) {
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

    private boolean isGanRobotCandidate(String deviceName, ScanRecord scanRecord) {
        if (deviceName == null) {
            return false;
        }
        String normalized = deviceName.trim().toUpperCase(Locale.US);
        if (!normalized.startsWith("GAN")) {
            return false;
        }
        if (scanRecord != null) {
            List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
            if (serviceUuids != null && !serviceUuids.isEmpty()) {
                boolean hasRobotService = false;
                for (ParcelUuid parcelUuid : serviceUuids) {
                    UUID uuid = parcelUuid == null ? null : parcelUuid.getUuid();
                    if (uuid == null) {
                        continue;
                    }
                    if (SERVICE_UUID_GAN_ROBOT.equals(uuid)) {
                        hasRobotService = true;
                    }
                    if (BluetoothTools.SERVICE_UUID_GAN_V2.equals(uuid)
                            || BluetoothTools.SERVICE_UUID_GAN_V3.equals(uuid)
                            || BluetoothTools.SERVICE_UUID_GAN_V4.equals(uuid)) {
                        return false;
                    }
                }
                if (hasRobotService) {
                    return true;
                }
            }
        }
        return true;
    }

    private void connectRobot(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        closeGatt();
        setConnectionState(STATE_CONNECTING);
        appendStatus(getString(R.string.gan_robot_connecting));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sharedBluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                sharedBluetoothGatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException e) {
            setConnectionState(STATE_DISCONNECTED);
            appendStatus(getString(R.string.connect_fail));
            Log.e(TAG, "connectGatt failed", e);
            Toast.makeText(this, R.string.connect_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectRobot() {
        if (sharedBluetoothGatt == null) {
            setConnectionState(STATE_DISCONNECTED);
            return;
        }
        setConnectionState(STATE_DISCONNECTING);
        appendStatus(getString(R.string.gan_robot_disconnecting));
        try {
            sharedBluetoothGatt.disconnect();
        } catch (SecurityException e) {
            Log.e(TAG, "disconnect failed", e);
            closeGatt();
            setConnectionState(STATE_DISCONNECTED);
        }
    }

    private void closeGatt() {
        sharedStatusCharacteristic = null;
        sharedMoveCharacteristic = null;
        if (sharedBluetoothGatt != null) {
            try {
                sharedBluetoothGatt.close();
            } catch (Exception ignored) { }
            sharedBluetoothGatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Log.e(TAG, "discoverServices failed", e);
                    runOnUiThread(() -> {
                        appendStatus(getString(R.string.connect_fail));
                        disconnectRobot();
                    });
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    closeGatt();
                    setConnectionState(STATE_DISCONNECTED);
                    appendStatus(getString(R.string.gan_robot_status_disconnected));
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> {
                    appendStatus(getString(R.string.connect_fail));
                    disconnectRobot();
                });
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID_GAN_ROBOT);
            if (service == null) {
                runOnUiThread(() -> {
                    appendStatus(getString(R.string.ble_device_not_supported));
                    disconnectRobot();
                });
                return;
            }
            sharedStatusCharacteristic = service.getCharacteristic(CHARACTER_UUID_STATUS);
            sharedMoveCharacteristic = service.getCharacteristic(CHARACTER_UUID_MOVE);
            if (sharedStatusCharacteristic == null || sharedMoveCharacteristic == null) {
                runOnUiThread(() -> {
                    appendStatus(getString(R.string.ble_device_not_supported));
                    disconnectRobot();
                });
                return;
            }
            runOnUiThread(() -> {
                setConnectionState(STATE_CONNECTED);
                appendStatus(getString(R.string.gan_robot_connected));
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (SHARED_GATT_IO_LOCK) {
                if (sharedWriteLatch != null && sharedMoveCharacteristic != null && characteristic != null
                        && sharedMoveCharacteristic.getUuid().equals(characteristic.getUuid())) {
                    sharedWriteStatus = status;
                    sharedWriteLatch.countDown();
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (SHARED_GATT_IO_LOCK) {
                if (sharedReadLatch != null && sharedStatusCharacteristic != null && characteristic != null
                        && sharedStatusCharacteristic.getUuid().equals(characteristic.getUuid())) {
                    sharedReadStatus = status;
                    sharedReadValue = characteristic.getValue();
                    sharedReadLatch.countDown();
                }
            }
        }
    };

    private void submitScramble() {
        if (getConnectionState() != STATE_CONNECTED || sharedBluetoothGatt == null || sharedStatusCharacteristic == null || sharedMoveCharacteristic == null) {
            Toast.makeText(this, R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isSending) {
            Toast.makeText(this, R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        final String displayScramble = etScramble.getText() == null ? "" : etScramble.getText().toString();
        final String orientationLabel = getActiveScrambleOrientationLabel();
        ScrambleResolutionResult scrambleResolution = resolveStandardScrambleForSubmit(displayScramble);
        final String scramble = scrambleResolution.standardScramble;
        runOnUiThread(() -> appendStatus("Scramble orientation: " + orientationLabel));
        
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // Set robot moving flag at the very start
                RobotSessionState.setRobotMoving(true);
                try {
                    String currentCubeState = RobotSessionState.getLatestSmartCubeState();
                    if (!TextUtils.isEmpty(currentCubeState)) {
                        try {
                            String targetState = resolveTargetStateForSubmit();
                            runOnUiThread(() -> appendStatus("Smart cube detected -> state-to-state mode"));
                            executeStateToStatePlan(currentCubeState, targetState, "State plan");
                            return;
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                appendStatus(getString(R.string.gan_robot_send_failed, e.getMessage()));
                                Toast.makeText(GanRobotActivity.this, R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }
                    }
                    runOnUiThread(() -> appendStatus("No smart cube state -> direct execute mode"));
                    executeAlgorithm(scramble);
                } finally {
                    RobotSessionState.setRobotMoving(false);
                }
            }
        });
    }

    private void solveFromSmartCubeState() {
        if (getConnectionState() != STATE_CONNECTED || sharedBluetoothGatt == null || sharedStatusCharacteristic == null || sharedMoveCharacteristic == null) {
            Toast.makeText(this, R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isSending) {
            Toast.makeText(this, R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        final String currentCubeState = RobotSessionState.getLatestSmartCubeState();
        if (TextUtils.isEmpty(currentCubeState)) {
            appendStatus(getString(R.string.gan_robot_solve_requires_smart_cube));
            Toast.makeText(this, R.string.gan_robot_solve_requires_smart_cube, Toast.LENGTH_SHORT).show();
            return;
        }
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // Set robot moving flag at the very start
                RobotSessionState.setRobotMoving(true);
                try {
                    executeStateToStatePlan(currentCubeState, SOLVED_FACELET, "Solve plan");
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        appendStatus(getString(R.string.gan_robot_send_failed, e.getMessage()));
                        Toast.makeText(GanRobotActivity.this, R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
                    });
                } finally {
                    RobotSessionState.setRobotMoving(false);
                }
            }
        });
    }

    private void executeStateToStatePlan(String currentCubeState, String targetFacelet, String planLabel) throws Exception {
        OrientationPlan orientationPlan = runOrientationProbePlan(currentCubeState);
        String algorithmLogical = buildStateToStateAlgorithm(orientationPlan.currentStateAfterProbe, targetFacelet);
        String algorithm = remapAlgorithmWithFaceMap(algorithmLogical, orientationPlan.logicalToPhysicalFaceMap);
        runOnUiThread(() -> appendStatus("Orientation probe done (D/F)"));
        runOnUiThread(() -> appendStatus(planLabel + ": " + algorithm));
        executeAlgorithm(algorithm);
    }

    private void executeAlgorithm(String algorithm) {
        if (TextUtils.isEmpty(algorithm) || TextUtils.isEmpty(algorithm.trim())) {
            runOnUiThread(() -> {
                appendStatus(getString(R.string.gan_robot_send_success));
                Toast.makeText(GanRobotActivity.this, R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        List<byte[]> packets;
        try {
            packets = GanRobotCodec.encodeScramble(algorithm);
        } catch (IllegalArgumentException e) {
            runOnUiThread(() -> {
                appendStatus(getString(R.string.gan_robot_invalid_scramble, e.getMessage()));
                Toast.makeText(GanRobotActivity.this, R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        if (packets.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(GanRobotActivity.this, R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show());
            return;
        }
        setSending(true);
        runOnUiThread(() -> appendStatus(getString(R.string.gan_robot_waiting_execution, packets.size())));
        try {
            for (int i = 0; i < packets.size(); i++) {
                ensureGattConnected();
                writeMovePacket(packets.get(i));
                int remaining = waitRobotIdle();
                final int chunk = i + 1;
                final int left = remaining;
                runOnUiThread(() -> appendStatus(getString(R.string.gan_robot_execute_chunk, chunk, packets.size(), left)));
            }
            runOnUiThread(() -> {
                appendStatus(getString(R.string.gan_robot_send_success));
                Toast.makeText(GanRobotActivity.this, R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "execute scramble failed", e);
            runOnUiThread(() -> {
                appendStatus(getString(R.string.gan_robot_send_failed, e.getMessage()));
                Toast.makeText(GanRobotActivity.this, R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
            });
        } finally {
            setSending(false);
        }
    }

    private String buildStateToStateAlgorithm(String startFacelet, String targetFacelet) {
        String start = normalizeFacelet(startFacelet);
        String target = normalizeFacelet(targetFacelet);
        if (TextUtils.equals(start, target)) {
            return "";
        }
        // Get the difference between start and target states
        String scrambleFacelet = Tools.getScrambleFacelet(start, target);
        if (scrambleFacelet == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
        }
        // Solve the difference to get the algorithm
        String algorithm = new cs.min2phase.Search().solution(scrambleFacelet);
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
        }
        if (algorithm.startsWith("Error")) {
            throw new IllegalStateException(algorithm);
        }
        return algorithm.trim();
    }

    private ScrambleResolutionResult resolveStandardScrambleForSubmit(String displayScramble) {
        String normalizedInputDisplay = normalizeScrambleString(displayScramble);
        String normalizedPrefillDisplay = normalizeScrambleString(prefillDisplayScramble);
        if (!TextUtils.isEmpty(prefillRawScramble)
                && !TextUtils.isEmpty(normalizedInputDisplay)
                && !TextUtils.isEmpty(normalizedPrefillDisplay)
                && TextUtils.equals(normalizedInputDisplay, normalizedPrefillDisplay)) {
            return new ScrambleResolutionResult(prefillRawScramble);
        }
        return new ScrambleResolutionResult(convertDisplayScrambleToStandard(displayScramble));
    }

    private String resolveTargetStateForSubmit() {
        // Unified for normal/training mode: robot target is always the main-page target snapshot
        // captured when robot execution starts.
        String targetState = normalizeFacelet(RobotSessionState.getLatestMainTargetState());
        if (TextUtils.isEmpty(targetState)) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
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

    private String normalizeFacelet(String facelet) {
        if (TextUtils.isEmpty(facelet)) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_need_cube_state));
        }
        String normalized = facelet.trim();
        if (normalized.length() != 54) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        if (!normalized.matches("^[URFDLB]{54}$")) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        return normalized;
    }

    private OrientationPlan runOrientationProbePlan(String currentCubeState) throws Exception {
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

        Thread.sleep(200L);

        Map<Character, Character> logicalToPhysical = buildLogicalToPhysicalFaceMap(logicalFaceForPhysicalD, logicalFaceForPhysicalF);
        return new OrientationPlan(stateAfterF, logicalToPhysical);
    }

    private void writeProbeMove(String move) throws Exception {
        List<byte[]> packets = GanRobotCodec.encodeScramble(move);
        if (packets.isEmpty()) {
            throw new IllegalStateException("Probe move is empty");
        }
        for (byte[] packet : packets) {
            ensureGattConnected();
            writeMovePacket(packet);
            waitRobotIdle();
        }
    }

    private String waitForSmartCubeStateChange(String previousState, long timeoutMs, String probeName) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String prev = normalizeFacelet(previousState);
        while (SystemClock.elapsedRealtime() < deadline) {
            String now = RobotSessionState.getLatestSmartCubeState();
            if (!TextUtils.isEmpty(now)) {
                String normalizedNow = normalizeFacelet(now);
                if (!TextUtils.equals(prev, normalizedNow)) {
                    return normalizedNow;
                }
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("Smart cube probe timeout on " + probeName);
    }

    private char detectAppliedFaceClockwise(String beforeState, String afterState) {
        char[] faces = new char[] {'U', 'R', 'F', 'D', 'L', 'B'};
        for (char face : faces) {
            String transformed = applyFaceClockwise(beforeState, face);
            if (TextUtils.equals(transformed, afterState)) {
                return face;
            }
        }
        return 0;
    }

    private String applyFaceClockwise(String facelet, char face) {
        CubieCube cube = new CubieCube();
        if (Util.toCubieCube(facelet, cube) != 0) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        int moveIndex = toClockwiseMoveIndex(face);
        CubieCube moved = cube.move(moveIndex);
        return Util.toFaceCube(moved);
    }

    private int toClockwiseMoveIndex(char face) {
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

    private Map<Character, Character> buildLogicalToPhysicalFaceMap(char logicalForPhysicalD, char logicalForPhysicalF) {
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

    private String remapAlgorithmWithFaceMap(String algorithm, Map<Character, Character> logicalToPhysicalFaceMap) {
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

    private int[] faceToVector(char face) {
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

    private char vectorToFace(int[] vector) {
        if (vector[0] == 0 && vector[1] == 1 && vector[2] == 0) return 'U';
        if (vector[0] == 0 && vector[1] == -1 && vector[2] == 0) return 'D';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == 1) return 'F';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == -1) return 'B';
        if (vector[0] == 1 && vector[1] == 0 && vector[2] == 0) return 'R';
        if (vector[0] == -1 && vector[1] == 0 && vector[2] == 0) return 'L';
        throw new IllegalArgumentException("Invalid vector");
    }

    private int[] negate(int[] v) {
        return new int[] {-v[0], -v[1], -v[2]};
    }

    private int dot(int[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private int[] cross(int[] a, int[] b) {
        return new int[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private int norm1(int[] v) {
        return Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]);
    }

    private void writeMovePacket(byte[] packet) throws Exception {
        BluetoothGatt gatt = sharedBluetoothGatt;
        if (gatt == null || sharedMoveCharacteristic == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
        synchronized (SHARED_GATT_IO_LOCK) {
            sharedWriteStatus = BluetoothGatt.GATT_FAILURE;
            sharedWriteLatch = new CountDownLatch(1);
            sharedMoveCharacteristic.setValue(packet);
            boolean started = gatt.writeCharacteristic(sharedMoveCharacteristic);
            if (!started) {
                sharedWriteLatch = null;
                throw new IllegalStateException(getString(R.string.connect_fail));
            }
        }
        if (sharedWriteLatch == null || !sharedWriteLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
        }
        if (sharedWriteStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_write_failed, sharedWriteStatus));
        }
        synchronized (SHARED_GATT_IO_LOCK) {
            sharedWriteLatch = null;
        }
        runOnUiThread(() -> appendStatus("TX fff3: " + toHex(packet)));
    }

    private int waitRobotIdle() throws Exception {
        boolean seenNonZero = false;
        int zeroStreak = 0;
        long deadline = SystemClock.elapsedRealtime() + 20000L;
        int lastValue = 0;
        int lastLoggedValue = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            RobotStatusSample sample = readMovesRemaining();
            lastValue = sample.movesRemaining;
            if (lastValue != lastLoggedValue) {
                final int currentValue = lastValue;
                final String rawHex = toHex(sample.raw);
                runOnUiThread(() -> appendStatus("RX fff2: remaining=" + currentValue + " raw=" + rawHex));
                lastLoggedValue = lastValue;
            }
            if (lastValue > 0) {
                seenNonZero = true;
                zeroStreak = 0;
            } else {
                zeroStreak++;
                if (seenNonZero || zeroStreak >= ROBOT_IDLE_ZERO_STREAK) {
                    return lastValue;
                }
            }
            Thread.sleep(30L);
        }
        throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
    }

    private RobotStatusSample readMovesRemaining() throws Exception {
        BluetoothGatt gatt = sharedBluetoothGatt;
        if (gatt == null || sharedStatusCharacteristic == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
        synchronized (SHARED_GATT_IO_LOCK) {
            sharedReadStatus = BluetoothGatt.GATT_FAILURE;
            sharedReadValue = null;
            sharedReadLatch = new CountDownLatch(1);
            boolean started = gatt.readCharacteristic(sharedStatusCharacteristic);
            if (!started) {
                sharedReadLatch = null;
                throw new IllegalStateException(getString(R.string.connect_fail));
            }
        }
        if (sharedReadLatch == null || !sharedReadLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
        }
        if (sharedReadStatus != BluetoothGatt.GATT_SUCCESS || sharedReadValue == null || sharedReadValue.length == 0) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_read_failed, sharedReadStatus));
        }
        synchronized (SHARED_GATT_IO_LOCK) {
            sharedReadLatch = null;
        }
        byte[] snapshot = sharedReadValue.clone();
        return new RobotStatusSample(snapshot[0] & 0xff, snapshot);
    }

    private void ensureGattConnected() {
        if (getConnectionState() != STATE_CONNECTED || sharedBluetoothGatt == null || sharedStatusCharacteristic == null || sharedMoveCharacteristic == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
    }

    private void setSending(final boolean sending) {
        isSending = sending;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateConnectionUi();
            }
        });
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
        btnConnect.setEnabled(!connecting && !isSending);
        btnDisconnect.setEnabled(connected && !isSending);
        boolean hasInput = etScramble != null
                && etScramble.getText() != null
                && !TextUtils.isEmpty(etScramble.getText().toString().trim());
        btnSend.setEnabled(!isSending && hasInput);
        if (btnClear != null) {
            btnClear.setEnabled(!isSending);
        }
        if (btnSolve != null) {
            btnSolve.setEnabled(!isSending && connected);
        }
        progressConnecting.setVisibility(connecting ? View.VISIBLE : View.GONE);
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

    private String toHex(byte[] value) {
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
        // Only shutdown executor if no robot operations are in progress
        if (!RobotSessionState.isRobotMoving()) {
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
