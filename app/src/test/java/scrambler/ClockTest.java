package scrambler;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class ClockTest {
    @Test
    public void scramble_doesNotAppendWcaPinState() {
        Clock clock = new Clock();

        for (int i = 0; i < 100; i++) {
            String scramble = clock.scramble();
            String[] tokens = scramble.trim().split("\\s+");

            assertFalse(tokens[tokens.length - 1].matches("UR|DR|DL|UL"));
        }
    }

    @Test
    public void scramble_resetsPinsToDefaultState() {
        Clock clock = new Clock();

        clock.scrambleJaap(false);
        clock.scramble();

        for (int peg : clock.getPegs()) {
            assertEquals(1, peg);
        }
    }
}
