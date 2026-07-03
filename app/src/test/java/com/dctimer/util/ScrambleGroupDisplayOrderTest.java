package com.dctimer.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ScrambleGroupDisplayOrderTest {
    @Test
    public void cfopGroupKeepsRealIndexButDisplaysAfter333() {
        int groupCount = 23;

        assertEquals(21, ScrambleGroupDisplay.GROUP_333_CFOP);
        assertEquals(3, ScrambleGroupDisplay.toDisplayPosition(ScrambleGroupDisplay.GROUP_333_CFOP, groupCount));
        assertEquals(ScrambleGroupDisplay.GROUP_333_CFOP, ScrambleGroupDisplay.toRealGroup(3, groupCount));
        assertEquals(2, ScrambleGroupDisplay.toDisplayPosition(ScrambleGroupDisplay.GROUP_333, groupCount));
        assertEquals(4, ScrambleGroupDisplay.toDisplayPosition(2, groupCount));
    }

    @Test
    public void namesFollowDisplayOrderWithoutChangingRealOrder() {
        String[] realNames = new String[23];
        for (int i = 0; i < realNames.length; i++) {
            realNames[i] = "group-" + i;
        }

        String[] displayNames = ScrambleGroupDisplay.toDisplayNames(realNames);

        assertArrayEquals(new String[] {"group-0", "group-1", "group-2", "group-22", "group-3"},
                new String[] {displayNames[0], displayNames[1], displayNames[2], displayNames[3], displayNames[4]});
    }
}
