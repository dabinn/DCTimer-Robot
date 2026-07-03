package com.dctimer.model;

import com.dctimer.util.Utils;

public final class SmartCubeTraining {
    public static final int GROUP_333_CFOP = 21;
    public static final int GROUP_333_ROUX = 22;
    public static final int SUB_OLL = 0;
    public static final int SUB_PLL = 1;
    public static final int SUB_LAST_LAYER = 2;
    public static final int SUB_F2L = 3;
    public static final int SUB_ZBLL = 4;
    public static final int SUB_ZZLL = 5;
    public static final int SUB_2GLL = 6;
    public static final int SUB_ELL = 7;
    public static final int SUB_ZBLS = 8;
    public static final int SUB_COLL = 9;
    public static final int SUB_OLLCP = 10;
    public static final int SUB_EOCP = 11;
    public static final int SUB_COUNT = 12;
    public static final int SUB_ROUX_CMLL = 0;
    public static final int SUB_ROUX_LSE = 1;
    public static final int SUB_ROUX_L10P = 2;
    public static final int ROUX_SUB_COUNT = 3;
    public static final int CATEGORY_333_CFOP_BASE = GROUP_333_CFOP << 5;
    public static final int CATEGORY_333_ROUX_BASE = GROUP_333_ROUX << 5;
    public static final int DEFAULT_TRAINING_ORIENTATION = 13;

    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final int[][] F2L_MASK = toEqus("----U-------RRRRRR---FFFFFFDDDDDDDDD---LLLLLL---BBBBBB");
    private static final int[][] OLL_MASK = toEqus("UUUUUUUUU---RRRRRR---FFFFFFDDDDDDDDD---LLLLLL---BBBBBB");
    private static final int[][] EOLL_MASK = toEqus("-U-UUU-U----RRRRRR---FFFFFFDDDDDDDDD---LLLLLL---BBBBBB");
    private static final int[][] CPLL_MASK = toEqus("UUUUUUUUUr-rRRRRRRf-fFFFFFFDDDDDDDDDl-lLLLLLLb-bBBBBBB");
    private static final int[][] ROUX_CMLL_MASK = toEqus("U-U---U-Ur-rRRRRRRf-fF-FF-FD-DD-DD-Dl-lLLLLLLb-bB-BB-B");
    private static final int[][] SOLVED_MASK = toEqus(SOLVED_FACELET);

    private SmartCubeTraining() {
    }

    public static boolean is333Cfop(int scrambleIdx) {
        return (scrambleIdx >> 5) == GROUP_333_CFOP;
    }

    public static boolean is333Roux(int scrambleIdx) {
        return (scrambleIdx >> 5) == GROUP_333_ROUX;
    }

    public static boolean isSmart333Training(int scrambleIdx) {
        return is333Cfop(scrambleIdx) || is333Roux(scrambleIdx);
    }

    public static boolean is333CfopSub(int scrambleIdx, int sub) {
        return is333Cfop(scrambleIdx) && (scrambleIdx & 0x1f) == sub;
    }

    public static boolean is333RouxSub(int scrambleIdx, int sub) {
        return is333Roux(scrambleIdx) && (scrambleIdx & 0x1f) == sub;
    }

    public static boolean isStageCompleteMode(int scrambleIdx) {
        int sub = scrambleIdx & 0x1f;
        return (is333Cfop(scrambleIdx) && (sub == SUB_OLL || sub == SUB_F2L || sub == SUB_ZBLS || sub == SUB_COLL || sub == SUB_OLLCP || sub == SUB_EOCP))
                || (is333Roux(scrambleIdx) && sub == SUB_ROUX_CMLL);
    }

