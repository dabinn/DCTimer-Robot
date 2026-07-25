package cs.min2phase;

import java.util.Arrays;

import solver.Utils;

/** Generalized two-phase solver which excludes one logical face. */
public final class GanRobotFiveFaceSolver {
    private static final int MAX_DEPTH = 40;
    private static final long MAX_OPTIMIZATION_NANOS = 500_000_000L;
    private static final int[] FACT = {1, 1, 2, 6, 24, 120, 720, 5040, 40320};
    private static final int[] OPPOSITE = {3, 4, 5, 0, 1, 2};
    private static final int[] IDENTITY_EDGES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] IDENTITY_CORNERS = {0, 1, 2, 3, 4, 5, 6, 7};

    private static volatile SharedTables sharedTables;
    private static final Variant[] VARIANTS = new Variant[6];
    private static final OrientationTransform[] ORIENTATION_TRANSFORMS = new OrientationTransform[6];
    private static boolean symmetriesInitialized;
    private static volatile String lastDebugStats = "not-run";

    private GanRobotFiveFaceSolver() { }

    public static String solve(String facelets, long timeoutMs) {
        return solve(facelets, timeoutMs, 'U');
    }

    public static String solve(String facelets, long timeoutMs, char forbiddenFace) {
        CubieCube root = new CubieCube();
        if (Util.toCubieCube(facelets, root) != 0) return null;
        int forbidden = faceIndex(forbiddenFace);
        OrientationTransform transform = getOrientationTransform(forbidden);
        CubieCube canonicalRoot = transform.apply(root);
        SharedTables shared = getSharedTables();
        Variant variant = getVariant(shared, 0);
        String canonicalSolution = new Search(shared, variant, timeoutMs).solve(canonicalRoot);
        return canonicalSolution == null ? null : transform.toLogicalAlgorithm(canonicalSolution);
    }

    public static String getLastDebugStats() {
        return lastDebugStats;
    }

    public static void warmUp() {
        SharedTables shared = getSharedTables();
        getVariant(shared, 0);
        ensureSymmetries();
    }

    private static SharedTables getSharedTables() {
        SharedTables result = sharedTables;
        if (result == null) synchronized (GanRobotFiveFaceSolver.class) {
            result = sharedTables;
            if (result == null) sharedTables = result = new SharedTables();
        }
        return result;
    }

    private static Variant getVariant(SharedTables shared, int forbidden) {
        Variant result = VARIANTS[forbidden];
        if (result == null) synchronized (VARIANTS) {
            result = VARIANTS[forbidden];
            if (result == null) VARIANTS[forbidden] = result = new Variant(shared, forbidden);
        }
        return result;
    }

    private static OrientationTransform getOrientationTransform(int forbiddenFace) {
        OrientationTransform result = ORIENTATION_TRANSFORMS[forbiddenFace];
        if (result == null) synchronized (ORIENTATION_TRANSFORMS) {
            result = ORIENTATION_TRANSFORMS[forbiddenFace];
            if (result == null) {
                ensureSymmetries();
                result = OrientationTransform.find(forbiddenFace);
                ORIENTATION_TRANSFORMS[forbiddenFace] = result;
            }
        }
        return result;
    }

    private static void ensureSymmetries() {
        if (!symmetriesInitialized) {
            CubieCube.initSym();
            symmetriesInitialized = true;
        }
    }

    private static int faceIndex(char face) {
        switch (face) {
            case 'U': return 0;
            case 'R': return 1;
            case 'F': return 2;
            case 'D': return 3;
            case 'L': return 4;
            case 'B': return 5;
            default: throw new IllegalArgumentException("Unsupported forbidden face: " + face);
        }
    }

    private static int tableNext(int[] table, int index, int move) {
        return table[index * 18 + move];
    }

    private static final class OrientationTransform {
        final int symmetry;
        final int urfCount;
        final int[] canonicalToLogicalMove;

        OrientationTransform(int symmetry, int urfCount, int[] canonicalToLogicalMove) {
            this.symmetry = symmetry;
            this.urfCount = urfCount;
            this.canonicalToLogicalMove = canonicalToLogicalMove;
        }

        static OrientationTransform find(int forbiddenFace) {
            for (int symmetry = 0; symmetry < 16; symmetry++) {
                for (int urfCount = 0; urfCount < 3; urfCount++) {
                    int[] logicalToCanonical = new int[18];
                    int[] canonicalToLogical = new int[18];
                    Arrays.fill(canonicalToLogical, -1);
                    boolean valid = true;
                    for (int logicalMove = 0; logicalMove < 18; logicalMove++) {
                        CubieCube transformed = apply(CubieCube.moveCube[logicalMove], symmetry, urfCount);
                        int canonicalMove = findMove(transformed);
                        if (canonicalMove < 0 || canonicalToLogical[canonicalMove] >= 0) {
                            valid = false;
                            break;
                        }
                        logicalToCanonical[logicalMove] = canonicalMove;
                        canonicalToLogical[canonicalMove] = logicalMove;
                    }
                    if (valid && logicalToCanonical[forbiddenFace * 3] / 3 == 0) {
                        return new OrientationTransform(symmetry, urfCount, canonicalToLogical);
                    }
                }
            }
            throw new IllegalStateException("Cannot map forbidden face to canonical U: " + forbiddenFace);
        }

        CubieCube apply(CubieCube cube) {
            return apply(cube, symmetry, urfCount);
        }

        String toLogicalAlgorithm(String canonicalAlgorithm) {
            if (canonicalAlgorithm.isEmpty()) return canonicalAlgorithm;
            String[] tokens = canonicalAlgorithm.split(" ");
            StringBuilder result = new StringBuilder(canonicalAlgorithm.length());
            for (String token : tokens) {
                int canonicalMove = moveIndex(token);
                int logicalMove = canonicalToLogicalMove[canonicalMove];
                if (result.length() > 0) result.append(' ');
                result.append(Util.move2str[logicalMove]);
            }
            return result.toString();
        }

        private static CubieCube apply(CubieCube input, int symmetry, int urfCount) {
            CubieCube current = new CubieCube(input);
            if (symmetry != 0) {
                CubieCube conjugated = new CubieCube();
                CubieCube.CornConjugate(current, symmetry, conjugated);
                CubieCube.EdgeConjugate(current, symmetry, conjugated);
                current = conjugated;
            }
            for (int i = 0; i < urfCount; i++) current.URFConjugate();
            return current;
        }

        private static int findMove(CubieCube cube) {
            for (int move = 0; move < 18; move++) if (cube.equals(CubieCube.moveCube[move])) return move;
            return -1;
        }

        private static int moveIndex(String token) {
            for (int move = 0; move < Util.move2str.length; move++) {
                if (Util.move2str[move].equals(token)) return move;
            }
            throw new IllegalArgumentException("Unknown move: " + token);
        }
    }

    private static final class SharedTables {
        final int[] twist = new int[2187 * 18];
        final int[] flip = new int[2048 * 18];
        final int[] cornerPermutation = new int[40320 * 18];

        SharedTables() {
            for (int index = 0; index < 2187; index++) {
                CubieCube cube = new CubieCube();
                cube.setTwist(index);
                for (int move = 0; move < 18; move++) {
                    twist[index * 18 + move] = cube.move(move).getTwist();
                }
            }
            for (int index = 0; index < 2048; index++) {
                CubieCube cube = new CubieCube();
                cube.setFlip(index);
                for (int move = 0; move < 18; move++) {
                    flip[index * 18 + move] = cube.move(move).getFlip();
                }
            }
            for (int index = 0; index < 40320; index++) {
                CubieCube cube = new CubieCube();
                cube.setCPerm(index);
                for (int move = 0; move < 18; move++) {
                    cornerPermutation[index * 18 + move] = Utils.get8Perm(cube.move(move).cp, 8);
                }
            }
        }
    }

    private static final class Variant {
        final int forbiddenFace;
        final int axisFace;
        final int[] phaseOneMoves;
        final int[] phaseTwoMoves;
        final int[] slicePieces;
        final int[] nonSlicePieces;
        final int[] edgeGroupA;
        final int[] edgeGroupB;
        final int solvedSlicePosition;
        final int solvedSlicePermutation;
        final int[] slicePositionMove = new int[495 * 18];
        final int[] sliceFullMove = new int[11880 * 18];
        final int[] edgeGroupAMove = new int[11880 * 18];
        final int[] edgeGroupBMove = new int[11880 * 18];
        final int[] slicePermutationMove = new int[24 * 18];
        final int[] edgePermutationMove = new int[40320 * 18];
        final byte[] sliceFlipPruning;
        final byte[] sliceTwistPruning;
        final byte[] sliceCornerPruning;
        final byte[] sliceEdgePruning;

        Variant(SharedTables shared, int forbiddenFace) {
            this.forbiddenFace = forbiddenFace;
            axisFace = OPPOSITE[forbiddenFace];
            phaseOneMoves = createPhaseOneMoves(forbiddenFace);
            phaseTwoMoves = createPhaseTwoMoves(forbiddenFace, axisFace);
            slicePieces = createSlicePieces(forbiddenFace);
            nonSlicePieces = complement(slicePieces, 12);
            edgeGroupA = Arrays.copyOfRange(nonSlicePieces, 0, 4);
            edgeGroupB = Arrays.copyOfRange(nonSlicePieces, 4, 8);

            int solvedSlice = permutationIndex(IDENTITY_EDGES, slicePieces, true);
            solvedSlicePosition = solvedSlice / 24;
            solvedSlicePermutation = solvedSlice % 24;

            buildSliceMoves();
            buildPartialEdgeMove(edgeGroupA, edgeGroupAMove);
            buildPartialEdgeMove(edgeGroupB, edgeGroupBMove);
            buildSlicePermutationMove();
            buildEdgePermutationMove();

            sliceFlipPruning = buildPairPruning(
                    495, 2048, slicePositionMove, shared.flip,
                    solvedSlicePosition, 0, phaseOneMoves);
            sliceTwistPruning = buildPairPruning(
                    495, 2187, slicePositionMove, shared.twist,
                    solvedSlicePosition, 0, phaseOneMoves);
            sliceCornerPruning = buildPairPruning(
                    24, 40320, slicePermutationMove, shared.cornerPermutation,
                    solvedSlicePermutation, 0, phaseTwoMoves);
            sliceEdgePruning = buildPairPruning(
                    24, 40320, slicePermutationMove, edgePermutationMove,
                    solvedSlicePermutation, 0, phaseTwoMoves);
        }

        int slicePosition(int[] ep) {
            return permutationIndex(ep, slicePieces, true) / 24;
        }

        int sliceFull(int[] ep) {
            return permutationIndex(ep, slicePieces, true);
        }

        int slicePermutation(int[] ep) {
            return permutationIndex(ep, slicePieces, true) % 24;
        }

        int edgePermutation(int[] ep) {
            int[] permutation = new int[8];
            for (int i = 0; i < nonSlicePieces.length; i++) {
                int piece = ep[nonSlicePieces[i]];
                permutation[i] = indexOf(nonSlicePieces, piece);
                if (permutation[i] < 0) return -1;
            }
            return Utils.get8Perm(permutation, 8);
        }

        int mergeEdgePermutation(int groupA, int groupB) {
            int[] a = permutationVector(groupA, edgeGroupA, 12, false);
            int[] b = permutationVector(groupB, edgeGroupB, 12, false);
            int[] local = new int[8];
            for (int i = 0; i < 8; i++) {
                int position = nonSlicePieces[i];
                int piece = a[position] >= 0 ? a[position] : b[position];
                local[i] = indexOf(nonSlicePieces, piece);
                if (local[i] < 0) return -1;
            }
            return Utils.get8Perm(local, 8);
        }

        private void buildSliceMoves() {
            for (int full = 0; full < 11880; full++) {
                int[] vector = permutationVector(full, slicePieces, 12, true);
                for (int move = 0; move < 18; move++) {
                    sliceFullMove[full * 18 + move] =
                            permutationIndex(movePartial(vector, CubieCube.moveCube[move].ep), slicePieces, true);
                }
            }
            for (int position = 0; position < 495; position++) {
                for (int move = 0; move < 18; move++) {
                    slicePositionMove[position * 18 + move] =
                            tableNext(sliceFullMove, position * 24, move) / 24;
                }
            }
        }

        private void buildPartialEdgeMove(int[] affected, int[] table) {
            for (int index = 0; index < 11880; index++) {
                int[] vector = permutationVector(index, affected, 12, false);
                for (int move = 0; move < 18; move++) {
                    table[index * 18 + move] = permutationIndex(
                            movePartial(vector, CubieCube.moveCube[move].ep), affected, false);
                }
            }
        }

        private void buildSlicePermutationMove() {
            for (int permutation = 0; permutation < 24; permutation++) {
                for (int move : phaseTwoMoves) {
                    slicePermutationMove[permutation * 18 + move] =
                            tableNext(sliceFullMove, solvedSlicePosition * 24 + permutation, move) % 24;
                }
            }
        }

        private void buildEdgePermutationMove() {
            for (int index = 0; index < 40320; index++) {
                int[] localPermutation = new int[8];
                Utils.set8Perm(localPermutation, 8, index);
                int[] ep = IDENTITY_EDGES.clone();
                for (int i = 0; i < 8; i++) {
                    ep[nonSlicePieces[i]] = nonSlicePieces[localPermutation[i]];
                }
                for (int move : phaseTwoMoves) {
                    int[] moved = movePartial(ep, CubieCube.moveCube[move].ep);
                    int[] nextPermutation = new int[8];
                    boolean valid = true;
                    for (int i = 0; i < 8; i++) {
                        nextPermutation[i] = indexOf(nonSlicePieces, moved[nonSlicePieces[i]]);
                        valid &= nextPermutation[i] >= 0;
                    }
                    if (!valid) throw new IllegalStateException("Phase-two move left edge subgroup");
                    edgePermutationMove[index * 18 + move] = Utils.get8Perm(nextPermutation, 8);
                }
            }
        }
    }

    private static final class Search {
        final SharedTables shared;
        final Variant variant;
        final long searchStartNanos;
        final long hardDeadlineNanos;
        long deadlineNanos;
        final int[] path = new int[MAX_DEPTH];
        final int[] bestPath = new int[MAX_DEPTH];
        int bestLength = MAX_DEPTH + 1;
        int firstLength = -1;
        long firstSolutionMs = -1L;
        long bestSolutionMs = -1L;
        int bestPhaseOneDepth = -1;
        int solutionCount;
        long nodes;
        long phaseOneNodes;
        long phaseTwoNodes;
        long phaseTwoCalls;
        boolean timedOut;
        boolean stopAfterFirst = true;

        Search(SharedTables shared, Variant variant, long timeoutMs) {
            this.shared = shared;
            this.variant = variant;
            searchStartNanos = System.nanoTime();
            hardDeadlineNanos = searchStartNanos + Math.max(1L, timeoutMs) * 1_000_000L;
            deadlineNanos = hardDeadlineNanos;
        }

        String solve(CubieCube root) {
            if (isSolved(root)) {
                lastDebugStats = "solved=true, firstLength=0, bestLength=0"
                        + ", firstSolutionMs=0, bestSolutionMs=0, candidates=1"
                        + ", p1Nodes=0, p2Calls=0, p2Nodes=0, budgetExhausted=false";
                return "";
            }
            int twist = root.getTwist();
            int flip = root.getFlip();
            int sliceFull = variant.sliceFull(root.ep);
            int corner = Utils.get8Perm(root.cp, 8);
            int edgeA = permutationIndex(root.ep, variant.edgeGroupA, false);
            int edgeB = permutationIndex(root.ep, variant.edgeGroupB, false);
            int lowerBound = phaseOneDistance(twist, flip, sliceFull / 24);

            // First find any valid five-face solution. This preserves the solver's
            // short time-to-first-result before spending the rest of the budget on
            // reducing the number of physical Robot moves.
            for (int phaseOneDepth = lowerBound; phaseOneDepth <= 20; phaseOneDepth++) {
                if (phaseOne(twist, flip, sliceFull, corner, edgeA, edgeB,
                        phaseOneDepth, -1, 0)) {
                    break;
                }
                if (timedOut) {
                    lastDebugStats = stats(false);
                    return null;
                }
            }
            if (bestLength > MAX_DEPTH) {
                lastDebugStats = stats(false);
                return null;
            }

            // Continue enumerating phase-one endpoints while time remains. Phase
            // two is bounded by bestLength - 1, so every accepted candidate is
            // strictly shorter than the current result.
            stopAfterFirst = false;
            deadlineNanos = Math.min(hardDeadlineNanos, System.nanoTime() + MAX_OPTIMIZATION_NANOS);
            for (int phaseOneDepth = lowerBound;
                    phaseOneDepth <= 20 && phaseOneDepth < bestLength;
                    phaseOneDepth++) {
                phaseOne(twist, flip, sliceFull, corner, edgeA, edgeB,
                        phaseOneDepth, -1, 0);
                if (timedOut) break;
            }
            lastDebugStats = stats(true);
            return formatSolution();
        }

        boolean phaseOne(
                int twist, int flip, int sliceFull, int corner,
                int edgeA, int edgeB, int remaining, int lastMove, int pathIndex) {
            phaseOneNodes++;
            if (checkTimeout()) return false;
            int slice = sliceFull / 24;
            if (phaseOneDistance(twist, flip, slice) > remaining) return false;
            if (twist == 0 && flip == 0 && slice == variant.solvedSlicePosition) {
                phaseTwoCalls++;
                int edge = variant.mergeEdgePermutation(edgeA, edgeB);
                int slicePermutation = sliceFull % 24;
                if (edge >= 0 && tryPhaseTwo(corner, edge, slicePermutation, lastMove, pathIndex)
                        && stopAfterFirst) return true;
            }
            if (remaining == 0) return false;
            int lastFace = lastMove < 0 ? -1 : lastMove / 3;
            for (int move : variant.phaseOneMoves) {
                int face = move / 3;
                if (skipFace(face, lastFace)) continue;
                path[pathIndex] = move;
                if (phaseOne(
                        tableNext(shared.twist, twist, move),
                        tableNext(shared.flip, flip, move),
                        tableNext(variant.sliceFullMove, sliceFull, move),
                        tableNext(shared.cornerPermutation, corner, move),
                        tableNext(variant.edgeGroupAMove, edgeA, move),
                        tableNext(variant.edgeGroupBMove, edgeB, move),
                        remaining - 1,
                        move,
                        pathIndex + 1)) return true;
                if (timedOut) return false;
            }
            return false;
        }

        boolean tryPhaseTwo(int corner, int edge, int slice, int lastMove, int pathIndex) {
            int lowerBound = phaseTwoDistance(corner, edge, slice);
            int maxDepth = stopAfterFirst
                    ? MAX_DEPTH - pathIndex
                    : bestLength - pathIndex - 1;
            for (int depth = lowerBound; depth <= maxDepth; depth++) {
                if (phaseTwo(corner, edge, slice, depth, lastMove, pathIndex)) {
                    recordSolution(pathIndex + depth, pathIndex);
                    return true;
                }
                if (timedOut) return false;
            }
            return false;
        }

        boolean phaseTwo(int corner, int edge, int slice, int remaining, int lastMove, int pathIndex) {
            phaseTwoNodes++;
            if (checkTimeout()) return false;
            if (remaining == 0) {
                return corner == 0 && edge == 0 && slice == variant.solvedSlicePermutation;
            }
            if (phaseTwoDistance(corner, edge, slice) > remaining) return false;
            int lastFace = lastMove < 0 ? -1 : lastMove / 3;
            for (int move : variant.phaseTwoMoves) {
                int face = move / 3;
                if (skipFace(face, lastFace)) continue;
                path[pathIndex] = move;
                if (phaseTwo(
                        tableNext(shared.cornerPermutation, corner, move),
                        tableNext(variant.edgePermutationMove, edge, move),
                        tableNext(variant.slicePermutationMove, slice, move),
                        remaining - 1,
                        move,
                        pathIndex + 1)) return true;
                if (timedOut) return false;
            }
            return false;
        }

        int phaseOneDistance(int twist, int flip, int slice) {
            return Math.max(
                    pruningValue(variant.sliceTwistPruning, slice + 495 * twist),
                    pruningValue(variant.sliceFlipPruning, slice + 495 * flip));
        }

        int phaseTwoDistance(int corner, int edge, int slice) {
            return Math.max(
                    pruningValue(variant.sliceCornerPruning, slice + 24 * corner),
                    pruningValue(variant.sliceEdgePruning, slice + 24 * edge));
        }

        boolean checkTimeout() {
            if ((++nodes & 0x1fff) == 0 && System.nanoTime() >= deadlineNanos) timedOut = true;
            return timedOut;
        }

        String formatSolution() {
            StringBuilder result = new StringBuilder(bestLength * 3);
            for (int i = 0; i < bestLength; i++) {
                if (i > 0) result.append(' ');
                result.append(Util.move2str[bestPath[i]]);
            }
            return result.toString();
        }

        void recordSolution(int length, int phaseOneDepth) {
            if (length >= bestLength) return;
            long elapsedMs = (System.nanoTime() - searchStartNanos) / 1_000_000L;
            if (firstLength < 0) {
                firstLength = length;
                firstSolutionMs = elapsedMs;
            }
            bestLength = length;
            bestSolutionMs = elapsedMs;
            bestPhaseOneDepth = phaseOneDepth;
            solutionCount++;
            System.arraycopy(path, 0, bestPath, 0, length);
        }

        String stats(boolean solved) {
            return "solved=" + solved
                    + ", firstLength=" + firstLength
                    + ", bestLength=" + (bestLength <= MAX_DEPTH ? bestLength : -1)
                    + ", firstSolutionMs=" + firstSolutionMs
                    + ", bestSolutionMs=" + bestSolutionMs
                    + ", bestP1Depth=" + bestPhaseOneDepth
                    + ", candidates=" + solutionCount
                    + ", p1Nodes=" + phaseOneNodes
                    + ", p2Calls=" + phaseTwoCalls
                    + ", p2Nodes=" + phaseTwoNodes
                    + ", budgetExhausted=" + timedOut;
        }
    }

    private static int[] createPhaseOneMoves(int forbiddenFace) {
        int[] result = new int[15];
        int p = 0;
        for (int face = 0; face < 6; face++) if (face != forbiddenFace) {
            result[p++] = face * 3;
            result[p++] = face * 3 + 1;
            result[p++] = face * 3 + 2;
        }
        return result;
    }

    private static int[] createPhaseTwoMoves(int forbiddenFace, int axisFace) {
        int[] result = new int[7];
        int p = 0;
        for (int face = 0; face < 6; face++) {
            if (face == forbiddenFace) continue;
            if (face == axisFace) {
                result[p++] = face * 3;
                result[p++] = face * 3 + 1;
                result[p++] = face * 3 + 2;
            } else {
                result[p++] = face * 3 + 1;
            }
        }
        return result;
    }

    private static int[] createSlicePieces(int forbiddenFace) {
        if (forbiddenFace == 0 || forbiddenFace == 3) return new int[]{8, 9, 10, 11};
        if (forbiddenFace == 1 || forbiddenFace == 4) return new int[]{1, 3, 5, 7};
        return new int[]{0, 2, 4, 6};
    }

    private static byte[] buildPairPruning(
            int aSize, int bSize, int[] aMove, int[] bMove,
            int solvedA, int solvedB, int[] moves) {
        int size = aSize * bSize;
        byte[] distance = new byte[size];
        Arrays.fill(distance, (byte) -1);
        int[] queue = new int[size];
        int head = 0, tail = 1;
        int solved = solvedA + aSize * solvedB;
        queue[0] = solved;
        distance[solved] = 0;
        while (head < tail) {
            int index = queue[head++];
            int a = index % aSize;
            int b = index / aSize;
            byte nextDistance = (byte) (distance[index] + 1);
            for (int move : moves) {
                int next = tableNext(aMove, a, move) + aSize * tableNext(bMove, b, move);
                if (distance[next] == -1) {
                    distance[next] = nextDistance;
                    queue[tail++] = next;
                }
            }
        }
        return distance;
    }

    private static int pruningValue(byte[] table, int index) {
        int value = table[index];
        return value < 0 ? 0 : value;
    }

    private static boolean skipFace(int face, int lastFace) {
        return face == lastFace
                || (lastFace >= 0 && OPPOSITE[lastFace] == face && face > lastFace);
    }

    private static boolean isSolved(CubieCube cube) {
        return Arrays.equals(cube.cp, IDENTITY_CORNERS)
                && Arrays.equals(cube.ep, IDENTITY_EDGES)
                && cube.getTwist() == 0
                && cube.getFlip() == 0;
    }

    private static int[] complement(int[] selected, int size) {
        int[] result = new int[size - selected.length];
        int p = 0;
        for (int i = 0; i < size; i++) if (indexOf(selected, i) < 0) result[p++] = i;
        return result;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static int[] movePartial(int[] pieces, int[] movePermutation) {
        int[] result = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) result[i] = pieces[movePermutation[i]];
        return result;
    }

    private static int choose(int n, int k) {
        if (k < 0 || k > n) return 0;
        int result = 1;
        for (int i = 1; i <= k; i++) result = result * (n - k + i) / i;
        return result;
    }

    private static int permutationIndex(int[] pieces, int[] affected, boolean reversed) {
        int position = 0, k = 1;
        int[] values = new int[affected.length];
        if (reversed) {
            for (int n = pieces.length - 1; n >= 0; n--) if (indexOf(affected, pieces[n]) >= 0) {
                position += choose(pieces.length - 1 - n, k);
                values[affected.length - k] = pieces[n];
                k++;
            }
        } else {
            int p = 0;
            for (int n = 0; n < pieces.length; n++) if (indexOf(affected, pieces[n]) >= 0) {
                position += choose(n, k++);
                values[p++] = pieces[n];
            }
        }
        int permutation = 0;
        for (int i = values.length - 1; i > 0; i--) {
            int rotations = 0;
            while (values[i] != affected[i]) {
                rotateLeft(values, 0, i);
                rotations++;
            }
            permutation = (i + 1) * permutation + rotations;
        }
        return FACT[affected.length] * position + permutation;
    }

    private static int[] permutationVector(int index, int[] affectedInput, int size, boolean reversed) {
        int[] affected = affectedInput.clone();
        int base = FACT[affected.length];
        int position = index / base;
        int permutation = index % base;
        int[] pieces = new int[size];
        Arrays.fill(pieces, -1);
        for (int i = 1; i < affected.length; i++) {
            int rotations = permutation % (i + 1);
            permutation /= i + 1;
            while (rotations-- > 0) rotateRight(affected, 0, i);
        }
        int k = affected.length - 1;
        if (reversed) {
            for (int n = 0; n < size && k >= 0; n++) {
                int binomial = choose(size - 1 - n, k + 1);
                if (position - binomial >= 0) {
                    pieces[n] = affected[affected.length - 1 - k];
                    position -= binomial;
                    k--;
                }
            }
        } else {
            for (int n = size - 1; n >= 0 && k >= 0; n--) {
                int binomial = choose(n, k + 1);
                if (position - binomial >= 0) {
                    pieces[n] = affected[k];
                    position -= binomial;
                    k--;
                }
            }
        }
        return pieces;
    }

    private static void rotateLeft(int[] values, int left, int right) {
        int first = values[left];
        for (int i = left; i < right; i++) values[i] = values[i + 1];
        values[right] = first;
    }

    private static void rotateRight(int[] values, int left, int right) {
        int last = values[right];
        for (int i = right; i > left; i--) values[i] = values[i - 1];
        values[left] = last;
    }
}
