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
    private static final int ZBLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZBLL;
    private static final int ZZLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZZLL;
    private static final int TWO_GLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_2GLL;
    private static final int ELL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ELL;
    private static final int ZBLS = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZBLS;
    private static final int COLL = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_COLL;
    private static final int OLLCP = SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_OLLCP;
    private static final int CMLL = SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_CMLL;
    private static final int LSE = SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_LSE;
    private static final int L10P = SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_L10P;

    @Test
    public void identifies333CfopTrainingModes() {
        assertEquals(21, SmartCubeTraining.GROUP_333_CFOP);
        assertEquals(22, SmartCubeTraining.GROUP_333_ROUX);
        assertTrue(SmartCubeTraining.is333Cfop(OLL));
        assertTrue(SmartCubeTraining.is333Roux(CMLL));
        assertTrue(SmartCubeTraining.isSmart333Training(CMLL));
        assertTrue(SmartCubeTraining.is333CfopSub(PLL, SmartCubeTraining.SUB_PLL));
        assertTrue(SmartCubeTraining.is333RouxSub(LSE, SmartCubeTraining.SUB_ROUX_LSE));
        assertTrue(SmartCubeTraining.is333RouxSub(L10P, SmartCubeTraining.SUB_ROUX_L10P));
        assertTrue(SmartCubeTraining.isStageCompleteMode(OLL));
        assertTrue(SmartCubeTraining.isStageCompleteMode(F2L));
        assertTrue(SmartCubeTraining.isStageCompleteMode(ZBLS));
        assertTrue(SmartCubeTraining.isStageCompleteMode(COLL));
        assertTrue(SmartCubeTraining.isStageCompleteMode(OLLCP));
        assertTrue(SmartCubeTraining.isStageCompleteMode(CMLL));
        assertFalse(SmartCubeTraining.isStageCompleteMode(PLL));
        assertFalse(SmartCubeTraining.isStageCompleteMode(ZBLL));
        assertFalse(SmartCubeTraining.isStageCompleteMode(LSE));
        assertFalse(SmartCubeTraining.isStageCompleteMode(L10P));
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
    public void zbllZzll2gllAndEllRequireFullSolvedState() {
        assertFalse(SmartCubeTraining.isComplete(ZBLL, randomIncompleteZbllState(), 0));
        assertFalse(SmartCubeTraining.isComplete(ZZLL, randomIncompleteZzllState(), 0));
        assertFalse(SmartCubeTraining.isComplete(TWO_GLL, randomIncomplete2gllState(), 0));
        assertFalse(SmartCubeTraining.isComplete(ELL, randomIncompleteEllState(), 0));

        assertTrue(SmartCubeTraining.isComplete(ZBLL, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(ZZLL, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(TWO_GLL, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(ELL, SOLVED, 0));
    }

    @Test
    public void zblsCompletesWhenEollIsDoneEvenIfLastLayerIsNotSolved() {
        String eollState = randomIncompleteEollState();

        assertTrue(SmartCubeTraining.hasEOLL(eollState));
        assertFalse(SmartCubeTraining.isComplete(LAST_LAYER, eollState, 0));
        assertTrue(SmartCubeTraining.isComplete(ZBLS, eollState, 0));
    }

    @Test
    public void collCompletesWhenCpllIsDoneEvenIfEpllIsNotSolved() {
        String cpllState = randomIncompleteCpllState();

        assertTrue(SmartCubeTraining.hasCPLL(cpllState));
        assertFalse(SmartCubeTraining.isComplete(LAST_LAYER, cpllState, 0));
        assertTrue(SmartCubeTraining.isComplete(COLL, cpllState, 0));
    }

    @Test
    public void ollcpCompletesWhenCpllIsDoneEvenIfEpllIsNotSolved() {
        String cpllState = randomIncompleteCpllState();
        String ollState = randomIncompleteOllcpState();

        assertTrue(SmartCubeTraining.hasCPLL(cpllState));
        assertFalse(SmartCubeTraining.isComplete(LAST_LAYER, cpllState, 0));
        assertTrue(SmartCubeTraining.isComplete(OLLCP, cpllState, 0));
        assertFalse(SmartCubeTraining.isComplete(OLL, ollState, 0));
        assertFalse(SmartCubeTraining.isComplete(OLLCP, ollState, 0));
    }

    @Test
    public void rouxCmllCompletesWhenCmllIsDoneEvenIfLseIsNotSolved() {
        String lseState = randomIncompleteRouxLseState();

        assertTrue(SmartCubeTraining.hasRouxCMLL(lseState));
        assertFalse(SmartCubeTraining.isComplete(LSE, lseState, 0));
        assertTrue(SmartCubeTraining.isComplete(CMLL, lseState, 0));
    }

    @Test
    public void rouxLseAndL10pRequireFullSolvedState() {
        assertFalse(SmartCubeTraining.isComplete(CMLL, randomIncompleteRouxCmllState(), 0));
        assertFalse(SmartCubeTraining.isComplete(LSE, randomIncompleteRouxLseState(), 0));
        assertFalse(SmartCubeTraining.isComplete(L10P, randomIncompleteRouxCmllState(), 0));

        assertTrue(SmartCubeTraining.isComplete(CMLL, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(LSE, SOLVED, 0));
        assertTrue(SmartCubeTraining.isComplete(L10P, SOLVED, 0));
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

    private static String randomIncompleteZbllState() {
        String state;
        do {
            state = Tools.randomZBLastLayer();
        } while (SmartCubeTraining.isComplete(ZBLL, state, 0));
        return state;
    }

    private static String randomIncompleteZzllState() {
        String state;
        do {
            state = Tools.randomZZLastLayer();
        } while (SmartCubeTraining.isComplete(ZZLL, state, 0));
        return state;
    }

    private static String randomIncomplete2gllState() {
        String state;
        do {
            state = Tools.randomState(
                    Tools.STATE_SOLVED,
                    new int[]{-1, -1, -1, -1, 0, 0, 0, 0},
                    new int[]{-1, -1, -1, -1, 4, 5, 6, 7, 8, 9, 10, 11},
                    Tools.STATE_SOLVED);
        } while (SmartCubeTraining.isComplete(TWO_GLL, state, 0));
        return state;
    }

    private static String randomIncompleteEllState() {
        String state;
        do {
            state = Tools.randomEdgeOfLastLayer();
        } while (SmartCubeTraining.isComplete(ELL, state, 0));
        return state;
    }

    private static String randomIncompleteEollState() {
        String state;
        do {
            state = Tools.randomZBLastLayer();
        } while (SmartCubeTraining.isComplete(LAST_LAYER, state, 0));
        return state;
    }

    private static String randomIncompleteCpllState() {
        String state;
        do {
            state = Tools.randomState(
                    Tools.STATE_SOLVED,
                    Tools.STATE_SOLVED,
                    new int[]{-1, -1, -1, -1, 4, 5, 6, 7, 8, 9, 10, 11},
                    Tools.STATE_SOLVED);
        } while (SmartCubeTraining.isComplete(LAST_LAYER, state, 0));
        return state;
    }

    private static String randomIncompleteOllcpState() {
        String state;
        do {
            state = Tools.randomLastLayer();
        } while (SmartCubeTraining.isComplete(OLL, state, 0));
        return state;
    }

    private static String randomIncompleteRouxCmllState() {
        String state;
        do {
            state = Tools.randomState(
                    new int[]{-1, -1, -1, -1, 4, 5, 6, 7},
                    new int[]{-1, -1, -1, -1, 0, 0, 0, 0},
                    new int[]{-1, -1, -1, -1, 4, -1, 6, -1, 8, 9, 10, 11},
                    new int[]{-1, -1, -1, -1, 0, -1, 0, -1, 0, 0, 0, 0});
        } while (SmartCubeTraining.isComplete(CMLL, state, 0));
        return state;
    }

    private static String randomIncompleteRouxLseState() {
        String state;
        do {
            state = Tools.randomState(
                    Tools.STATE_SOLVED,
                    Tools.STATE_SOLVED,
                    new int[]{-1, -1, -1, -1, 4, -1, 6, -1, 8, 9, 10, 11},
                    new int[]{-1, -1, -1, -1, 0, -1, 0, -1, 0, 0, 0, 0});
        } while (SmartCubeTraining.isComplete(LSE, state, 0));
        return state;
    }
}
