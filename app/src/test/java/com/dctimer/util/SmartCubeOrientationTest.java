package com.dctimer.util;

import org.junit.Test;

import cs.min2phase.Tools;

import static org.junit.Assert.assertEquals;

public class SmartCubeOrientationTest {
    private static final String SOLVED = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final String SCRAMBLED = Tools.fromScramble("R U F2 D' L B2 R' U2");

    @Test
    public void defaultOrientationKeepsFaceletStateAndMovesUnchanged() {
        assertEquals(SOLVED, Utils.orientFacelets(SOLVED, 0));
        assertEquals(SOLVED, Utils.unorientFacelets(SOLVED, 0));
        for (int move = 0; move < 18; move++) {
            assertEquals(move, Utils.orientSmartCubeMove(move, 0));
            assertEquals(move, Utils.unorientSmartCubeMove(move, 0));
        }
    }

    @Test
    public void allOrientationsCanRoundTripFaceletsAndMoves() {
        for (int orientation = 0; orientation < Utils.SMART_CUBE_ORIENTATION_FACES.length; orientation++) {
            assertEquals(SCRAMBLED, Utils.unorientFacelets(Utils.orientFacelets(SCRAMBLED, orientation), orientation));
            for (int move = 0; move < 18; move++) {
                assertEquals(move, Utils.unorientSmartCubeMove(Utils.orientSmartCubeMove(move, orientation), orientation));
            }
        }
    }

    @Test
    public void orientedMoveMatchesOrientedFaceletStateChange() {
        for (int orientation = 0; orientation < Utils.SMART_CUBE_ORIENTATION_FACES.length; orientation++) {
            String orientedStart = Utils.orientFacelets(SCRAMBLED, orientation);
            for (int move = 0; move < 18; move++) {
                String physicalThenOrient = Utils.orientFacelets(Utils.applySmartCubeMove(SCRAMBLED, move), orientation);
                String orientThenDisplayMove = Utils.applySmartCubeMove(orientedStart, Utils.orientSmartCubeMove(move, orientation));
                assertEquals(physicalThenOrient, orientThenDisplayMove);
            }
        }
    }
}
