package com.dctimer.util;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import cs.min2phase.CubieCube;
import cs.min2phase.Tools;
import cs.min2phase.Util;

public final class GanRobotExecutor {
    private static final String TAG = "GanRobotExecutor";
    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final String ORIENTATION_PROBE_ROLLBACK = "F' D'";
    private static final int ROBOT_IDLE_ZERO_STREAK_EXECUTE = 5;
    private static final int ROBOT_IDLE_ZERO_STREAK_PROBE = 2;
    private static final long ROBOT_IDLE_TIMEOUT_MS_EXECUTE = 20000L;
    private static final long ROBOT_IDLE_TIMEOUT_MS_PROBE = 5000L;
    private static final long SMART_CUBE_PROBE_TIMEOUT_MS = 2500L;
    private static final long SMART_CUBE_STATE_POLL_MS = 5L;

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean isSending;
    private static String latestRemainingStatusLine;

    private GanRobotExecutor() {
    }

    public static class RobotSolvePlan {
        public final String algorithmLogical;
        public final String strategyLabel;
        public final int evaluatedCandidates;
        public final long searchTimeMs;

        RobotSolvePlan(String algorithmLogical, String strategyLabel, int evaluatedCandidates, long searchTimeMs) {
            this.algorithmLogical = algorithmLogical;
            this.strategyLabel = strategyLabel;
            this.evaluatedCandidates = evaluatedCandidates;
            this.searchTimeMs = searchTimeMs;
        }
    }

    private static class SolveCandidate {
        final String algorithm;
        final int cost;
        final int length;
        final int evaluatedCandidates;
        final String profileName;

        SolveCandidate(String algorithm, int cost, int length, int evaluatedCandidates, String profileName) {
            this.algorithm = algorithm;
            this.cost = cost;
            this.length = length;
            this.evaluatedCandidates = evaluatedCandidates;
            this.profileName = profileName;
        }
    }

    public static class RobotExecutionResult {
        public final boolean success;
        public final long executionTimeMs;

        RobotExecutionResult(boolean success, long executionTimeMs) {
            this.success = success;
            this.executionTimeMs = executionTimeMs;
        }
    }

    public static class OrientationPlan {
        public final String currentStateAfterProbe;
        public final Map<Character, Character> logicalToPhysicalFaceMap;

        OrientationPlan(String currentStateAfterProbe, Map<Character, Character> logicalToPhysicalFaceMap) {
            this.currentStateAfterProbe = currentStateAfterProbe;
            this.logicalToPhysicalFaceMap = logicalToPhysicalFaceMap;
        }
    }

    public static void solveFromSmartCubeState() {
        if (!GanRobotActivity.isConnectedAndReady()) {
            showToast(R.string.gan_robot_wait_connect);
            return;
        }
        if (GanRobotActivity.isSending() || GanRobotSessionState.isRobotMoving()) {
            showToast(R.string.gan_robot_send_in_progress);
            return;
        }
        IO_EXECUTOR.execute(() -> {
            GanRobotSessionState.setRobotMoving(true);
            try {
                String currentCubeState = GanRobotSessionState.waitForSmartCubeStateSnapshot(400L);
                if (TextUtils.isEmpty(currentCubeState)) {
                    postStatusAndToast(R.string.gan_robot_solve_requires_smart_cube);
                    return;
                }
                GanRobotActivity.executeStateToStatePlan(currentCubeState, SOLVED_FACELET, "Solve plan");
            } catch (Exception e) {
                postSendFailed(e);
            } finally {
                GanRobotSessionState.setRobotMoving(false);
            }
        });
    }

