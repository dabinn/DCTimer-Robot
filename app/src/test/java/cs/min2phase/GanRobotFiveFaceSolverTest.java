package cs.min2phase;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GanRobotFiveFaceSolverTest {
    @Test
    public void solvesSimpleStateWithoutU() {
        CubieCube cube = new CubieCube().move(3).move(7).move(10);
        String solution = GanRobotFiveFaceSolver.solve(Util.toFaceCube(cube), 2000L);
        assertNotNull(GanRobotFiveFaceSolver.getLastDebugStats(), solution);
        assertFalse(solution.matches(".*\\bU(?:2|')?\\b.*"));
        for (String token : solution.split(" ")) {
            for (int move = 0; move < Util.move2str.length; move++) {
                if (Util.move2str[move].equals(token)) {
                    cube = cube.move(move);
                    break;
                }
            }
        }
        assertEquals("UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB", Util.toFaceCube(cube));

        String dynamic = GanRobotFiveFaceSolver.solve(Util.toFaceCube(new CubieCube().move(3)), 2000L, 'D');
        assertNotNull(dynamic);
        assertFalse(dynamic.matches(".*\\bD(?:2|')?\\b.*"));
    }

    @Test
    public void solvesCapturedRobotStateWithoutForbiddenL() {
        assertCapturedStateSolved("LDFRUBBRLBLRBRUDBUDDULFRLLRDUFDDUUFRFURRLFLBBULDFBFBDF");
        assertCapturedStateSolved("LBRDUBURBRUFLRLBFFRDUUFFBDDULLUDRDFUFBFULFBRLDRDBBLLDR");
        assertCapturedStateSolved("DRFLUURFBRBRFRLFFFBLUUFDLUUDFLLDRBRLFBDBLRLDBUBRUBDDDU");
    }

    @Test
    public void supportsEveryForbiddenLogicalFace() {
        CubieCube scrambled = new CubieCube();
        int[] scramble = {0, 4, 8, 9, 13, 17, 3, 7, 11, 12, 16};
        for (int move : scramble) scrambled = scrambled.move(move);
        String facelets = Util.toFaceCube(scrambled);
        char[] forbiddenFaces = {'U', 'R', 'F', 'D', 'L', 'B'};
        for (char forbidden : forbiddenFaces) {
            String solution = GanRobotFiveFaceSolver.solve(facelets, 30000L, forbidden);
            assertNotNull("forbidden=" + forbidden + " " + GanRobotFiveFaceSolver.getLastDebugStats(), solution);
            assertFalse("forbidden=" + forbidden + " solution=" + solution,
                    solution.matches(".*\\b" + forbidden + "(?:2|')?\\b.*"));
            CubieCube result = new CubieCube(scrambled);
            for (String token : solution.split(" ")) {
                for (int move = 0; move < Util.move2str.length; move++) {
                    if (Util.move2str[move].equals(token)) {
                        result = result.move(move);
                        break;
                    }
                }
            }
            assertEquals("forbidden=" + forbidden + " solution=" + solution,
                    "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB",
                    Util.toFaceCube(result));
        }
    }

    private static void assertCapturedStateSolved(String facelets) {
        String solution = GanRobotFiveFaceSolver.solve(facelets, 30000L, 'L');
        assertNotNull(GanRobotFiveFaceSolver.getLastDebugStats(), solution);
        assertFalse(solution.matches(".*\\bL(?:2|')?\\b.*"));
        CubieCube cube = new CubieCube();
        assertEquals(0, Util.toCubieCube(facelets, cube));
        for (String token : solution.split(" ")) {
            for (int move = 0; move < Util.move2str.length; move++) {
                if (Util.move2str[move].equals(token)) {
                    cube = cube.move(move);
                    break;
                }
            }
        }
        String coordinateDebug = " twist=" + cube.getTwist()
                + " flip=" + cube.getFlip()
                + " corner=" + solver.Utils.get8Perm(cube.cp, 8)
                + " ep=" + java.util.Arrays.toString(cube.ep);
        assertEquals(facelets + " solution=" + solution + coordinateDebug,
                "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB", Util.toFaceCube(cube));
    }
}
