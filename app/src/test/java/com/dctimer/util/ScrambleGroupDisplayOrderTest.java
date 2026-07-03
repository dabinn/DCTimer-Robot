package com.dctimer.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ScrambleGroupDisplayOrderTest {
    @Test
    public void smartTrainingGroupsKeepRealIndicesButDisplayAfter333() {
        int groupCount = 24;

        assertEquals(21, ScrambleGroupDisplay.GROUP_333_CFOP);
        assertEquals(22, ScrambleGroupDisplay.GROUP_333_ROUX);
        assertEquals(3, ScrambleGroupDisplay.toDisplayPosition(ScrambleGroupDisplay.GROUP_333_CFOP, groupCount));
        assertEquals(ScrambleGroupDisplay.GROUP_333_CFOP, ScrambleGroupDisplay.toRealGroup(3, groupCount));
        assertEquals(4, ScrambleGroupDisplay.toDisplayPosition(ScrambleGroupDisplay.GROUP_333_ROUX, groupCount));
        assertEquals(ScrambleGroupDisplay.GROUP_333_ROUX, ScrambleGroupDisplay.toRealGroup(4, groupCount));
        assertEquals(2, ScrambleGroupDisplay.toDisplayPosition(ScrambleGroupDisplay.GROUP_333, groupCount));
        assertEquals(5, ScrambleGroupDisplay.toDisplayPosition(2, groupCount));
    }

    @Test
    public void namesFollowDisplayOrderWithoutChangingRealOrder() {
        String[] realNames = new String[24];
        for (int i = 0; i < realNames.length; i++) {
            realNames[i] = "group-" + i;
        }

        String[] displayNames = ScrambleGroupDisplay.toDisplayNames(realNames);

        assertArrayEquals(new String[] {"group-0", "group-1", "group-2", "group-22", "group-23", "group-3"},
                new String[] {displayNames[0], displayNames[1], displayNames[2], displayNames[3], displayNames[4], displayNames[5]});
    }
}
