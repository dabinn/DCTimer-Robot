package com.dctimer.util;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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

import androidx.core.content.ContextCompat;

import java.util.Set;

public final class GanRobotAutoConnector {
    public static final String PREF_NAME = "dctimer";
    public static final String PREF_GAN_ROBOT_AUTO_CONNECT = "ganrobot_auto_connect";
    private static final long AUTO_CONNECT_COOLDOWN_MS = 15000L;
    private static final long AUTO_SCAN_TIMEOUT_MS = 7000L;

    private static final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private static boolean scanRunning;
    private static BluetoothLeScanner bluetoothLeScanner;
    private static BluetoothAdapter bluetoothAdapter;
    private static ScanCallback scanCallback;
    private static BluetoothAdapter.LeScanCallback leScanCallback;
    private static Runnable stopScanRunnable;
    private static volatile long lastAutoConnectAttemptElapsedMs;

    private GanRobotAutoConnector() {
    }

    public interface DeviceCallback {
        void onDeviceFound(Context context, BluetoothDevice device);
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
        if (connectBondedDeviceIfAvailable(appContext, adapter, GanRobotAutoConnector::connectRobotSilently)) {
            return;
        }
        startScan(appContext, adapter, AUTO_SCAN_TIMEOUT_MS, autoConnectHandler,
                GanRobotAutoConnector::connectRobotSilently);
    }

    public static boolean isAutoConnectEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_GAN_ROBOT_AUTO_CONNECT, false);
    }

    public static void closeConnection() {
        GanRobotBleClient.closeConnection();
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
            if (!GanRobotBleClient.initBluetoothAdapter(appContext)) {
                resetAutoConnection();
                return;
            }
            GanRobotBleClient.connectDevice(appContext, device);
        } catch (SecurityException e) {
            resetAutoConnection();
        }
    }

    private static boolean canAcceptAutoConnectDevice(Context context) {
        return isAutoConnectEnabled(context)
                && !GanRobotBleClient.hasGatt()
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
        GanRobotBleClient.close();
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
