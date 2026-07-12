package com.dctimer.util;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GanRobotProtocol {
    private static final String UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_BUTTON = UUID.fromString("0000fff4" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_STATUS = UUID.fromString("0000fff2" + UUID_SUFFIX);
    public static final UUID CHARACTER_UUID_MOVE = UUID.fromString("0000fff3" + UUID_SUFFIX);
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902" + UUID_SUFFIX);

    private static final int PACKET_TYPE_BUTTON_PRESS = 0x02;

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

    public static boolean isButtonPressEvent(byte[] rawValue) {
        return rawValue != null
                && rawValue.length > 0
                && (rawValue[0] & 0xff) == PACKET_TYPE_BUTTON_PRESS;
    }

    private static boolean isGanSmartCubeService(UUID uuid) {
        return SERVICE_UUID_GAN_V2.equals(uuid)
                || SERVICE_UUID_GAN_V3.equals(uuid)
                || SERVICE_UUID_GAN_V4.equals(uuid);
    }
}

final class GanRobotCodec {
    private static final int MAX_NIBBLES_PER_WRITE = 18 * 2;
    private static final List<String> U_D_SWAP = Arrays.asList("F", "B", "R2", "L2", "B'", "F'");
    private static final List<String> U_D_UNSWAP = Arrays.asList("F", "B", "L2", "R2", "B'", "F'");
    private static final Map<String, Integer> MOVE_MAP = createMoveMap();

    private GanRobotCodec() { }

    public static List<byte[]> encodeScramble(String scramble) {
        List<String> moves = parseMoves(scramble);
        List<Integer> nibbles = movesToNibbles(moves);
        if (nibbles.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> packets = new ArrayList<>();
        for (int offset = 0; offset < nibbles.size(); offset += MAX_NIBBLES_PER_WRITE) {
            int end = Math.min(offset + MAX_NIBBLES_PER_WRITE, nibbles.size());
            packets.add(packNibbles(nibbles.subList(offset, end)));
        }
        return packets;
    }

    public static int estimateRobotCost(String algorithm) {
        return parseMoves(algorithm).size();
    }

    static List<String> parseMoves(String scramble) {
        if (scramble == null) {
            throw new IllegalArgumentException("scramble is empty");
        }
        String normalized = scramble
                .replace('\u2019', '\'')
                .replace('\uFF07', '\'')
                .trim()
                .replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("scramble is empty");
        }
        String[] rawTokens = normalized.split(" ");
        List<String> tokens = new ArrayList<>(rawTokens.length);
        for (String rawToken : rawTokens) {
            tokens.add(normalizeToken(rawToken));
        }

        List<String> expanded = new ArrayList<>(tokens.size() * 2);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.charAt(0) == 'U') {
                int totalQuarterTurns = 0;
                int j = i;
                while (j < tokens.size() && tokens.get(j).charAt(0) == 'U') {
                    totalQuarterTurns = (totalQuarterTurns + toQuarterTurns(tokens.get(j))) % 4;
                    j++;
                }
                if (totalQuarterTurns != 0) {
                    expanded.addAll(U_D_SWAP);
                    expanded.add("D" + toSuffix(totalQuarterTurns));
                    expanded.addAll(U_D_UNSWAP);
                }
                i = j - 1;
            } else {
                expanded.add(token);
            }
        }
        return simplifyAdjacentFaceTurns(expanded);
    }

    static List<Integer> movesToNibbles(List<String> moves) {
        List<Integer> nibbles = new ArrayList<>(moves.size());
        for (String move : moves) {
            Integer nibble = MOVE_MAP.get(move);
            if (nibble == null) {
                throw new IllegalArgumentException("Unsupported move: " + move);
            }
            nibbles.add(nibble);
        }
        return nibbles;
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Invalid move: null");
        }
        String normalized = token.trim().toUpperCase(Locale.US);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Invalid move: " + token);
        }
        if (normalized.endsWith("2'")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.matches("^[RUFBLD](2|')?$")) {
            throw new IllegalArgumentException("Invalid move: " + token);
        }
        return normalized;
    }

    private static int toQuarterTurns(String move) {
        if (move.endsWith("2")) {
            return 2;
        }
        if (move.endsWith("'")) {
            return 3;
        }
        return 1;
    }

    private static String toSuffix(int quarterTurns) {
        if (quarterTurns == 2) {
            return "2";
        }
        if (quarterTurns == 3) {
            return "'";
        }
        return "";
    }

    private static List<String> simplifyAdjacentFaceTurns(List<String> moves) {
        List<String> simplified = new ArrayList<>(moves.size());
        for (String move : moves) {
            if (simplified.isEmpty()) {
                simplified.add(move);
                continue;
            }
            int lastIdx = simplified.size() - 1;
            String lastMove = simplified.get(lastIdx);
            if (lastMove.charAt(0) != move.charAt(0)) {
                simplified.add(move);
                continue;
            }
            int combined = (toQuarterTurns(lastMove) + toQuarterTurns(move)) % 4;
            if (combined == 0) {
                simplified.remove(lastIdx);
            } else {
                simplified.set(lastIdx, lastMove.charAt(0) + toSuffix(combined));
            }
        }
        return simplified;
    }

    private static byte[] packNibbles(List<Integer> nibbles) {
        byte[] bytes = new byte[18];
        Arrays.fill(bytes, (byte) 0xff);
        for (int i = 0; i < nibbles.size(); i++) {
            int value = nibbles.get(i) & 0x0f;
            int idx = i / 2;
            if (i % 2 == 0) {
                bytes[idx] = (byte) (value << 4);
            } else {
                bytes[idx] = (byte) ((bytes[idx] & 0xf0) | value);
            }
        }
        if (nibbles.size() % 2 == 1) {
            int idx = nibbles.size() / 2;
            bytes[idx] = (byte) ((bytes[idx] & 0xf0) | 0x0f);
        }
        return bytes;
    }

    private static Map<String, Integer> createMoveMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("R", 0);
        map.put("R2", 1);
        map.put("R'", 2);
        map.put("F", 3);
        map.put("F2", 4);
        map.put("F'", 5);
        map.put("D", 6);
        map.put("D2", 7);
        map.put("D'", 8);
        map.put("L", 9);
        map.put("L2", 10);
        map.put("L'", 11);
        map.put("B", 12);
        map.put("B2", 13);
        map.put("B'", 14);
        return map;
    }
}
