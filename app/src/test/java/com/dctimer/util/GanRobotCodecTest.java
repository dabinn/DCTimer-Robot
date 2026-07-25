package com.dctimer.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GanRobotCodecTest {
    @Test
    public void parsesUMoveUsingSwapSequence() {
        List<String> moves = GanRobotCodec.parseMoves("U");
        assertEquals(13, moves.size());
        assertEquals("F", moves.get(0));
        assertEquals("D", moves.get(6));
    }

    @Test
    public void encodesFormulaEndAndZeroPadding() {
        List<byte[]> packets = GanRobotCodec.encodeScramble("R");
        assertEquals(1, packets.size());
        byte[] payload = packets.get(0);
        assertEquals(0x0f, payload[0] & 0xff);
        assertEquals(0x00, payload[1] & 0xff);
        assertEquals(0x00, payload[2] & 0xff);
    }

    @Test
    public void encodesOrientationProbeLikeOfficialFormula() {
        List<byte[]> packets = GanRobotCodec.encodeScramble("D F");
        assertEquals(1, packets.size());
        byte[] payload = packets.get(0);
        assertEquals(0x63, payload[0] & 0xff);
        assertEquals(0xf0, payload[1] & 0xff);
        assertEquals(0x00, payload[2] & 0xff);
    }

    @Test
    public void encodesConnectionAxisCheckSequence() {
        List<byte[]> packets = GanRobotCodec.encodeScramble("R F D L B");
        assertEquals(1, packets.size());
        byte[] payload = packets.get(0);
        assertEquals(0x03, payload[0] & 0xff);
        assertEquals(0x69, payload[1] & 0xff);
        assertEquals(0xcf, payload[2] & 0xff);
        assertEquals(0x00, payload[3] & 0xff);
    }

    @Test
    public void splitsLongScrambleIntoMultiplePackets() {
        List<byte[]> packets = GanRobotCodec.encodeScramble(
                "R F D L B R F D L B R F D L B R F D L B R F D L B R F D L B R F D L B R F"
        );
        assertTrue(packets.size() > 1);
    }

    @Test
    public void mergesConsecutiveUMovesBeforeExpansion() {
        List<String> moves = GanRobotCodec.parseMoves("U U");
        assertEquals(13, moves.size());
        assertEquals("D2", moves.get(6));
    }

    @Test
    public void cancelsConsecutiveInverseUMoves() {
        List<String> moves = GanRobotCodec.parseMoves("U U'");
        assertTrue(moves.isEmpty());
    }

    @Test
    public void simplifiesAdjacentSameFaceTurnsAfterExpansion() {
        List<String> moves = GanRobotCodec.parseMoves("R R");
        assertEquals(1, moves.size());
        assertEquals("R2", moves.get(0));
    }
}
