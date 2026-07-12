package com.dctimer.util;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;

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

    private static volatile BluetoothGatt bluetoothGatt;
    private static volatile BluetoothGattCharacteristic statusCharacteristic;
    private static volatile BluetoothGattCharacteristic moveCharacteristic;
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

    public interface Callback {
        void onDeviceListChanged(List<BLEDevice> devices);

        void onScanFailed();

        void onConnected();

        void onDisconnected(BLEDevice device);

        void onUnsupportedDevice();

        void onConnectFailed();
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
        close();
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
            notifyConnected();
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
        Callback current = callback;
        if (current != null) {
            current.onConnected();
        }
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
        GanRobotProtocol.enableNotifications(gatt, service);
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
        return bluetoothGatt != null && statusCharacteristic != null && moveCharacteristic != null;
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
