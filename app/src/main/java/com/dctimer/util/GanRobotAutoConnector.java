package com.dctimer.util;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;

import java.util.Set;

public final class GanRobotAutoConnector {
    private static boolean scanRunning;
    private static BluetoothLeScanner bluetoothLeScanner;
    private static BluetoothAdapter bluetoothAdapter;
    private static ScanCallback scanCallback;
    private static BluetoothAdapter.LeScanCallback leScanCallback;
    private static Runnable stopScanRunnable;

    private GanRobotAutoConnector() {
    }

    public interface DeviceCallback {
        void onDeviceFound(Context context, BluetoothDevice device);
    }

    public static boolean connectBondedDeviceIfAvailable(Context context, BluetoothAdapter adapter,
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

    public static synchronized void startScan(Context context, BluetoothAdapter adapter, long scanTimeoutMs,
                                              Handler handler, DeviceCallback callback) {
        if (context == null || adapter == null || handler == null || callback == null || scanRunning) {
            return;
        }
        stopScan(handler);
        scanRunning = true;
        bluetoothAdapter = adapter;
        stopScanRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (GanRobotAutoConnector.class) {
                    stopScan(handler);
                    scanRunning = false;
                }
            }
        };
        handler.postDelayed(stopScanRunnable, scanTimeoutMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bluetoothLeScanner = adapter.getBluetoothLeScanner();
            if (bluetoothLeScanner != null) {
                scanCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        BluetoothDevice device = result == null ? null : result.getDevice();
                        ScanRecord scanRecord = result == null ? null : result.getScanRecord();
                        onDeviceScanned(context, device, scanRecord, handler, callback);
                    }
                };
                bluetoothLeScanner.startScan(scanCallback);
                return;
            }
        }
        leScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                onDeviceScanned(context, device, null, handler, callback);
            }
        };
        adapter.startLeScan(leScanCallback);
    }

    public static synchronized boolean isScanRunning() {
        return scanRunning;
    }

    private static void onDeviceScanned(Context context, BluetoothDevice device, ScanRecord scanRecord,
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
        synchronized (GanRobotAutoConnector.class) {
            if (!scanRunning) {
                return;
            }
            stopScan(handler);
            scanRunning = false;
        }
        callback.onDeviceFound(context, device);
    }

    private static void stopScan(Handler handler) {
        if (stopScanRunnable != null && handler != null) {
            handler.removeCallbacks(stopScanRunnable);
            stopScanRunnable = null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bluetoothLeScanner != null && scanCallback != null) {
                bluetoothLeScanner.stopScan(scanCallback);
            } else if (bluetoothAdapter != null && leScanCallback != null) {
                bluetoothAdapter.stopLeScan(leScanCallback);
            }
        } catch (SecurityException ignored) {
        }
        bluetoothLeScanner = null;
        scanCallback = null;
        leScanCallback = null;
        bluetoothAdapter = null;
    }
}
