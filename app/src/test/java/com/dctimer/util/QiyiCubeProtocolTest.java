package com.dctimer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class QiyiCubeProtocolTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    public void parsesRealQiyiGyroFrameAndNormalizesMappedQuaternion() {
        byte[] frame = hex("CC100004F663FDBCFE59FEA0FDACDEA1");

        float[] quaternion = QiyiCubeProtocol.parseGyroQuaternion(frame);

        assertNotNull(quaternion);
        float norm = (float) Math.sqrt(0.58f * 0.58f + 0.423f * 0.423f
                + 0.352f * 0.352f + 0.596f * 0.596f);
        assertEquals(-0.580f / norm, quaternion[0], EPSILON);
        assertEquals(0.352f / norm, quaternion[1], EPSILON);
        assertEquals(-0.423f / norm, quaternion[2], EPSILON);
        assertEquals(-0.596f / norm, quaternion[3], EPSILON);
        assertEquals(1f, length(quaternion), EPSILON);
    }

    @Test
    public void mapsPositiveAndNegativeQiyiAxesBeforeNormalization() {
        byte[] frame = newGyroFrame(100, -200, 300, -400);

        float[] quaternion = QiyiCubeProtocol.parseGyroQuaternion(frame);

        assertNotNull(quaternion);
        float norm = (float) Math.sqrt(0.1f * 0.1f + 0.3f * 0.3f
                + 0.2f * 0.2f + 0.4f * 0.4f);
        assertEquals(0.1f / norm, quaternion[0], EPSILON);
        assertEquals(-0.3f / norm, quaternion[1], EPSILON);
        assertEquals(-0.2f / norm, quaternion[2], EPSILON);
        assertEquals(-0.4f / norm, quaternion[3], EPSILON);
    }

    @Test
    public void rejectsGyroFrameWithInvalidCrc() {
        byte[] frame = hex("CC100004F663FDBCFE59FEA0FDACDEA1");
        frame[6] ^= 0x01;

        assertNull(QiyiCubeProtocol.parseGyroQuaternion(frame));
    }

    @Test
    public void rejectsMalformedGyroFrames() {
        assertNull(QiyiCubeProtocol.parseGyroQuaternion(null));
        assertNull(QiyiCubeProtocol.parseGyroQuaternion(new byte[15]));

        byte[] wrongHeader = newGyroFrame(100, -200, 300, -400);
        wrongHeader[0] = (byte) 0xfe;
        assertNull(QiyiCubeProtocol.parseGyroQuaternion(wrongHeader));

        byte[] wrongLength = newGyroFrame(100, -200, 300, -400);
        wrongLength[1] = 0x11;
        assertNull(QiyiCubeProtocol.parseGyroQuaternion(wrongLength));
    }

    @Test
    public void rejectsZeroGyroQuaternionWithValidCrc() {
        assertNull(QiyiCubeProtocol.parseGyroQuaternion(newGyroFrame(0, 0, 0, 0)));
    }

    @Test
    public void doesNotMatchOrdinaryQiyiFrame() {
        byte[] ordinaryFrame = new byte[16];
        ordinaryFrame[0] = (byte) 0xfe;
        ordinaryFrame[1] = 0x10;

        assertNull(QiyiCubeProtocol.parseGyroQuaternion(ordinaryFrame));
    }

    @Test
    public void collectStateChangeMoves_scansAllHistorySlotsAndSortsByTimestamp() {
        byte[] msg = newStateChangeMessage(96, 9, 300);
        putHistorySlot(msg, 0, 5, 280);
        putEmptyHistorySlot(msg, 1);
        putHistorySlot(msg, 2, 3, 220);
        putHistorySlot(msg, 10, 7, 260);

        QiyiCubeProtocol.MoveSample[] moves = QiyiCubeProtocol.collectStateChangeMoves(msg, 200, 300);

        assertEquals(4, moves.length);
        assertMove(moves[0], 3, 220);
        assertMove(moves[1], 7, 260);
        assertMove(moves[2], 5, 280);
        assertMove(moves[3], 9, 300);
    }

    @Test
    public void collectStateChangeMoves_filtersOldInvalidAndDuplicateMoves() {
        byte[] msg = newStateChangeMessage(96, 4, 500);
        putHistorySlot(msg, 0, 12, 440);
        putHistorySlot(msg, 1, 13, 460);
        putHistorySlot(msg, 2, 4, 500);
        putHistorySlot(msg, 3, 1, 300);

        QiyiCubeProtocol.MoveSample[] moves = QiyiCubeProtocol.collectStateChangeMoves(msg, 400, 500);

        assertEquals(2, moves.length);
        assertMove(moves[0], 12, 440);
        assertMove(moves[1], 4, 500);
    }

    @Test
    public void collectStateChangeMoves_defersHistorySlotsNewerThanCurrentFrame() {
        byte[] msg = newStateChangeMessage(96, 3, 426604);
        putHistorySlot(msg, 0, 2, 426707);
        putHistorySlot(msg, 10, 2, 426582);

        QiyiCubeProtocol.MoveSample[] moves = QiyiCubeProtocol.collectStateChangeMoves(msg, 426582, 426604);

        assertEquals(1, moves.length);
        assertMove(moves[0], 3, 426604);
    }

    private static byte[] newStateChangeMessage(int length, int primaryMove, long primaryTimestamp) {
        byte[] msg = new byte[length];
        msg[2] = 0x03;
        putTimestamp(msg, 3, primaryTimestamp);
        msg[34] = (byte) primaryMove;
        for (int i = 0; i < 11; i++) {
            putEmptyHistorySlot(msg, i);
        }
        return msg;
    }

    private static void putHistorySlot(byte[] msg, int slot, int move, long timestamp) {
        int offset = 36 + slot * 5;
        putTimestamp(msg, offset, timestamp);
        msg[offset + 4] = (byte) move;
    }

    private static void putEmptyHistorySlot(byte[] msg, int slot) {
        int offset = 36 + slot * 5;
        for (int i = 0; i < 5; i++) {
            msg[offset + i] = (byte) 0xff;
        }
    }

    private static void putTimestamp(byte[] msg, int offset, long timestamp) {
        msg[offset] = (byte) ((timestamp >> 24) & 0xff);
        msg[offset + 1] = (byte) ((timestamp >> 16) & 0xff);
        msg[offset + 2] = (byte) ((timestamp >> 8) & 0xff);
        msg[offset + 3] = (byte) (timestamp & 0xff);
    }

    private static void assertMove(QiyiCubeProtocol.MoveSample move, int rawMove, long timestamp) {
        assertEquals(rawMove, move.move);
        assertEquals(timestamp, move.timestamp);
    }

    private static byte[] newGyroFrame(int ax, int ay, int az, int aw) {
        byte[] frame = new byte[16];
        frame[0] = (byte) 0xcc;
        frame[1] = 0x10;
        putInt16BigEndian(frame, 6, ax);
        putInt16BigEndian(frame, 8, ay);
        putInt16BigEndian(frame, 10, az);
        putInt16BigEndian(frame, 12, aw);
        int crc = QiyiCubeProtocol.crc16Modbus(frame, 14);
        frame[14] = (byte) crc;
        frame[15] = (byte) (crc >> 8);
        return frame;
    }

    private static void putInt16BigEndian(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

    private static float length(float[] quaternion) {
        return (float) Math.sqrt(quaternion[0] * quaternion[0]
                + quaternion[1] * quaternion[1] + quaternion[2] * quaternion[2]
                + quaternion[3] * quaternion[3]);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
