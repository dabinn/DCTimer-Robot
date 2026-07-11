package com.dctimer.util;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class GanRobotProtocol {
    private static final String UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_BUTTON = UUID.fromString("0000fff4" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_STATUS = UUID.fromString("0000fff2" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_MOVE = UUID.fromString("0000fff3" + UUID_SUFFIX);
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902" + UUID_SUFFIX);

    private static final UUID SERVICE_UUID_GAN_V2 = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dc4179");
    private static final UUID SERVICE_UUID_GAN_V3 = UUID.fromString("8653000a-43e6-47b7-9cb0-5fc21d4ae340");
    private static final UUID SERVICE_UUID_GAN_V4 = UUID.fromString("00000010-0000-fff7-fff6-fff5fff4fff0");

    private GanRobotProtocol() {
    }

    public static void enableNotifications(BluetoothGatt gatt, BluetoothGattService service) {
        if (gatt == null || service == null) {
            return;
        }
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        if (characteristics == null || characteristics.isEmpty()) {
            return;
        }
        for (BluetoothGattCharacteristic characteristic : characteristics) {
            if (characteristic == null) {
                continue;
            }
            int properties = characteristic.getProperties();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                    && (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
                continue;
            }
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                continue;
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
            if (descriptor == null) {
                continue;
            }
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            }
            gatt.writeDescriptor(descriptor);
        }
    }

    public static boolean isCandidate(String deviceName, ScanRecord scanRecord) {
        if (deviceName == null) {
            return false;
        }
        String normalized = deviceName.trim().toUpperCase(Locale.US);
        if (!normalized.startsWith("GANBOT-")) {
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
                    if (SERVICE_UUID.equals(uuid)) {
                        hasRobotService = true;
                    }
                    if (isGanSmartCubeService(uuid)) {
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

    private static boolean isGanSmartCubeService(UUID uuid) {
        return SERVICE_UUID_GAN_V2.equals(uuid)
                || SERVICE_UUID_GAN_V3.equals(uuid)
                || SERVICE_UUID_GAN_V4.equals(uuid);
    }
}
