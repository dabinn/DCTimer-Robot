package com.dctimer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class GanCubeProtocolTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    public void parsesGen2GyroQuaternionFromBitFour() {
        float[] quaternion = GanCubeProtocol.parseV2GyroEvent(
                payload(0x1, 4, 0x6000, 0x4000, 0xc000, 0x2000));

        assertNotNull(quaternion);
        assertMappedQuaternion(quaternion, 0x6000, 0x4000, 0xc000, 0x2000);
    }

    @Test
    public void parsesGen4GyroQuaternionFromBitSixteen() {
        char[] bits = payloadBits(0xec, 8, 16, 0x4000, 0x7fff, 0x0000, 0x0000);
        writeBits(bits, 8, 8, 0x08);

        float[] quaternion = GanCubeProtocol.parseV4GyroEvent(new String(bits));

        assertNotNull(quaternion);
        assertMappedQuaternion(quaternion, 0x4000, 0x7fff, 0x0000, 0x0000);
    }

    @Test
    public void rejectsInvalidQuaternionPayloads() {
        assertNull(GanCubeProtocol.parseGyroQuaternion(null, 0));
        assertNull(GanCubeProtocol.parseGyroQuaternion(repeat('0', 63), 0));
        assertNull(GanCubeProtocol.parseGyroQuaternion(repeat('0', 64), 0));

        char[] invalid = repeat('0', 64).toCharArray();
        invalid[4] = 'x';
        assertNull(GanCubeProtocol.parseGyroQuaternion(new String(invalid), 0));
    }

    @Test
    public void doesNotTreatMoveOrGen3EventsAsGyro() {
        String gen2Move = new String(payloadBits(0x2, 4, 4, 0x7fff, 0, 0, 0));
        String gen4Move = new String(payloadBits(0x01, 8, 16, 0x7fff, 0, 0, 0));
        String gen3Move = new String(payloadBits(0x55, 8, 16, 0x7fff, 0, 0, 0));

        assertNull(GanCubeProtocol.parseV2GyroEvent(gen2Move));
        assertNull(GanCubeProtocol.parseV4GyroEvent(gen4Move));
        assertNull(GanCubeProtocol.parseV2GyroEvent(gen3Move));
        assertNull(GanCubeProtocol.parseV4GyroEvent(gen3Move));
    }

    private static void assertMappedQuaternion(float[] actual, int rawW, int rawX, int rawY, int rawZ) {
        float w = decode(rawW);
        float x = decode(rawX);
        float y = decode(rawY);
        float z = decode(rawZ);
        float norm = (float) Math.sqrt(w * w + x * x + y * y + z * z);

        assertEquals(x / norm, actual[0], EPSILON);
        assertEquals(z / norm, actual[1], EPSILON);
        assertEquals(-y / norm, actual[2], EPSILON);
        assertEquals(w / norm, actual[3], EPSILON);
        assertEquals(1f, length(actual), EPSILON);
    }

    private static float decode(int raw) {
        float sign = (raw & 0x8000) == 0 ? 1f : -1f;
        return sign * (raw & 0x7fff) / 0x7fff;
    }

    private static float length(float[] quaternion) {
        return (float) Math.sqrt(quaternion[0] * quaternion[0]
                + quaternion[1] * quaternion[1]
                + quaternion[2] * quaternion[2]
                + quaternion[3] * quaternion[3]);
    }

    private static char[] payloadBits(int eventType, int eventTypeLength, int quaternionStart,
                                      int rawW, int rawX, int rawY, int rawZ) {
        char[] bits = repeat('0', 96).toCharArray();
        writeBits(bits, 0, eventTypeLength, eventType);
        writeBits(bits, quaternionStart, 16, rawW);
        writeBits(bits, quaternionStart + 16, 16, rawX);
        writeBits(bits, quaternionStart + 32, 16, rawY);
        writeBits(bits, quaternionStart + 48, 16, rawZ);
        return bits;
    }

    private static String payload(int eventType, int quaternionStart,
                                  int rawW, int rawX, int rawY, int rawZ) {
        return new String(payloadBits(eventType, 4, quaternionStart, rawW, rawX, rawY, rawZ));
    }

    private static void writeBits(char[] bits, int start, int length, int value) {
        for (int i = 0; i < length; i++) {
            int shift = length - 1 - i;
            bits[start + i] = ((value >> shift) & 1) == 0 ? '0' : '1';
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) {
            chars[i] = value;
        }
        return new String(chars);
    }
}
