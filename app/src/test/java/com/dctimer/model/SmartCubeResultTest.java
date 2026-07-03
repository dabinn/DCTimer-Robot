package com.dctimer.model;

import org.junit.Test;

import java.lang.reflect.Field;

import cs.min2phase.Tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SmartCubeResultTest {
    private static final String SOLVED = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    @Test
    public void ganResultUsesDeviceMoveDeltasWithoutScaling() {
        SmartCube cube = new SmartCube();
        cube.setType(BLEDevice.TYPE_GANI_CUBE);
        cube.setCubeState(SOLVED);

        cube.applyMove(0, 0, null);
        cube.markSolveStarted(SOLVED);
        cube.applyMove(3, 500, null);
        cube.applyMove(6, 700, null);

        cube.calcResult();

        assertEquals(1200, cube.getResult());
    }

    @Test
    public void setCubeStateNotifiesWhenFaceletSyncReachesTargetScramble() throws Exception {
        SmartCube cube = new SmartCube();
        String targetScrambleState = Tools.fromScramble("R U");
        Field targetState = SmartCube.class.getDeclaredField("targetState");
        targetState.setAccessible(true);
        targetState.set(cube, targetScrambleState);

        final boolean[] scrambled = {false};
        cube.setStateChangedCallback(new SmartCube.StateChangedCallback() {
            @Override
            public void onScrambled(SmartCube cube) {
                scrambled[0] = true;
            }

            @Override
            public void onSolved(SmartCube cube) {
            }
        });

        cube.setCubeState(targetScrambleState);

        assertTrue(scrambled[0]);
    }

    @Test
    public void resetSolveTrackingKeepsCurrentStageState() {
        SmartCube cube = new SmartCube();
        cube.setCubeState(SOLVED);
        cube.applyMove(0, 0, null);
        String stageState = cube.getCubeState();

        cube.resetSolveTracking();

        assertEquals(stageState, cube.getCubeState());
        cube.applyMove(3, 500, null);
        cube.calcResult();
        assertEquals(0, cube.getResult());
    }
}
