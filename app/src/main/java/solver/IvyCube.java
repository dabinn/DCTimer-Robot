package solver;

import java.util.Arrays;
import java.util.Random;

public class IvyCube {
    private static final short[][] pm = new short[360][4];
    private static final short[][] om = new short[81][4];
    private static final byte[] pd = new byte[360];
    private static final byte[] od = new byte[81];
    private static final int[] seq = new int[12];
    private static final String[] suff = {"'", ""};
    private static final Random r = new Random();

    static {
        init();
    }

    private static void init() {
        int[] arr = new int[6];
        for (int i = 0; i < 360; i++) {
            for (int j = 0; j < 4; j++) {
                Utils.idxToPerm(arr, i, 6, true);
                switch (j) {
                    case 0:
                        Utils.circle(arr, 0, 3, 1);
                        break;
                    case 1:
                        Utils.circle(arr, 0, 2, 4);
                        break;
                    case 2:
                        Utils.circle(arr, 1, 5, 2);
                        break;
                    case 3:
                        Utils.circle(arr, 3, 4, 5);
                        break;
                }
                pm[i][j] = (short) Utils.permToIdx(arr, 6, true);
            }
        }

        arr = new int[4];
        for (int i = 0; i < 81; i++) {
            for (int j = 0; j < 4; j++) {
                idxToOri(arr, i, false);
                arr[j]++;
                om[i][j] = (short) oriToIdx(arr, false);
            }
        }

        Arrays.fill(pd, (byte) -1);
        pd[0] = 0;
        Utils.createPrun(pd, 6, pm, 2);
        Arrays.fill(od, (byte) -1);
        od[0] = 0;
        Utils.createPrun(od, 6, om, 2);
    }

    private static boolean search(int p, int o, int d, int l) {
        if (d == 0) return p == 0 && o == 0;
        if (pd[p] > d || od[o] > d) return false;
        for (int k = 0; k < 4; k++) {
            if (k != l) {
                int cp = p, co = o;
                for (int m = 0; m < 2; m++) {
                    cp = pm[cp][k];
                    co = om[co][k];
                    if (search(cp, co, d - 1, k)) {
                        seq[d] = k << 1 | m;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static String scramble() {
        int p, o;
        do {
            p = r.nextInt(360);
            o = r.nextInt(81);
        } while ((p == 0 && o == 0) || search(p, o, 0, -1) || search(p, o, 1, -1));
        for (int d = 6; d < 12; d++) {
            if (search(p, o, d, -1)) {
                return moveToString(d);
            }
        }
        return "error";
    }

    private static String moveToString(int d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= d; i++) {
            int move = seq[i] >> 1;
            int pow = seq[i] & 1;
            if (sb.length() > 0) sb.append(' ');
            sb.append("RLUB".charAt(move)).append(suff[pow]);
        }
        return sb.toString();
    }

    private static int oriToIdx(int[] orientation, boolean zeroSum) {
        int idx = zeroSum ? 0 : orientation[0] % 3;
        for (int i = orientation.length - 1; i > 0; i--) {
            idx = idx * 3 + orientation[i] % 3;
        }
        return idx;
    }

    private static void idxToOri(int[] orientation, int idx, boolean zeroSum) {
        int parity = orientation.length * 3;
        for (int i = 1; i < orientation.length; i++) {
            orientation[i] = idx % 3;
            parity -= orientation[i];
            idx /= 3;
        }
        orientation[0] = zeroSum ? parity % 3 : idx % 3;
    }
}
