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
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
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
import com.dctimer.util.GanRobotCodec;
import com.dctimer.util.Utils;
import com.dctimer.widget.CustomToolbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private static class RobotStatusSample {
        final int movesRemaining;
        final byte[] raw;

        RobotStatusSample(int movesRemaining, byte[] raw) {
            this.movesRemaining = movesRemaining;
            this.raw = raw;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> scannedAddresses = new HashSet<>();
    private final List<BluetoothDevice> scannedDevices = new ArrayList<>();
    private final List<String> scannedDeviceNames = new ArrayList<>();
    private final Object gattIoLock = new Object();
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
    private ProgressBar progressConnecting;
    private int uiMode;
    private int connectionState = STATE_DISCONNECTED;
    private boolean isSending;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private AlertDialog scanDialog;
    private ProgressBar scanProgress;
    private ArrayAdapter<String> scanAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic statusCharacteristic;
    private BluetoothGattCharacteristic moveCharacteristic;
    private CountDownLatch writeLatch;
    private int writeStatus = BluetoothGatt.GATT_FAILURE;
    private CountDownLatch readLatch;
    private int readStatus = BluetoothGatt.GATT_FAILURE;
    private byte[] readValue;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        setContentView(R.layout.activity_gan_robot);
        bindViews();
        setupToolbar();
        applyThemeColors();
        updateConnectionUi();
        appendStatus(getString(R.string.gan_robot_disconnected));
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
        if (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED || connectionState == STATE_DISCONNECTING) {
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
            onDeviceScanned(device);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onDeviceScanned(result == null ? null : result.getDevice());
        }
    };

    private void onDeviceScanned(final BluetoothDevice device) {
        if (device == null) {
            return;
        }
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            return;
        }
        if (!isGanRobotCandidate(name)) {
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

    private boolean isGanRobotCandidate(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String normalized = deviceName.trim().toUpperCase(Locale.US);
        return normalized.startsWith("GAN");
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
                bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException e) {
            setConnectionState(STATE_DISCONNECTED);
            appendStatus(getString(R.string.connect_fail));
            Log.e(TAG, "connectGatt failed", e);
            Toast.makeText(this, R.string.connect_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectRobot() {
        if (bluetoothGatt == null) {
            setConnectionState(STATE_DISCONNECTED);
            return;
        }
        setConnectionState(STATE_DISCONNECTING);
        appendStatus(getString(R.string.gan_robot_disconnecting));
        try {
            bluetoothGatt.disconnect();
        } catch (SecurityException e) {
            Log.e(TAG, "disconnect failed", e);
            closeGatt();
            setConnectionState(STATE_DISCONNECTED);
        }
    }

    private void closeGatt() {
        statusCharacteristic = null;
        moveCharacteristic = null;
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) { }
            bluetoothGatt = null;
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
            statusCharacteristic = service.getCharacteristic(CHARACTER_UUID_STATUS);
            moveCharacteristic = service.getCharacteristic(CHARACTER_UUID_MOVE);
            if (statusCharacteristic == null || moveCharacteristic == null) {
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
            synchronized (gattIoLock) {
                if (writeLatch != null && moveCharacteristic != null && characteristic != null
                        && moveCharacteristic.getUuid().equals(characteristic.getUuid())) {
                    writeStatus = status;
                    writeLatch.countDown();
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (gattIoLock) {
                if (readLatch != null && statusCharacteristic != null && characteristic != null
                        && statusCharacteristic.getUuid().equals(characteristic.getUuid())) {
                    readStatus = status;
                    readValue = characteristic.getValue();
                    readLatch.countDown();
                }
            }
        }
    };

    private void submitScramble() {
        if (connectionState != STATE_CONNECTED || bluetoothGatt == null || statusCharacteristic == null || moveCharacteristic == null) {
            Toast.makeText(this, R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isSending) {
            Toast.makeText(this, R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        final String scramble = etScramble.getText() == null ? "" : etScramble.getText().toString();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                executeScramble(scramble);
            }
        });
    }

    private void executeScramble(String scramble) {
        List<byte[]> packets;
        try {
            packets = GanRobotCodec.encodeScramble(scramble);
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

    private void writeMovePacket(byte[] packet) throws Exception {
        BluetoothGatt gatt = bluetoothGatt;
        if (gatt == null || moveCharacteristic == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
        synchronized (gattIoLock) {
            writeStatus = BluetoothGatt.GATT_FAILURE;
            writeLatch = new CountDownLatch(1);
            moveCharacteristic.setValue(packet);
            boolean started = gatt.writeCharacteristic(moveCharacteristic);
            if (!started) {
                writeLatch = null;
                throw new IllegalStateException(getString(R.string.connect_fail));
            }
        }
        if (writeLatch == null || !writeLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
        }
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_write_failed, writeStatus));
        }
        synchronized (gattIoLock) {
            writeLatch = null;
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
        BluetoothGatt gatt = bluetoothGatt;
        if (gatt == null || statusCharacteristic == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
        synchronized (gattIoLock) {
            readStatus = BluetoothGatt.GATT_FAILURE;
            readValue = null;
            readLatch = new CountDownLatch(1);
            boolean started = gatt.readCharacteristic(statusCharacteristic);
            if (!started) {
                readLatch = null;
                throw new IllegalStateException(getString(R.string.connect_fail));
            }
        }
        if (readLatch == null || !readLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
        }
        if (readStatus != BluetoothGatt.GATT_SUCCESS || readValue == null || readValue.length == 0) {
            throw new IllegalStateException(getString(R.string.gan_robot_status_read_failed, readStatus));
        }
        synchronized (gattIoLock) {
            readLatch = null;
        }
        byte[] snapshot = readValue.clone();
        return new RobotStatusSample(snapshot[0] & 0xff, snapshot);
    }

    private void ensureGattConnected() {
        if (connectionState != STATE_CONNECTED || bluetoothGatt == null || statusCharacteristic == null || moveCharacteristic == null) {
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
        connectionState = state;
        updateConnectionUi();
    }

    private void updateConnectionUi() {
        if (tvConnectionState == null) {
            return;
        }
        int stateText;
        switch (connectionState) {
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
        boolean connected = connectionState == STATE_CONNECTED;
        boolean connecting = connectionState == STATE_CONNECTING || connectionState == STATE_DISCONNECTING;
        btnConnect.setVisibility(connected ? View.GONE : View.VISIBLE);
        btnDisconnect.setVisibility(connected ? View.VISIBLE : View.GONE);
        btnConnect.setEnabled(!connecting && !isSending);
        btnDisconnect.setEnabled(connected && !isSending);
        boolean hasInput = etScramble != null
                && etScramble.getText() != null
                && !TextUtils.isEmpty(etScramble.getText().toString().trim());
        btnSend.setEnabled(!isSending && hasInput);
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
        closeGatt();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
