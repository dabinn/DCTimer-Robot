package com.dctimer.util;

import com.dctimer.model.SmartCubeTraining;

public final class ScrambleSubitemDisplay {
    private static final int[] CFOP_DISPLAY_ORDER = {
            SmartCubeTraining.SUB_F2L,
            SmartCubeTraining.SUB_OLL,
            SmartCubeTraining.SUB_PLL,
            SmartCubeTraining.SUB_LAST_LAYER,
            SmartCubeTraining.SUB_CLL,
            SmartCubeTraining.SUB_ELL,
            SmartCubeTraining.SUB_COLL,
            SmartCubeTraining.SUB_EOCP,
            SmartCubeTraining.SUB_2GLL,
            SmartCubeTraining.SUB_OLLCP,
            SmartCubeTraining.SUB_ZZLL,
            SmartCubeTraining.SUB_ZBLS,
            SmartCubeTraining.SUB_ZBLL
    };

    private ScrambleSubitemDisplay() {
    }

    public static int[] getDisplaySubIndices(int realGroup, int subCount) {
        if (subCount <= 0) {
            return new int[0];
        }
        if (realGroup != SmartCubeTraining.GROUP_333_CFOP) {
            int[] subs = new int[subCount];
            for (int i = 0; i < subCount; i++) {
                subs[i] = i;
            }
            return subs;
        }
        int[] subs = new int[subCount];
        boolean[] used = new boolean[subCount];
        int position = 0;
        for (int sub : CFOP_DISPLAY_ORDER) {
            if (sub >= 0 && sub < subCount && !used[sub]) {
                subs[position++] = sub;
                used[sub] = true;
            }
        }
        for (int sub = 0; sub < subCount; sub++) {
            if (!used[sub]) {
                subs[position++] = sub;
            }
        }
        return subs;
    }

    public static int toDisplayPosition(int realGroup, int realSub, int subCount) {
        int[] subs = getDisplaySubIndices(realGroup, subCount);
        for (int i = 0; i < subs.length; i++) {
            if (subs[i] == realSub) {
                return i;
            }
        }
        return realSub >= 0 && realSub < subCount ? realSub : 0;
    }

    public static int toRealSub(int realGroup, int displayPosition, int subCount) {
        int[] subs = getDisplaySubIndices(realGroup, subCount);
        if (displayPosition < 0 || displayPosition >= subs.length) {
            return 0;
        }
        return subs[displayPosition];
    }

    public static String[] toDisplayNames(int realGroup, String[] realNames) {
        if (realNames == null) {
            return new String[0];
        }
        int[] subs = getDisplaySubIndices(realGroup, realNames.length);
        String[] names = new String[subs.length];
        for (int i = 0; i < subs.length; i++) {
            names[i] = realNames[subs[i]];
        }
        return names;
    }
}