    public static void executeScramble(String scramble, boolean useMainTargetState) {
        IO_EXECUTOR.execute(() -> {
            GanRobotSessionState.setRobotMoving(true);
            try {
                String effectiveScramble = TextUtils.isEmpty(scramble)
                        ? GanRobotSessionState.getLatestMainScramble()
                        : scramble;
                if (TextUtils.isEmpty(effectiveScramble)) {
                    showToast(R.string.gan_robot_invalid_scramble_short);
                    return;
                }
                String currentCubeState = GanRobotSessionState.waitForSmartCubeStateSnapshot(250L);
                boolean smartCubeAvailable = isSmartCubeModeActive() && !TextUtils.isEmpty(currentCubeState);
                if (smartCubeAvailable && useMainTargetState) {
                    try {
                        String targetState = GanRobotActivity.resolveTargetStateForSubmit();
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Smart cube detected -> state-to-state mode"));
                        GanRobotActivity.executeStateToStatePlan(currentCubeState, targetState, "State plan");
                        return;
                    } catch (Exception e) {
                        postSendFailed(e);
                        return;
                    }
                }
                if (smartCubeAvailable) {
                    try {
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Manual scramble + smart cube -> orientation probe mode"));
                        OrientationPlan orientationPlan = runOrientationProbePlan(currentCubeState);
                        String remappedScramble = remapAlgorithmWithFaceMap(effectiveScramble, orientationPlan.logicalToPhysicalFaceMap);
                        String finalAlgorithm = prependProbeRollback(remappedScramble);
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Manual plan (probe rollback): " + finalAlgorithm));
                        executeAlgorithm(finalAlgorithm);
                        return;
                    } catch (Exception e) {
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Orientation probe failed, fallback direct execute"));
                    }
                }
                GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely(useMainTargetState
                        ? "No smart cube state -> direct execute mode"
                        : "Manual scramble -> direct execute mode"));
                executeAlgorithm(effectiveScramble);
            } finally {
                GanRobotSessionState.setRobotMoving(false);
            }
        });
    }

    public static RobotExecutionResult executeAlgorithm(String algorithm) {
        if (TextUtils.isEmpty(algorithm) || TextUtils.isEmpty(algorithm.trim())) {
            GanRobotActivity.postOnMainThread(() -> {
                GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_success));
                Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(true, 0L);
        }
        List<byte[]> packets;
        try {
            packets = GanRobotCodec.encodeScramble(algorithm);
        } catch (IllegalArgumentException e) {
            GanRobotActivity.postOnMainThread(() -> {
                GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_invalid_scramble, e.getMessage()));
                Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(false, 0L);
        }
        if (packets.isEmpty()) {
            showToast(R.string.gan_robot_invalid_scramble_short);
            return new RobotExecutionResult(false, 0L);
        }
        long executeStartMs = SystemClock.elapsedRealtime();
        setSending(true);
        latestRemainingStatusLine = null;
        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_waiting_execution, packets.size())));
        try {
            for (int i = 0; i < packets.size(); i++) {
                ensureGattConnected();
                writeMovePacket(packets.get(i));
                waitRobotIdle();
                final int chunk = i + 1;
                GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Chunk " + chunk + "/" + packets.size() + " done"));
            }
            GanRobotActivity.postOnMainThread(() -> {
                GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_success));
                Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_success, Toast.LENGTH_SHORT).show();
            });
            return new RobotExecutionResult(true, SystemClock.elapsedRealtime() - executeStartMs);
        } catch (Exception e) {
            Log.e(TAG, "execute scramble failed", e);
            postSendFailed(e);
            return new RobotExecutionResult(false, SystemClock.elapsedRealtime() - executeStartMs);
        } finally {
            setSending(false);
            latestRemainingStatusLine = null;
        }
    }

    public static void writeProbeMove(String move) throws Exception {
        List<byte[]> packets = GanRobotCodec.encodeScramble(move);
        if (packets.isEmpty()) {
            throw new IllegalStateException("Probe move is empty");
        }
        for (byte[] packet : packets) {
            ensureGattConnected();
            writeMovePacket(packet);
            waitRobotIdleForProbe();
        }
    }

    public static OrientationPlan runOrientationProbePlan(String currentCubeState) throws Exception {
        String stateBeforeProbe = normalizeFacelet(currentCubeState);

        writeProbeMove("D");
        String stateAfterD = waitForSmartCubeStateChange(stateBeforeProbe, SMART_CUBE_PROBE_TIMEOUT_MS, "D");
        char logicalFaceForPhysicalD = detectAppliedFaceClockwise(stateBeforeProbe, stateAfterD);
        if (logicalFaceForPhysicalD == 0) {
            throw new IllegalStateException("Cannot infer orientation from D probe");
        }

        writeProbeMove("F");
        String stateAfterF = waitForSmartCubeStateChange(stateAfterD, SMART_CUBE_PROBE_TIMEOUT_MS, "F");
        char logicalFaceForPhysicalF = detectAppliedFaceClockwise(stateAfterD, stateAfterF);
        if (logicalFaceForPhysicalF == 0) {
            throw new IllegalStateException("Cannot infer orientation from F probe");
        }

        Map<Character, Character> logicalToPhysical = buildLogicalToPhysicalFaceMap(logicalFaceForPhysicalD, logicalFaceForPhysicalF);
        return new OrientationPlan(stateAfterF, logicalToPhysical);
    }

    public static String remapAlgorithmWithFaceMap(String algorithm, Map<Character, Character> logicalToPhysicalFaceMap) {
        if (TextUtils.isEmpty(algorithm) || logicalToPhysicalFaceMap == null || logicalToPhysicalFaceMap.isEmpty()) {
            return algorithm;
        }
        String[] tokens = algorithm.trim().split("\\s+");
        StringBuilder builder = new StringBuilder(algorithm.length() + 8);
        for (String token : tokens) {
            if (TextUtils.isEmpty(token)) {
                continue;
            }
            char logicalFace = token.charAt(0);
            Character physicalFace = logicalToPhysicalFaceMap.get(logicalFace);
            if (physicalFace == null) {
                throw new IllegalStateException("Orientation remap missing face: " + logicalFace);
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(physicalFace);
            if (token.length() > 1) {
                builder.append(token.substring(1));
            }
        }
        return builder.toString();
    }

    public static boolean isSending() {
        return isSending;
    }

    public static String getLatestRemainingStatusLine() {
        return latestRemainingStatusLine;
    }

    public static void setLatestRemainingStatusLine(String statusLine) {
        latestRemainingStatusLine = statusLine;
    }

    private static boolean isSmartCubeModeActive() {
        return APP.enterTime == 3;
    }

    private static String prependProbeRollback(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return ORIENTATION_PROBE_ROLLBACK;
        }
        return ORIENTATION_PROBE_ROLLBACK + " " + algorithm.trim();
    }

    private static void writeMovePacket(byte[] packet) throws Exception {
        GanRobotBleClient.writeMovePacket(GanRobotActivity.robotContext(), packet);
        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("TX fff3: " + toHex(packet)));
    }

    private static int waitRobotIdle() throws Exception {
        return waitRobotIdleInternal(ROBOT_IDLE_TIMEOUT_MS_EXECUTE, ROBOT_IDLE_ZERO_STREAK_EXECUTE, true);
    }

    private static int waitRobotIdleForProbe() throws Exception {
        return waitRobotIdleInternal(ROBOT_IDLE_TIMEOUT_MS_PROBE, ROBOT_IDLE_ZERO_STREAK_PROBE, false);
    }

    private static int waitRobotIdleInternal(long timeoutMs, int zeroStreakTarget, boolean logStatus) throws Exception {
        boolean seenNonZero = false;
        int zeroStreak = 0;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int lastValue = 0;
        int lastLoggedValue = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            GanRobotBleClient.StatusSample sample = GanRobotBleClient.readMovesRemaining(GanRobotActivity.robotContext());
            lastValue = sample.movesRemaining;
            if (logStatus && lastValue != lastLoggedValue) {
                final int currentValue = lastValue;
                GanRobotActivity.postOnMainThread(() -> GanRobotActivity.upsertRemainingStatusSafely(currentValue));
                lastLoggedValue = lastValue;
            }
            if (lastValue > 0) {
                seenNonZero = true;
                zeroStreak = 0;
            } else {
                zeroStreak++;
                if (seenNonZero || zeroStreak >= zeroStreakTarget) {
                    return lastValue;
                }
            }
        }
        throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_status_timeout));
    }

    private static void ensureGattConnected() {
        if (!GanRobotActivity.isConnectedAndReady()) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_wait_connect));
        }
    }

    private static void setSending(final boolean sending) {
        isSending = sending;
        GanRobotActivity.notifyConnectionUiChanged();
    }

    private static String toHex(byte[] value) {
        if (value == null || value.length == 0) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(java.util.Locale.US, "%02X", value[i] & 0xff));
        }
        return builder.toString();
    }

    public static RobotSolvePlan buildStateToStateAlgorithm(String startFacelet, String targetFacelet) {
        String start = normalizeFacelet(startFacelet);
        String target = normalizeFacelet(targetFacelet);
        if (TextUtils.equals(start, target)) {
            return new RobotSolvePlan("", "already-at-target", 0, 0);
        }
        String scrambleFacelet = Tools.getScrambleFacelet(start, target);
        if (scrambleFacelet == null) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_failed_short));
        }
        RobotSolvePlan solvePlan = buildRobotOptimizedStateSolution(scrambleFacelet);
        String algorithm = solvePlan.algorithmLogical;
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_failed_short));
        }
        if (algorithm.startsWith("Error")) {
            throw new IllegalStateException(algorithm);
        }
        return new RobotSolvePlan(algorithm.trim(), solvePlan.strategyLabel, solvePlan.evaluatedCandidates, solvePlan.searchTimeMs);
    }

    public static int countAlgorithmMoves(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return 0;
        }
        String trimmed = algorithm.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private static RobotSolvePlan buildRobotOptimizedStateSolution(String scrambleFacelet) {
        long searchStartMs = SystemClock.elapsedRealtime();
        final long totalTimeBudgetMs = 460L;
        ExecutorService solverPool = Executors.newFixedThreadPool(4);
        SolveCandidate bestCandidate = null;
        int evaluatedCandidates = 0;
        try {
            List<Callable<SolveCandidate>> tasks = new ArrayList<>();
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "fast",
                    26,
                    15000L,
                    0L,
                    2,
                    4,
                    180L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "balance",
                    28,
                    26000L,
                    50L,
                    2,
                    5,
                    220L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "deepA",
                    30,
                    45000L,
                    100L,
                    2,
                    6,
                    260L
            ));
            tasks.add(() -> runFallbackSearchProfile(
                    scrambleFacelet,
                    "deepB",
                    30,
                    50000L,
                    100L,
                    2,
                    8,
                    300L
            ));
            List<Future<SolveCandidate>> futures = solverPool.invokeAll(tasks, totalTimeBudgetMs, TimeUnit.MILLISECONDS);
            for (Future<SolveCandidate> future : futures) {
                if (future == null || !future.isDone() || future.isCancelled()) {
                    continue;
                }
                SolveCandidate candidate = future.get();
                if (candidate == null || TextUtils.isEmpty(candidate.algorithm) || candidate.algorithm.startsWith("Error")) {
                    continue;
                }
                evaluatedCandidates += candidate.evaluatedCandidates;
                if (bestCandidate == null
                        || candidate.cost < bestCandidate.cost
                        || (candidate.cost == bestCandidate.cost && candidate.length < bestCandidate.length)) {
                    bestCandidate = candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parallel fallback search failed, fallback to single profile", e);
        } finally {
            solverPool.shutdownNow();
        }

        long searchTimeMs = SystemClock.elapsedRealtime() - searchStartMs;
        if (bestCandidate != null) {
            return new RobotSolvePlan(
                    bestCandidate.algorithm,
                    "fallback-parallel-" + bestCandidate.profileName,
                    Math.max(evaluatedCandidates, bestCandidate.evaluatedCandidates),
                    searchTimeMs
            );
        }

        SolveCandidate single = runFallbackSearchProfile(
                scrambleFacelet,
                "single",
                30,
                50000L,
                100L,
                2,
                12,
                350L
        );
        searchTimeMs = SystemClock.elapsedRealtime() - searchStartMs;
        return new RobotSolvePlan(single.algorithm, "fallback-cost-optimized", single.evaluatedCandidates, searchTimeMs);
    }

    private static SolveCandidate runFallbackSearchProfile(
            String scrambleFacelet,
            String profileName,
            int maxDepth,
            long probeMax,
            long probeMin,
            int verbose,
            int maxCandidateChecks,
            long maxSearchTimeMs
    ) {
        long startMs = SystemClock.elapsedRealtime();
        cs.min2phase.Search search = new cs.min2phase.Search();
        String best = search.solution(scrambleFacelet, maxDepth, probeMax, probeMin, verbose);
        int evaluated = 1;
        if (TextUtils.isEmpty(best) || best.startsWith("Error")) {
            return new SolveCandidate(best, Integer.MAX_VALUE, Integer.MAX_VALUE, evaluated, profileName);
        }
        best = best.trim();
        int bestCost = GanRobotCodec.estimateRobotCost(best);
        int bestLength = countAlgorithmMoves(best);
        for (int i = 0; i < maxCandidateChecks; i++) {
            if (SystemClock.elapsedRealtime() - startMs >= maxSearchTimeMs) {
                break;
            }
            String candidate = search.next(probeMax, probeMin, verbose);
            if (candidate == null || candidate.startsWith("Error")) {
                break;
            }
            evaluated++;
            candidate = candidate.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            int candidateCost = GanRobotCodec.estimateRobotCost(candidate);
            int candidateLength = countAlgorithmMoves(candidate);
            if (candidateCost < bestCost || (candidateCost == bestCost && candidateLength < bestLength)) {
                best = candidate;
                bestCost = candidateCost;
                bestLength = candidateLength;
            }
        }
        return new SolveCandidate(best, bestCost, bestLength, evaluated, profileName);
    }

    public static String normalizeFacelet(String facelet) {
        if (TextUtils.isEmpty(facelet)) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_solve_need_cube_state));
        }
        String normalized = facelet.trim();
        if (normalized.length() != 54) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        if (!normalized.matches("^[URFDLB]{54}$")) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        return normalized;
    }

    private static String waitForSmartCubeStateChange(String previousState, long timeoutMs, String probeName) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String prev = normalizeFacelet(previousState);
        while (SystemClock.elapsedRealtime() < deadline) {
            String now = GanRobotSessionState.getLatestSmartCubeState();
            if (!TextUtils.isEmpty(now)) {
                String normalizedNow = normalizeFacelet(now);
                if (!TextUtils.equals(prev, normalizedNow)) {
                    return normalizedNow;
                }
            }
            Thread.sleep(SMART_CUBE_STATE_POLL_MS);
        }
        throw new IllegalStateException("Smart cube probe timeout on " + probeName);
    }

    private static char detectAppliedFaceClockwise(String beforeState, String afterState) {
        char[] faces = new char[] {'U', 'R', 'F', 'D', 'L', 'B'};
        for (char face : faces) {
            String transformed = applyFaceClockwise(beforeState, face);
            if (TextUtils.equals(transformed, afterState)) {
                return face;
            }
        }
        return 0;
    }

    private static String applyFaceClockwise(String facelet, char face) {
        CubieCube cube = new CubieCube();
        if (Util.toCubieCube(facelet, cube) != 0) {
            throw new IllegalStateException(GanRobotActivity.robotContext().getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        int moveIndex = toClockwiseMoveIndex(face);
        CubieCube moved = cube.move(moveIndex);
        return Util.toFaceCube(moved);
    }

    private static int toClockwiseMoveIndex(char face) {
        switch (face) {
            case 'U':
                return 0;
            case 'R':
                return 3;
            case 'F':
                return 6;
            case 'D':
                return 9;
            case 'L':
                return 12;
            case 'B':
                return 15;
            default:
                throw new IllegalArgumentException("Unsupported face: " + face);
        }
    }

    private static Map<Character, Character> buildLogicalToPhysicalFaceMap(char logicalForPhysicalD, char logicalForPhysicalF) {
        int[] physicalUp = negate(faceToVector(logicalForPhysicalD));
        int[] physicalFront = faceToVector(logicalForPhysicalF);
        if (dot(physicalUp, physicalFront) != 0) {
            throw new IllegalStateException("Invalid orientation probe result");
        }
        int[] physicalRight = cross(physicalUp, physicalFront);
        if (norm1(physicalRight) != 1) {
            throw new IllegalStateException("Invalid orientation probe basis");
        }

        Map<Character, Character> physicalToLogical = new HashMap<>();
        physicalToLogical.put('U', vectorToFace(physicalUp));
        physicalToLogical.put('D', vectorToFace(negate(physicalUp)));
        physicalToLogical.put('F', vectorToFace(physicalFront));
        physicalToLogical.put('B', vectorToFace(negate(physicalFront)));
        physicalToLogical.put('R', vectorToFace(physicalRight));
        physicalToLogical.put('L', vectorToFace(negate(physicalRight)));

        Map<Character, Character> logicalToPhysical = new HashMap<>();
        for (Map.Entry<Character, Character> entry : physicalToLogical.entrySet()) {
            logicalToPhysical.put(entry.getValue(), entry.getKey());
        }
        return logicalToPhysical;
    }

    private static int[] faceToVector(char face) {
        switch (face) {
            case 'U':
                return new int[] {0, 1, 0};
            case 'D':
                return new int[] {0, -1, 0};
            case 'F':
                return new int[] {0, 0, 1};
            case 'B':
                return new int[] {0, 0, -1};
            case 'R':
                return new int[] {1, 0, 0};
            case 'L':
                return new int[] {-1, 0, 0};
            default:
                throw new IllegalArgumentException("Unknown face " + face);
        }
    }

    private static char vectorToFace(int[] vector) {
        if (vector[0] == 0 && vector[1] == 1 && vector[2] == 0) return 'U';
        if (vector[0] == 0 && vector[1] == -1 && vector[2] == 0) return 'D';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == 1) return 'F';
        if (vector[0] == 0 && vector[1] == 0 && vector[2] == -1) return 'B';
        if (vector[0] == 1 && vector[1] == 0 && vector[2] == 0) return 'R';
        if (vector[0] == -1 && vector[1] == 0 && vector[2] == 0) return 'L';
        throw new IllegalArgumentException("Invalid vector");
    }

    private static int[] negate(int[] v) {
        return new int[] {-v[0], -v[1], -v[2]};
    }

    private static int dot(int[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static int[] cross(int[] a, int[] b) {
        return new int[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static int norm1(int[] v) {
        return Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]);
    }

    private static void postStatusAndToast(int messageResId) {
        GanRobotActivity.postOnMainThread(() -> {
            GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(messageResId));
            Toast.makeText(GanRobotActivity.robotContext(), messageResId, Toast.LENGTH_SHORT).show();
        });
    }

    private static void postSendFailed(Exception e) {
        GanRobotActivity.postOnMainThread(() -> {
            GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_failed, e.getMessage()));
            Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
        });
    }

    private static void showToast(int messageResId) {
        GanRobotActivity.postOnMainThread(() ->
                Toast.makeText(GanRobotActivity.robotContext(), messageResId, Toast.LENGTH_SHORT).show());
    }
}
