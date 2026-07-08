package com.dctimer.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GanRobotCodec {
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
        List<String> expanded = new ArrayList<>();
        for (String rawToken : rawTokens) {
            String token = normalizeToken(rawToken);
            if (token.charAt(0) == 'U') {
                expanded.addAll(U_D_SWAP);
                expanded.add("D" + token.substring(1));
                expanded.addAll(U_D_UNSWAP);
            } else {
                expanded.add(token);
            }
        }
        return expanded;
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
