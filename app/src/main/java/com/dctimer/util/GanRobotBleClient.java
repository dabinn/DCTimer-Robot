package com.dctimer.util;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;

import com.dctimer.R;

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

    public static void attach(BluetoothGatt gatt, BluetoothGattCharacteristic status, BluetoothGattCharacteristic move) {
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