    public static boolean isComplete(int scrambleIdx, String facelets, int orientationIndex) {
        if (!isSmart333Training(scrambleIdx) || facelets == null || facelets.length() == 0) {
            return false;
        }
        String oriented = Utils.orientFacelets(facelets, orientationIndex);
        if (is333Roux(scrambleIdx)) {
            switch (scrambleIdx & 0x1f) {
                case SUB_ROUX_CMLL:
                    return matchesMask(oriented, ROUX_CMLL_MASK);
                case SUB_ROUX_LSE:
                case SUB_ROUX_L10P:
                    return matchesMask(oriented, SOLVED_MASK);
                default:
                    return false;
            }
        }
        switch (scrambleIdx & 0x1f) {
            case SUB_OLL:
                return matchesMask(oriented, OLL_MASK);
            case SUB_PLL:
            case SUB_LAST_LAYER:
            case SUB_ZBLL:
            case SUB_ZZLL:
            case SUB_2GLL:
            case SUB_ELL:
                return matchesMask(oriented, SOLVED_MASK);
            case SUB_F2L:
                return matchesMask(oriented, F2L_MASK);
            case SUB_ZBLS:
                return matchesMask(oriented, EOLL_MASK);
            case SUB_COLL:
            case SUB_OLLCP:
                return matchesMask(oriented, CPLL_MASK);
            case SUB_EOCP:
                return matchesEOCP(oriented);
            default:
                return false;
        }
    }

    static boolean hasF2L(String facelets) {
        return matchesMask(facelets, F2L_MASK);
    }

    static boolean hasOLL(String facelets) {
        return matchesMask(facelets, OLL_MASK);
    }

    static boolean hasEOLL(String facelets) {
        return matchesMask(facelets, EOLL_MASK);
    }

    static boolean hasCPLL(String facelets) {
        return matchesMask(facelets, CPLL_MASK);
    }

    static boolean hasEOCP(String facelets) {
        return matchesEOCP(facelets);
    }

    static boolean hasRouxCMLL(String facelets) {
        return matchesMask(facelets, ROUX_CMLL_MASK);
    }

    private static boolean matchesEOCP(String facelets) {
        if (!matchesMask(facelets, EOLL_MASK)) {
            return false;
        }
        char u = facelets.charAt(4);
        return hasCornerColors(facelets, 8, 9, 20, u, facelets.charAt(13), facelets.charAt(22))
                && hasCornerColors(facelets, 6, 18, 38, u, facelets.charAt(22), facelets.charAt(40))
                && hasCornerColors(facelets, 0, 36, 47, u, facelets.charAt(40), facelets.charAt(49))
                && hasCornerColors(facelets, 2, 45, 11, u, facelets.charAt(49), facelets.charAt(13));
    }

    private static boolean hasCornerColors(String facelets, int a, int b, int c, char x, char y, char z) {
        return containsAll(facelets.charAt(a), facelets.charAt(b), facelets.charAt(c), x, y, z);
    }

    private static boolean containsAll(char a, char b, char c, char x, char y, char z) {
        return contains(a, b, c, x) && contains(a, b, c, y) && contains(a, b, c, z);
    }

    private static boolean contains(char a, char b, char c, char x) {
        return a == x || b == x || c == x;
    }

    private static boolean matchesMask(String facelets, int[][] mask) {
        if (facelets == null || facelets.length() < 54) {
            return false;
        }
        for (int[] equ : mask) {
            char color = facelets.charAt(equ[0]);
            for (int i = 1; i < equ.length; i++) {
                if (facelets.charAt(equ[i]) != color) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[][] toEqus(String mask) {
        int[][] buckets = new int[128][];
        int[] counts = new int[128];
        for (int i = 0; i < mask.length(); i++) {
            char color = mask.charAt(i);
            if (color == '-') {
                continue;
            }
            if (buckets[color] == null) {
                buckets[color] = new int[54];
            }
            buckets[color][counts[color]++] = i;
        }
        int groupCount = 0;
        for (int count : counts) {
            if (count > 1) {
                groupCount++;
            }
        }
        int[][] equs = new int[groupCount][];
        int out = 0;
        for (int color = 0; color < counts.length; color++) {
            if (counts[color] > 1) {
                equs[out] = new int[counts[color]];
                System.arraycopy(buckets[color], 0, equs[out], 0, counts[color]);
                out++;
            }
        }
        return equs;
    }
}
