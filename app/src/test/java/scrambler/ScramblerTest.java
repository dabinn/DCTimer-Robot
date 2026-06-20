package scrambler;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ScramblerTest {
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
}
