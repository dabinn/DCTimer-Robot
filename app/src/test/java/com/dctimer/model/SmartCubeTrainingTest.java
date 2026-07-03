package com.dctimer.model;

import com.dctimer.util.Utils;

import org.junit.Test;

import cs.min2phase.Tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmartCubeTrainingTest {
    private static final String SOLVED = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final int OLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_OLL;
    private static final int PLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_PLL;
    private static final int LAST_LAYER = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_LAST_LAYER;
    private static final int F2L = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_F2L;

    @Test
    public void identifies333CfopTrainingModes() {
        assertEquals(21, SmartCubeTraining.GROUP_333_CFOP);
        assertTrue(SmartCubeTraining.is333Cfop(OLL));
        assertTrue(SmartCubeTraining.is333CfopSub(PLL, SmartCubeTraining.SUB_PLL));
        assertTrue(SmartCubeTraining.isStageCompleteMode(OLL));
        assertTrue(SmartCubeTraining.isStageCompleteMode(F2L));
        assertFalse(SmartCubeTraining.isStageCompleteMode(PLL));
    }

    @Test
    public void defaultTrainingOrientationIsYellowTopGreenFront() {
        int[] pair = Utils.getSmartCubeOrientationPair(SmartCubeTraining.DEFAULT_TRAINING_ORIENTATION);

        assertEquals(3, pair[0]);
        assertEquals(2, pair[1]);
    }

    @Test
    public void ollCompletesWhenOrientationIsDoneEvenIfPllIsNotSolved() {
        String pllState = randomIncompletePllState();

        assertTrue(SmartCubeTraining.hasOLL(pllState));
        assertFalse(SmartCubeTraining.isComplete(PLL, pllState, 0));
        assertTrue(SmartCubeTraining.isComplete(OLL, pllState, 0));
    }

    @Test
    public void f2lCompletesWithoutSolvedLastLayer() {
        String lastLayerState = randomIncompleteLastLayerState();

        assertTrue(SmartCubeTraining.hasF2L(lastLayerState));
        assertFalse(SmartCubeTraining.isComplete(LAST_LAYER, lastLayerState, 0));
        assertTrue(SmartCubeTraining.isComplete(F2L, lastLayerState, 0));
    }

    @Test
    public void pllAndLastLayerRequireFullSolvedState() {
        String pllState = randomIncompletePllState();

        assertFalse(SmartCubeTraining.isComplete(PLL, pllState, 0));
        assertFalse(SmartCubeTraining.isComplete(LAST_LAYER, pllState, 0));
        assertTrue(SmartCubeTraining.isComplete(PLL, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(LAST_LAYER, SOLVED, 0));
    }

    @Test
    public void completionUsesTrainingOrientation() {
        String orientedSolvedAsPhysical = Utils.unorientFacelets(SOLVED, SmartCubeTraining.DEFAULT_TRAINING_ORIENTATION);

        assertTrue(SmartCubeTraining.isComplete(PLL, orientedSolvedAsPhysical, SmartCubeTraining.DEFAULT_TRAINING_ORIENTATION));
    }

    private static String randomIncompletePllState() {
        String state;
        do {
            state = Tools.randomPLL();
        } while (SmartCubeTraining.isComplete(PLL, state, 0));
        return state;
    }

    private static String randomIncompleteLastLayerState() {
        String state;
        do {
            state = Tools.randomLastLayer();
        } while (SmartCubeTraining.isComplete(LAST_LAYER, state, 0));
        return state;
    }
}
