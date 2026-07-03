package cs.min2phase;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ToolsTest {
    @Test
    public void randomZBLastSlotLeavesUnsolvedEdgesOrientationRandom() {
        Tools.setRandomSource(new Random(1234));
        boolean sawUnorientedOpenEdge = false;

        try {
            for (int i = 0; i < 20; i++) {
                CubieCube cube = new CubieCube();
                assertEquals(0, Util.toCubieCube(Tools.randomZBLastSlot(), cube));

                assertOriented(cube, 4, 5, 6, 7, 9, 10, 11);
                for (int edge : new int[]{0, 1, 2, 3, 8}) {
                    if (cube.eo[edge] != 0) {
                        sawUnorientedOpenEdge = true;
                    }
                }
            }
        } finally {
            Tools.setRandomSource(new Random());
        }

        assertTrue(sawUnorientedOpenEdge);
    }

    private static void assertOriented(CubieCube cube, int... edges) {
        for (int edge : edges) {
            assertEquals(0, cube.eo[edge]);
        }
    }
}
