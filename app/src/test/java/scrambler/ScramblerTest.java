package scrambler;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

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
}
