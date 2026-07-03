package scrambler;

import org.junit.Test;

import java.lang.reflect.Field;

import com.dctimer.model.SmartCubeTraining;
import com.dctimer.util.Utils;

import cs.min2phase.Tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScramblerTest {
    private static final String SOLVED = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    @Test
    public void setScrambleClearsCachedCubeStateForImportedScramble() throws Exception {
        Scrambler scrambler = new Scrambler(null);

        Field cubeState = Scrambler.class.getDeclaredField("cubeState");
        cubeState.setAccessible(true);
        cubeState.set(scrambler, "cached-state");

        Field hint = Scrambler.class.getDeclaredField("hint");
        hint.setAccessible(true);
        hint.set(scrambler, "\nold hint");

        scrambler.setScramble("F R");

        assertEquals("", cubeState.get(scrambler));
        assertEquals("", hint.get(scrambler));
    }

    @Test
    public void ivyCubeScrambleGeneratesFormulaWithoutImage() {
        Scrambler scrambler = new Scrambler(null);

        for (int i = 0; i < 20; i++) {
            scrambler.generateScramble(522, true);

            assertEquals(0, scrambler.getScrambleLen());
            assertEquals(0, scrambler.getImageType());
            assertFalse(scrambler.getScramble().isEmpty());
            for (String token : scrambler.getScramble().split(" ")) {
                assertFalse(token.isEmpty());
                assertFalse(token.matches(".*[^RLUB'].*"));
            }
        }
    }

    @Test
    public void smart333CfopScramblesGenerate3x3States() {
        Scrambler scrambler = new Scrambler(null);

        for (int sub = 0; sub < SmartCubeTraining.SUB_COUNT; sub++) {
            scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_CFOP_BASE + sub, true);

            assertTrue(scrambler.is333Scramble());
            assertEquals(3, scrambler.getImageType());
            assertFalse(scrambler.getScramble().isEmpty());
            assertFalse(scrambler.getCubeState().isEmpty());
        }
    }

    @Test
    public void smart333RouxScramblesGenerate3x3States() {
        Scrambler scrambler = new Scrambler(null);

        for (int sub = 0; sub < SmartCubeTraining.ROUX_SUB_COUNT; sub++) {
            scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_ROUX_BASE + sub, true);

            assertTrue(scrambler.is333Scramble());
            assertTrue(scrambler.isSmart333TrainingScramble());
            assertEquals(3, scrambler.getImageType());
            assertFalse(scrambler.getScramble().isEmpty());
            assertFalse(scrambler.getCubeState().isEmpty());
        }
    }

    @Test
    public void expandedSmart333CfopScramblesMatchTrainingSemantics() {
        Scrambler scrambler = new Scrambler(null);

        assertFullSolveTrainingMode(SmartCubeTraining.SUB_ZBLL);
        assertFullSolveTrainingMode(SmartCubeTraining.SUB_ZZLL);
        assertFullSolveTrainingMode(SmartCubeTraining.SUB_2GLL);
        assertFullSolveTrainingMode(SmartCubeTraining.SUB_ELL);

        scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZBLS, true);
        String zblsState = scrambler.getCubeState();
        assertFalse(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZBLS, zblsState, 0));

        scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_COLL, true);
        String collState = scrambler.getCubeState();
        assertTrue(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_ZBLS, collState, 0));
        assertFalse(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_COLL, collState, 0));
    }

    @Test
    public void smart333RouxScramblesMatchTrainingSemantics() {
        Scrambler scrambler = new Scrambler(null);

        scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_CMLL, true);
        String cmllState = scrambler.getCubeState();
        assertFalse(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_CMLL, cmllState, 0));

        scrambler.generateScramble(SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_LSE, true);
        String lseState = scrambler.getCubeState();
        assertTrue(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_CMLL, lseState, 0));
        assertFalse(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_LSE, lseState, 0));
    }

    @Test
    public void parsed333CfopScrambleKeeps3x3ImageType() {
        Scrambler scrambler = new Scrambler(null);

        scrambler.parseScramble(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_OLL,
                "R U R' U'");

        assertEquals(3, scrambler.getImageType());
    }

    @Test
    public void parsed333RouxScrambleKeeps3x3ImageType() {
        Scrambler scrambler = new Scrambler(null);

        scrambler.parseScramble(
                SmartCubeTraining.CATEGORY_333_ROUX_BASE + SmartCubeTraining.SUB_ROUX_CMLL,
                "R U R' U'");

        assertEquals(3, scrambler.getImageType());
    }

    @Test
    public void scrambleBetweenStatesMovesStartToTarget() {
        String start = Tools.fromScramble("R U F");
        String target = Tools.randomPLL();

        String scramble = Scrambler.buildScrambleBetweenStates(start, target);

        assertEquals(target, applyScramble(start, scramble));
    }

    @Test
    public void scrambleBetweenStatesSupportsTrainingDisplayMovesMappedToPhysicalTarget() {
        String start = Tools.fromScramble("R U F");
        Scrambler trainingScrambler = new Scrambler(null);
        trainingScrambler.generateScramble(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_LAST_LAYER, true);
        String physicalTarget = applyTrainingDisplayScramble(SOLVED, trainingScrambler.getScramble());

        String scramble = Scrambler.buildScrambleBetweenStates(start, physicalTarget);

        assertEquals(physicalTarget, applyScramble(start, scramble));
        assertTrue(SmartCubeTraining.isComplete(
                SmartCubeTraining.CATEGORY_333_CFOP_BASE + SmartCubeTraining.SUB_F2L,
                physicalTarget,
                SmartCubeTraining.DEFAULT_TRAINING_ORIENTATION));
    }

    private static String applyScramble(String state, String scramble) {
        String result = state;
        for (String token : scramble.trim().split("\\s+")) {
            int move = parseMove(token);
            if (move >= 0) {
                result = Utils.applySmartCubeMove(result, move);
            }
        }
        return result;
    }

    private static String applyTrainingDisplayScramble(String state, String scramble) {
        String result = state;
        for (String token : scramble.trim().split("\\s+")) {
            int displayMove = parseMove(token);
            if (displayMove >= 0) {
                int physicalMove = Utils.unorientSmartCubeMove(displayMove, SmartCubeTraining.DEFAULT_TRAINING_ORIENTATION);
                result = Utils.applySmartCubeMove(result, physicalMove);
            }
        }
        return result;
    }

    private static int parseMove(String move) {
        String faces = "URFDLB";
        if (move == null || move.length() == 0) {
            return -1;
        }
        int face = faces.indexOf(move.charAt(0));
        if (face < 0) {
            return -1;
        }
        int suffix = 0;
        if (move.length() > 1) {
            if (move.charAt(1) == '2') {
                suffix = 1;
            } else if (move.charAt(1) == '\'') {
                suffix = 2;
            }
        }
        return face * 3 + suffix;
    }

    private static void assertFullSolveTrainingMode(int sub) {
        int scrambleIdx = SmartCubeTraining.CATEGORY_333_CFOP_BASE + sub;

        assertFalse(SmartCubeTraining.isStageCompleteMode(scrambleIdx));
        assertTrue(SmartCubeTraining.isComplete(scrambleIdx, SOLVED, 0));
    }
}
