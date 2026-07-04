package com.dctimer.util;

import com.dctimer.model.SmartCubeTraining;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ScrambleSubitemDisplayOrderTest {
    @Test
    public void cfopSubitemsUseDisplayOrderWithoutChangingRealIndices() {
        String[] realNames = {
                "OLL",
                "PLL",
                "Last layer",
                "F2L",
                "ZBLL",
                "ZZLL",
                "2GLL",
                "ELL",
                "ZBLS",
                "COLL",
                "OLLCP",
                "EOCP",
                "CLL"
        };

        String[] displayNames = ScrambleSubitemDisplay.toDisplayNames(SmartCubeTraining.GROUP_333_CFOP, realNames);

        assertArrayEquals(new String[] {
                "F2L",
                "OLL",
                "PLL",
                "Last layer",
                "CLL",
                "ELL",
                "COLL",
                "EOCP",
                "2GLL",
                "OLLCP",
                "ZZLL",
                "ZBLS",
                "ZBLL"
        }, displayNames);
        assertEquals(0, ScrambleSubitemDisplay.toDisplayPosition(
                SmartCubeTraining.GROUP_333_CFOP, SmartCubeTraining.SUB_F2L, realNames.length));
        assertEquals(SmartCubeTraining.SUB_F2L, ScrambleSubitemDisplay.toRealSub(
                SmartCubeTraining.GROUP_333_CFOP, 0, realNames.length));
        assertEquals(12, ScrambleSubitemDisplay.toDisplayPosition(
                SmartCubeTraining.GROUP_333_CFOP, SmartCubeTraining.SUB_ZBLL, realNames.length));
        assertEquals(SmartCubeTraining.SUB_ZBLL, ScrambleSubitemDisplay.toRealSub(
                SmartCubeTraining.GROUP_333_CFOP, 12, realNames.length));
    }

    @Test
    public void nonCfopSubitemsKeepResourceOrder() {
        String[] realNames = {"CMLL", "LSE", "L10P"};

        assertArrayEquals(realNames, ScrambleSubitemDisplay.toDisplayNames(SmartCubeTraining.GROUP_333_ROUX, realNames));
        assertEquals(2, ScrambleSubitemDisplay.toDisplayPosition(
                SmartCubeTraining.GROUP_333_ROUX, SmartCubeTraining.SUB_ROUX_L10P, realNames.length));
        assertEquals(SmartCubeTraining.SUB_ROUX_L10P, ScrambleSubitemDisplay.toRealSub(
                SmartCubeTraining.GROUP_333_ROUX, 2, realNames.length));
    }
}
