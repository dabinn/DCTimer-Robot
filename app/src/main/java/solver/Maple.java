package solver;

import java.util.Arrays;
import java.util.Random;

public class Maple {
    private static final short[][] CENTER_MOVE = new short[360][4];
    private static final byte[][] CORNER_PERM_MOVE = new byte[36][4];
    private static final short[][] CORNER_ORI_MOVE = new short[2187][4];
    private static final byte[] CENTER_DISTANCE = new byte[360];
    private static final byte[][] CORNER_DISTANCE = new byte[2187][36];
    private static final Random RANDOM = new Random();
    private static final String[] TURN = {"R", "U", "L", "B"};
    private static final String[] SUFF = {"'", ""};
    private static final int[] IMAGE = new int[30];

    private static String lastScramble = "";
    private static StringBuilder solution;

    static {
        init();
    }

    private static void init() {
        initMoveTables();
        initDistanceTables();
    }

    private static void initMoveTables() {
        int[] arr = new int[7];
        for (int i = 0; i < 360; i++) {
            for (int j = 0; j < 4; j++) {
                Utils.idxToPerm(arr, i, 6, true);
                switch (j) {
                    case 0:
                        Utils.circle(arr, 0, 2, 3);
                        break;
                    case 1:
                        Utils.circle(arr, 0, 3, 4);
                        break;
                    case 2:
                        Utils.circle(arr, 0, 4, 1);
                        break;
                    case 3:
                        Utils.circle(arr, 3, 5, 4);
                        break;
                }
                CENTER_MOVE[i][j] = (short) Utils.permToIdx(arr, 6, true);
            }
        }

        int[] arr2 = new int[3];
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 4; k++) {
                    Utils.idxToPerm(arr, i, 4, true);
                    Utils.idxToPerm(arr2, j, 3, true);
                    switch (k) {
                        case 0:
                            Utils.circle(arr, 2, 0, 3);
                            break;
                        case 1:
                            Utils.circle(arr, 0, 1, 3);
                            break;
                        case 2:
                            Utils.circle(arr, 1, 2, 3);
                            break;
                        case 3:
                            Utils.circle(arr2, 0, 2, 1);
                            break;
                    }
                    CORNER_PERM_MOVE[i * 3 + j][k] =
                            (byte) (Utils.permToIdx(arr, 4, true) * 3 + Utils.permToIdx(arr2, 3, true));
                }
            }
        }

        for (int i = 0; i < 2187; i++) {
            for (int j = 0; j < 4; j++) {
                Utils.idxToOri(arr, i, 7, false);
                switch (j) {
                    case 0:
                        Utils.circle(arr, 1, 3, 6);
                        arr[1] += 2;
                        arr[3] += 2;
                        arr[4]++;
                        arr[6] += 2;
                        break;
                    case 1:
                        Utils.circle(arr, 1, 2, 3);
                        arr[0]++;
                        arr[1] += 2;
                        arr[2] += 2;
                        arr[3] += 2;
                        break;
                    case 2:
                        Utils.circle(arr, 2, 6, 3);
                        arr[2] += 2;
                        arr[3] += 2;
                        arr[5]++;
                        arr[6] += 2;
                        break;
                    case 3:
                        Utils.circle(arr, 0, 5, 4);
                        arr[0] += 2;
                        arr[3]++;
                        arr[4] += 2;
                        arr[5] += 2;
                        break;
                }
                CORNER_ORI_MOVE[i][j] = (short) Utils.oriToIdx(arr, 7, false);
            }
        }
    }

    private static void initDistanceTables() {
        Arrays.fill(CENTER_DISTANCE, (byte) -1);
        CENTER_DISTANCE[0] = 0;
        for (int depth = 0; depth < 5; depth++) {
            for (int i = 0; i < 360; i++) {
                if (CENTER_DISTANCE[i] == depth) {
                    for (int m = 0; m < 4; m++) {
                        int p = i;
                        for (int n = 0; n < 2; n++) {
                            p = CENTER_MOVE[p][m];
                            if (CENTER_DISTANCE[p] == -1) {
                                CENTER_DISTANCE[p] = (byte) (depth + 1);
                            }
                        }
                    }
                }
            }
        }

        for (byte[] row : CORNER_DISTANCE) {
            Arrays.fill(row, (byte) -1);
        }
        CORNER_DISTANCE[0][0] = 0;
        for (int depth = 0; depth < 7; depth++) {
            for (int i = 0; i < 2187; i++) {
                for (int j = 0; j < 36; j++) {
                    if (CORNER_DISTANCE[i][j] == depth) {
                        for (int k = 0; k < 4; k++) {
                            int p = i;
                            int q = j;
                            for (int l = 0; l < 2; l++) {
                                p = CORNER_ORI_MOVE[p][k];
                                q = CORNER_PERM_MOVE[q][k];
                                if (CORNER_DISTANCE[p][q] == -1) {
                                    CORNER_DISTANCE[p][q] = (byte) (depth + 1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static String scramble() {
        lastScramble = "";
        int center = RANDOM.nextInt(360);
        int cornerPerm;
        int cornerOri;
        do {
            cornerPerm = RANDOM.nextInt(36);
            cornerOri = RANDOM.nextInt(2187);
        } while (CORNER_DISTANCE[cornerOri][cornerPerm] < 0);
        solution = new StringBuilder();
        for (int depth = 0; depth < 13; depth++) {
            if (search(center, cornerPerm, cornerOri, depth, -1)) {
                break;
            }
        }
        return solution.toString().trim();
    }

    private static boolean search(int center, int cornerPerm, int cornerOri, int depth, int lastMove) {
        if (depth == 0) {
            return CENTER_DISTANCE[center] == 0 && CORNER_DISTANCE[cornerOri][cornerPerm] == 0;
        }
        if (CENTER_DISTANCE[center] > depth || CORNER_DISTANCE[cornerOri][cornerPerm] > depth) {
            return false;
        }
        for (int move = 0; move < 4; move++) {
            if (move != lastMove) {
                int nextCenter = center;
                int nextPerm = cornerPerm;
                int nextOri = cornerOri;
                for (int power = 0; power < 2; power++) {
                    nextCenter = CENTER_MOVE[nextCenter][move];
                    nextPerm = CORNER_PERM_MOVE[nextPerm][move];
                    nextOri = CORNER_ORI_MOVE[nextOri][move];
                    if (search(nextCenter, nextPerm, nextOri, depth - 1, move)) {
                        int index = move;
                        int count = 8;
                        while (count > 0 && solution.length() > 0 && lastScramble.startsWith(TURN[index])) {
                            count--;
                            index = RANDOM.nextInt(3);
                        }
                        lastScramble = TURN[index] + SUFF[power] + " ";
                        solution.append(lastScramble);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static int[] image(String scramble) {
        initColor();
        if (scramble == null || scramble.trim().length() == 0) {
            return IMAGE.clone();
        }
        String[] moves = scramble.trim().split("\\s+");
        for (String move : moves) {
            if (move.length() == 0) {
                continue;
            }
            int moveIdx = "RULB".indexOf(move.charAt(0));
            if (moveIdx < 0) {
                continue;
            }
            move(moveIdx);
            if (move.length() > 1) {
                move(moveIdx);
            }
        }
        return IMAGE.clone();
    }

    private static void initColor() {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 6; j++) {
                IMAGE[j * 5 + i] = j;
            }
        }
    }

    private static void move(int turn) {
        switch (turn) {
            case 0:
                circle3(17, 22, 2);
                circle3(1, 16, 20);
                circle3(0, 15, 23);
                circle3(21, 4, 19);
                break;
            case 1:
                circle3(2, 22, 7);
                circle3(0, 21, 5);
                circle3(3, 20, 8);
                circle3(10, 16, 28);
                circle3(6, 1, 24);
                circle3(4, 23, 9);
                break;
            case 2:
                circle3(12, 2, 7);
                circle3(3, 6, 10);
                circle3(4, 5, 13);
                circle3(11, 0, 9);
                break;
            case 3:
                circle3(22, 27, 7);
                circle3(24, 28, 8);
                circle3(5, 23, 25);
                circle3(21, 29, 9);
                break;
        }
    }

    private static void circle3(int a, int b, int c) {
        int temp = IMAGE[a];
        IMAGE[a] = IMAGE[b];
        IMAGE[b] = IMAGE[c];
        IMAGE[c] = temp;
    }
}
