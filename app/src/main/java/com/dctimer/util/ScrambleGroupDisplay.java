package com.dctimer.util;

public final class ScrambleGroupDisplay {
    public static final int WCA_GROUP = -1;
    public static final int GROUP_222 = 0;
    public static final int GROUP_333 = 1;
    public static final int GROUP_333_CFOP = 21;

    private ScrambleGroupDisplay() {
    }

    public static int[] getDisplayGroupIndices(int groupCount) {
        if (groupCount <= 0) {
            return new int[0];
        }
        int[] groups = new int[groupCount];
        int position = 0;
        groups[position++] = WCA_GROUP;
        int normalGroupCount = groupCount - 1;
        boolean hasCfopGroup = GROUP_333_CFOP < normalGroupCount;
        for (int realGroup = 0; realGroup < normalGroupCount; realGroup++) {
            if (hasCfopGroup && realGroup == GROUP_333_CFOP) {
                continue;
            }
            groups[position++] = realGroup;
            if (hasCfopGroup && realGroup == GROUP_333) {
                groups[position++] = GROUP_333_CFOP;
            }
        }
        return groups;
    }

    public static int toDisplayPosition(int realGroup, int groupCount) {
        int[] groups = getDisplayGroupIndices(groupCount);
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == realGroup) {
                return i;
            }
        }
        return realGroup == WCA_GROUP ? 0 : Math.max(0, realGroup + 1);
    }

    public static int toRealGroup(int displayPosition, int groupCount) {
        int[] groups = getDisplayGroupIndices(groupCount);
        if (displayPosition < 0 || displayPosition >= groups.length) {
            return WCA_GROUP;
        }
        return groups[displayPosition];
    }

    public static String[] toDisplayNames(String[] realNames) {
        if (realNames == null) {
            return new String[0];
        }
        int[] groups = getDisplayGroupIndices(realNames.length);
        String[] names = new String[groups.length];
        for (int i = 0; i < groups.length; i++) {
            names[i] = realNames[groups[i] + 1];
        }
        return names;
    }
}
