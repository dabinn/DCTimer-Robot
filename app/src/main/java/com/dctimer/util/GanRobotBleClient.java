package com.dctimer.util;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.model.BLEDevice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class GanRobotBleClient {
    private static final Object GATT_IO_LOCK = new Object();
    private static final long GATT_TIMEOUT_MS = 8000L;
    public static final String PREF_NAME = "dctimer";
    public static final String PREF_GAN_ROBOT_AUTO_CONNECT = "ganrobot_auto_connect";
    private static final long AUTO_CONNECT_COOLDOWN_MS = 15000L;
    private static final long AUTO_SCAN_TIMEOUT_MS = 7000L;

    private static volatile BluetoothGatt bluetoothGatt;
    private static volatile BluetoothGattCharacteristic statusCharacteristic;
    private static volatile BluetoothGattCharacteristic moveCharacteristic;
    private static volatile boolean notificationDescriptorWritePending;
    private static CountDownLatch writeLatch;
    private static int writeStatus = BluetoothGatt.GATT_FAILURE;
    private static CountDownLatch readLatch;
    private static int readStatus = BluetoothGatt.GATT_FAILURE;
    private static byte[] readValue;
    private static volatile Callback callback;
    private static BluetoothAdapter bluetoothAdapter;
    private static BluetoothLeScanner bluetoothLeScanner;
    private static boolean scanning;
    private static Set<String> scannedAddresses;
    private static List<BLEDevice> scannedDevices = new ArrayList<>();
    private static final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private static boolean autoScanRunning;
    private static BluetoothLeScanner autoBluetoothLeScanner;
    private static BluetoothAdapter autoBluetoothAdapter;
    private static ScanCallback autoScanCallback;
    private static BluetoothAdapter.LeScanCallback autoLeScanCallback;
    private static Runnable autoStopScanRunnable;
    private static volatile long lastAutoConnectAttemptElapsedMs;
    private static volatile Context autoConnectToastContext;

    public interface Callback {
        void onDeviceListChanged(List<BLEDevice> devices);

        void onScanFailed();

        void onConnected();

        void onDisconnected(BLEDevice device);

        void onUnsupportedDevice();

        void onConnectFailed();
    }

    private interface DeviceCallback {
        void onDeviceFound(Context context, BluetoothDevice device);
    }

    public static class StatusSample {
        public final int movesRemaining;
        public final byte[] raw;

        StatusSample(int movesRemaining, byte[] raw) {
            this.movesRemaining = movesRemaining;
            this.raw = raw;
        }
    }

    private GanRobotBleClient() {
    }

    public static void setCallback(Callback callback) {
        GanRobotBleClient.callback = callback;
    }

    public static void maybeAutoConnect(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        autoConnectToastContext = appContext;
        if (shouldSkipAutoConnect(appContext)) {
            return;
        }
        BluetoothAdapter adapter = getEnabledBluetoothAdapter(appContext);
        if (adapter == null) {
            return;
        }
        markAutoConnectAttempt();
        if (connectBondedDeviceIfAvailable(appContext, adapter, GanRobotBleClient::connectRobotSilently)) {
            return;
        }
        startAutoScan(appContext, adapter, AUTO_SCAN_TIMEOUT_MS, autoConnectHandler,
                GanRobotBleClient::connectRobotSilently);
    }

    public static boolean isAutoConnectEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_GAN_ROBOT_AUTO_CONNECT, false);
    }

    public static synchronized boolean initBluetoothAdapter(Context context) {
        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        return bluetoothAdapter != null;
    }

    public static synchronized void startScan(Context context) {
        if (!initBluetoothAdapter(context) || scanning) {
            return;
        }
        scannedAddresses = new HashSet<>();
        scannedDevices = new ArrayList<>();
        notifyDeviceListChanged(scannedDevices);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bluetoothLeScanner != null) {
                    bluetoothLeScanner.startScan(scanCallback);
                    scanning = true;
                    return;
                }
            }
            bluetoothAdapter.startLeScan(leScanCallback);
            scanning = true;
        } catch (SecurityException e) {
            scanning = false;
            notifyScanFailed();
        }
    }

    public static synchronized void stopScan() {
        if (bluetoothAdapter != null && scanning) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bluetoothLeScanner != null) {
                    bluetoothLeScanner.stopScan(scanCallback);
                } else {
                    bluetoothAdapter.stopLeScan(leScanCallback);
                }
            } catch (SecurityException ignored) {
            }
        }
        scanning = false;
        bluetoothLeScanner = null;
    }

    public static synchronized void connectScannedDevice(Context context, int deviceIndex) {
        if (deviceIndex < 0 || deviceIndex >= scannedDevices.size()) {
            return;
        }
        connectDevice(context, scannedDevices.get(deviceIndex).getAddress());
    }

    public static synchronized void connectDevice(Context context, BluetoothDevice device) {
        if (device == null) {
            return;
        }
        connectGatt(context, device);
    }

    public static synchronized void closeConnection() {
        stopScan();
        stopAutoScan(autoConnectHandler);
        autoScanRunning = false;
        close();
    }

    private static boolean connectBondedDeviceIfAvailable(Context context, BluetoothAdapter adapter,
                                                          DeviceCallback callback) {
        if (context == null || adapter == null || callback == null) {
            return false;
        }
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                return false;
            }
            for (BluetoothDevice device : bondedDevices) {
                if (device == null) {
                    continue;
                }
                String name = device.getName();
                if (!GanRobotProtocol.isCandidate(name, null)) {
                    continue;
                }
                callback.onDeviceFound(context, device);
                return true;
            }
        } catch (SecurityException ignored) {
        }
        return false;
    }

    private static synchronized void startAutoScan(Context context, BluetoothAdapter adapter, long scanTimeoutMs,
                                                   Handler handler, DeviceCallback callback) {
        if (context == null || adapter == null || handler == null || callback == null || autoScanRunning) {
            return;
        }
        stopAutoScan(handler);
        autoScanRunning = true;
        autoBluetoothAdapter = adapter;
        autoStopScanRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (GanRobotBleClient.class) {
                    stopAutoScan(handler);
                    autoScanRunning = false;
                }
            }
        };
        handler.postDelayed(autoStopScanRunnable, scanTimeoutMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            autoBluetoothLeScanner = adapter.getBluetoothLeScanner();
            if (autoBluetoothLeScanner != null) {
                autoScanCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        BluetoothDevice device = result == null ? null : result.getDevice();
                        ScanRecord scanRecord = result == null ? null : result.getScanRecord();
                        onAutoDeviceScanned(context, device, scanRecord, handler, callback);
                    }
                };
                autoBluetoothLeScanner.startScan(autoScanCallback);
                return;
            }
        }
        autoLeScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                onAutoDeviceScanned(context, device, null, handler, callback);
            }
        };
        adapter.startLeScan(autoLeScanCallback);
    }

    private static void onAutoDeviceScanned(Context context, BluetoothDevice device, ScanRecord scanRecord,
                                            Handler handler, DeviceCallback callback) {
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
        synchronized (GanRobotBleClient.class) {
            if (!autoScanRunning) {
                return;
            }
            stopAutoScan(handler);
            autoScanRunning = false;
        }
        callback.onDeviceFound(context, device);
    }

    private static boolean shouldSkipAutoConnect(Context context) {
        return !canAcceptAutoConnectDevice(context) || isAutoConnectCooldownActive();
    }

    private static boolean isAutoConnectCooldownActive() {
        long now = SystemClock.elapsedRealtime();
        return now - lastAutoConnectAttemptElapsedMs < AUTO_CONNECT_COOLDOWN_MS;
    }

    private static void markAutoConnectAttempt() {
        lastAutoConnectAttemptElapsedMs = SystemClock.elapsedRealtime();
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
        Context appContext = context.getApplicationContext();
        if (!canAcceptAutoConnectDevice(appContext)) {
            return;
        }
        closeConnection();
        try {
            if (!initBluetoothAdapter(appContext)) {
                resetAutoConnection();
                return;
            }
            connectDevice(appContext, device);
        } catch (SecurityException e) {
            resetAutoConnection();
        }
    }

    private static boolean canAcceptAutoConnectDevice(Context context) {
        return isAutoConnectEnabled(context)
                && !hasGatt()
                && hasAutoConnectPermissions(context)
                && isLocationEnabled(context);
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

    private static void resetAutoConnection() {
        closeConnection();
    }

    private static void stopAutoScan(Handler handler) {
        if (autoStopScanRunnable != null && handler != null) {
            handler.removeCallbacks(autoStopScanRunnable);
            autoStopScanRunnable = null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && autoBluetoothLeScanner != null && autoScanCallback != null) {
                autoBluetoothLeScanner.stopScan(autoScanCallback);
            } else if (autoBluetoothAdapter != null && autoLeScanCallback != null) {
                autoBluetoothAdapter.stopLeScan(autoLeScanCallback);
            }
        } catch (SecurityException ignored) {
        }
        autoBluetoothLeScanner = null;
        autoScanCallback = null;
        autoLeScanCallback = null;
        autoBluetoothAdapter = null;
    }

    private static void connectDevice(Context context, String address) {
        if (context == null || address == null || !initBluetoothAdapter(context)) {
            notifyConnectFailed();
            return;
        }
        try {
            connectGatt(context, bluetoothAdapter.getRemoteDevice(address));
        } catch (IllegalArgumentException | SecurityException e) {
            notifyConnectFailed();
        }
    }

    private static void connectGatt(Context context, BluetoothDevice device) {
        close();
        try {
            Context appContext = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(appContext, false, gattCallback);
            }
            if (bluetoothGatt == null) {
                notifyConnectFailed();
            }
        } catch (SecurityException e) {
            notifyConnectFailed();
        }
    }

    private static final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            onDeviceScanned(device, null);
        }
    };

    private static final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onDeviceScanned(result == null ? null : result.getDevice(),
                    result == null ? null : result.getScanRecord());
        }
    };

    private static void onDeviceScanned(BluetoothDevice device, ScanRecord scanRecord) {
        if (device == null) {
            return;
        }
        String name;
        String address;
        try {
            name = device.getName();
            address = device.getAddress();
        } catch (SecurityException e) {
            return;
        }
        if (!GanRobotProtocol.isCandidate(name, scanRecord) || address == null) {
            return;
        }
        if (scannedAddresses == null) {
            scannedAddresses = new HashSet<>();
        }
        if (!scannedAddresses.add(address)) {
            return;
        }
        BLEDevice bleDevice = new BLEDevice(name == null ? address : name, address);
        bleDevice.setType(BLEDevice.TYPE_GAN_ROBOT);
        scannedDevices.add(bleDevice);
        notifyDeviceListChanged(scannedDevices);
    }

    private static final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    notifyConnectFailed();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                close();
                notifyDisconnected(null);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || !attach(gatt)) {
                notifyConnectFailed();
                try {
                    gatt.disconnect();
                } catch (SecurityException ignored) {
                }
                return;
            }
            if (!notificationDescriptorWritePending) {
                notifyConnected();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (gatt == bluetoothGatt
                    && notificationDescriptorWritePending
                    && descriptor != null
                    && GanRobotProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID.equals(descriptor.getUuid())) {
                notificationDescriptorWritePending = false;
                notifyConnected();
            }
        }

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
            if (characteristic != null && GanRobotProtocol.CHARACTER_UUID_BUTTON.equals(characteristic.getUuid())) {
                GanRobotController.handleRobotButtonEvent(value);
            }
        }
    };

    static void notifyDeviceListChanged(List<BLEDevice> devices) {
        Callback current = callback;
        if (current != null) {
            current.onDeviceListChanged(devices);
        }
    }

    static void notifyScanFailed() {
        Callback current = callback;
        if (current != null) {
            current.onScanFailed();
        }
    }

    static void notifyConnected() {
        GanRobotExecutor.runConnectionAxisCheck();
        GanRobotExecutor.warmUpSolver();
        Callback current = callback;
        if (current != null) {
            current.onConnected();
        } else {
            showAutoConnectSuccessToast();
        }
    }

    private static void showAutoConnectSuccessToast() {
        Context context = autoConnectToastContext;
        if (context == null) {
            context = APP.getInstance();
        }
        if (context == null) {
            return;
        }
        Context toastContext = context;
        autoConnectHandler.post(() ->
                Toast.makeText(toastContext, R.string.gan_robot_connected, Toast.LENGTH_SHORT).show());
    }

    static void notifyDisconnected(BLEDevice device) {
        Callback current = callback;
        if (current != null) {
            current.onDisconnected(device);
        }
    }

    static void notifyUnsupportedDevice() {
        Callback current = callback;
        if (current != null) {
            current.onUnsupportedDevice();
        }
    }

    static void notifyConnectFailed() {
        Callback current = callback;
        if (current != null) {
            current.onConnectFailed();
        }
    }

    public static boolean hasGatt() {
        return bluetoothGatt != null;
    }

    public static void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    public static void close() {
        BluetoothGatt gatt = bluetoothGatt;
        clear();
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean attach(BluetoothGatt gatt) {
        if (gatt == null) {
            return false;
        }
        BluetoothGattService service = gatt.getService(GanRobotProtocol.SERVICE_UUID);
        if (service == null) {
            return false;
        }
        BluetoothGattCharacteristic status = service.getCharacteristic(GanRobotProtocol.CHARACTER_UUID_STATUS);
        BluetoothGattCharacteristic move = service.getCharacteristic(GanRobotProtocol.CHARACTER_UUID_MOVE);
        if (status == null || move == null) {
            return false;
        }
        attach(gatt, status, move);
        notificationDescriptorWritePending = GanRobotProtocol.enableNotifications(gatt, service);
        return true;
    }

    private static void attach(BluetoothGatt gatt, BluetoothGattCharacteristic status, BluetoothGattCharacteristic move) {
        bluetoothGatt = gatt;
        statusCharacteristic = status;
        moveCharacteristic = move;
    }

    public static void clear() {
        statusCharacteristic = null;
        moveCharacteristic = null;
        notificationDescriptorWritePending = false;
        synchronized (GATT_IO_LOCK) {
            writeLatch = null;
            readLatch = null;
            readValue = null;
            writeStatus = BluetoothGatt.GATT_FAILURE;
            readStatus = BluetoothGatt.GATT_FAILURE;
        }
        bluetoothGatt = null;
    }

    public static boolean isReady() {
        return bluetoothGatt != null
                && statusCharacteristic != null
                && moveCharacteristic != null
                && !notificationDescriptorWritePending;
    }

    public static void onCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status) {
        synchronized (GATT_IO_LOCK) {
            if (writeLatch != null && moveCharacteristic != null && characteristic != null
                    && moveCharacteristic.getUuid().equals(characteristic.getUuid())) {
                writeStatus = status;
                writeLatch.countDown();
            }
        }
    }

    public static void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
        synchronized (GATT_IO_LOCK) {
            if (readLatch != null && statusCharacteristic != null && characteristic != null
                    && statusCharacteristic.getUuid().equals(characteristic.getUuid())) {
                readStatus = status;
                readValue = characteristic.getValue();
                readLatch.countDown();
            }
        }
    }

    public static void writeMovePacket(Context context, byte[] packet) throws Exception {
        BluetoothGatt gatt = bluetoothGatt;
        BluetoothGattCharacteristic move = moveCharacteristic;
        if (context == null || gatt == null || move == null) {
            throw new IllegalStateException(contextString(context, R.string.gan_robot_wait_connect));
        }
        synchronized (GATT_IO_LOCK) {
            writeStatus = BluetoothGatt.GATT_FAILURE;
            writeLatch = new CountDownLatch(1);
            move.setValue(packet);
            boolean started = gatt.writeCharacteristic(move);
            if (!started) {
                writeLatch = null;
                throw new IllegalStateException(context.getString(R.string.connect_fail));
            }
        }
        if (writeLatch == null || !writeLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(context.getString(R.string.gan_robot_status_timeout));
        }
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IllegalStateException(context.getString(R.string.gan_robot_status_write_failed, writeStatus));
        }
        synchronized (GATT_IO_LOCK) {
            writeLatch = null;
        }
    }

    public static StatusSample readMovesRemaining(Context context) throws Exception {
        BluetoothGatt gatt = bluetoothGatt;
        BluetoothGattCharacteristic status = statusCharacteristic;
        if (context == null || gatt == null || status == null) {
            throw new IllegalStateException(contextString(context, R.string.gan_robot_wait_connect));
        }
        synchronized (GATT_IO_LOCK) {
            readStatus = BluetoothGatt.GATT_FAILURE;
            readValue = null;
            readLatch = new CountDownLatch(1);
            boolean started = gatt.readCharacteristic(status);
            if (!started) {
                readLatch = null;
                throw new IllegalStateException(context.getString(R.string.connect_fail));
            }
        }
        if (readLatch == null || !readLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(context.getString(R.string.gan_robot_status_timeout));
        }
        if (readStatus != BluetoothGatt.GATT_SUCCESS || readValue == null || readValue.length == 0) {
            throw new IllegalStateException(context.getString(R.string.gan_robot_status_read_failed, readStatus));
        }
        synchronized (GATT_IO_LOCK) {
            readLatch = null;
        }
        byte[] snapshot = readValue.clone();
        return new StatusSample(snapshot[0] & 0xff, snapshot);
    }

    private static String contextString(Context context, int resId) {
        if (context == null) {
            return "";
        }
        return context.getString(resId);
    }
}
