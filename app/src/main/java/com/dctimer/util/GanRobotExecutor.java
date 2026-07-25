package com.dctimer.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cs.min2phase.CubieCube;
import cs.min2phase.GanRobotFiveFaceSolver;
import cs.min2phase.Tools;
import cs.min2phase.Util;

public final class GanRobotExecutor {
    private static final String TAG = "GanRobotExecutor";
    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final String ORIENTATION_PROBE_ROLLBACK = "F' D'";
    private static final String CONNECTION_AXIS_CHECK_ALGORITHM = "R F D L B";
    private static final int ROBOT_IDLE_ZERO_STREAK_EXECUTE = 2;
    private static final int ROBOT_IDLE_ZERO_STREAK_PROBE = 1;
    private static final long ROBOT_IDLE_TIMEOUT_MS_EXECUTE = 20000L;
    private static final long ROBOT_IDLE_TIMEOUT_MS_PROBE = 5000L;
    private static final long ROBOT_STATUS_INITIAL_DELAY_MS_EXECUTE = 100L;
    private static final long ROBOT_STATUS_POLL_MS_EXECUTE = 200L;
    private static final long ROBOT_STATUS_POLL_MS_NEAR_IDLE = 100L;
    private static final long ROBOT_STATUS_POLL_MS_PROBE = 100L;
    private static final long ROBOT_FIVE_FACE_SOLVER_TIMEOUT_MS = 500L;
    private static final long SMART_CUBE_PROBE_TIMEOUT_MS = 2500L;
    private static final long SMART_CUBE_STATE_POLL_MS = 5L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Listener listener;
    private static boolean isSending;

    private GanRobotExecutor() {
    }

    public interface Listener {
        void onStatus(String message);
        void onToast(int messageResId);
        void onRemainingChanged(int remaining);
        void onSendingChanged();
    }

    private static class RobotSolvePlan {
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

    private static class RobotExecutionResult {
        public final boolean success;
        public final long executionTimeMs;

        RobotExecutionResult(boolean success, long executionTimeMs) {
            this.success = success;
            this.executionTimeMs = executionTimeMs;
        }
    }

    private static class OrientationPlan {
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
                executeStateToStatePlan(currentCubeState, SOLVED_FACELET, "Solve plan");
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
                        String targetState = resolveTargetStateForSubmit();
                        postStatus("Smart cube detected -> state-to-state mode");
                        executeStateToStatePlan(currentCubeState, targetState, "State plan");
                        return;
                    } catch (Exception e) {
                        postSendFailed(e);
                        return;
                    }
                }
                if (smartCubeAvailable) {
                    try {
                        postStatus("Manual scramble + smart cube -> orientation probe mode");
                        OrientationPlan orientationPlan = runOrientationProbePlan(currentCubeState);
                        postStatus("Orientation probe complete. Calculating...");
                        String remappedScramble = remapAlgorithmWithFaceMap(effectiveScramble, orientationPlan.logicalToPhysicalFaceMap);
                        String finalAlgorithm = prependProbeRollback(remappedScramble);
                        postStatus("Manual plan (probe rollback): " + finalAlgorithm);
                        executeAlgorithm(finalAlgorithm);
                        return;
                    } catch (Exception e) {
                        postStatus("Orientation probe failed, fallback direct execute");
                    }
                }
                postStatus(useMainTargetState
                        ? "No smart cube state -> direct execute mode"
                        : "Manual scramble -> direct execute mode");
                executeAlgorithm(effectiveScramble);
            } finally {
                GanRobotSessionState.setRobotMoving(false);
            }
        });
    }

    public static void runConnectionAxisCheck() {
        IO_EXECUTOR.execute(() -> {
            GanRobotSessionState.setRobotMoving(true);
            try {
                Log.i(TAG, "Connection axis check start: " + CONNECTION_AXIS_CHECK_ALGORITHM);
                postStatus("Connection axis check: " + CONNECTION_AXIS_CHECK_ALGORITHM);
                RobotExecutionResult result = executeAlgorithm(CONNECTION_AXIS_CHECK_ALGORITHM, false);
                if (result.success) {
                    Log.i(TAG, "Connection axis check completed in " + result.executionTimeMs + "ms");
                    postStatus("Connection axis check complete");
                }
            } finally {
                GanRobotSessionState.setRobotMoving(false);
            }
        });
    }

    private static RobotExecutionResult executeAlgorithm(String algorithm) {
        return executeAlgorithm(algorithm, true);
    }

    private static RobotExecutionResult executeAlgorithm(String algorithm, boolean notifySuccess) {
        if (TextUtils.isEmpty(algorithm) || TextUtils.isEmpty(algorithm.trim())) {
            if (notifySuccess) {
                postStatusAndToast(R.string.gan_robot_send_success);
            }
            return new RobotExecutionResult(true, 0L);
        }
        List<byte[]> packets;
        try {
            packets = GanRobotCodec.encodeScramble(algorithm);
        } catch (IllegalArgumentException e) {
            postStatus(getString(R.string.gan_robot_invalid_scramble, e.getMessage()));
            showToast(R.string.gan_robot_invalid_scramble_short);
            return new RobotExecutionResult(false, 0L);
        }
        if (packets.isEmpty()) {
            showToast(R.string.gan_robot_invalid_scramble_short);
            return new RobotExecutionResult(false, 0L);
        }
        long executeStartMs = SystemClock.elapsedRealtime();
        setSending(true);
        postStatus(getString(R.string.gan_robot_waiting_execution, packets.size()));
        try {
            for (int i = 0; i < packets.size(); i++) {
                ensureGattConnected();
                writeMovePacket(packets.get(i));
                waitRobotIdle();
                final int chunk = i + 1;
                postStatus("Chunk " + chunk + "/" + packets.size() + " done");
            }
            if (notifySuccess) {
                postStatusAndToast(R.string.gan_robot_send_success);
            }
            return new RobotExecutionResult(true, SystemClock.elapsedRealtime() - executeStartMs);
        } catch (Exception e) {
            Log.e(TAG, "execute scramble failed", e);
            postSendFailed(e);
            return new RobotExecutionResult(false, SystemClock.elapsedRealtime() - executeStartMs);
        } finally {
            setSending(false);
        }
    }

    private static void executeStateToStatePlan(String currentCubeState, String targetFacelet, String planLabel) throws Exception {
        long totalStartMs = SystemClock.elapsedRealtime();
        long probeStartMs = totalStartMs;
        OrientationPlan orientationPlan = runOrientationProbePlan(currentCubeState);
        Log.i(TAG, "Orientation map logical->physical: " + orientationPlan.logicalToPhysicalFaceMap);
        long probeTimeMs = SystemClock.elapsedRealtime() - probeStartMs;
        postStatus("Orientation probe complete. Calculating...");

        long pathStartMs = SystemClock.elapsedRealtime();
        char forbiddenLogicalFace = findLogicalFaceForPhysicalFace(orientationPlan.logicalToPhysicalFaceMap, 'U');
        Log.i(TAG, "Robot solver forbidden logical face: " + forbiddenLogicalFace + " (physical U axis)");
        RobotSolvePlan solvePlan = buildStateToStateAlgorithm(
                orientationPlan.currentStateAfterProbe,
                targetFacelet,
                forbiddenLogicalFace
        );
        long pathTimeMs = SystemClock.elapsedRealtime() - pathStartMs;

        String algorithm = remapAlgorithmWithFaceMap(solvePlan.algorithmLogical, orientationPlan.logicalToPhysicalFaceMap);
        int formulaMoveCount = countAlgorithmMoves(algorithm);
        int robotMoveCount = TextUtils.isEmpty(algorithm) ? 0 : GanRobotCodec.estimateRobotCost(algorithm);
        Log.i(TAG, "Robot solver physical formula (" + formulaMoveCount + " moves): " + algorithm);
        Log.i(TAG, "Robot solve plan: strategy=" + solvePlan.strategyLabel
                + ", searchMs=" + solvePlan.searchTimeMs
                + ", formulaMoves=" + formulaMoveCount
                + ", encodedMoves=" + robotMoveCount);
        postStatus("Solve strategy: " + solvePlan.strategyLabel + " (" + solvePlan.evaluatedCandidates + " candidates/" + solvePlan.searchTimeMs + "ms)");
        postStatus("Robot convert: " + formulaMoveCount + " -> " + robotMoveCount + " moves");
        postStatus(planLabel + ": " + algorithm);
        RobotExecutionResult executionResult = executeAlgorithm(algorithm);
        if (executionResult.success) {
            long totalTimeMs = SystemClock.elapsedRealtime() - totalStartMs;
            String timing = "Timing(ms) probe=" + probeTimeMs
                    + ", path=" + pathTimeMs
                    + ", move=" + executionResult.executionTimeMs
                    + ", total=" + totalTimeMs;
            Log.i(TAG, timing);
            postStatus(timing);
        }
    }

    private static void writeProbeMove(String move) throws Exception {
        List<byte[]> packets = GanRobotCodec.encodeScramble(move);
        if (packets.isEmpty()) {
            throw new IllegalStateException("Probe move is empty");
        }
        for (byte[] packet : packets) {
            ensureGattConnected();
            writeMovePacket(packet);
        }
    }

    private static OrientationPlan runOrientationProbePlan(String currentCubeState) throws Exception {
        String stateBeforeProbe = normalizeFacelet(currentCubeState);

        // The Robot treats the orientation probe as one formula. Sending D and F
        // as separate jobs causes an extra execution/voice transition.
        writeProbeMove("D F");
        String stateAfterD = waitForSmartCubeStateChange(stateBeforeProbe, SMART_CUBE_PROBE_TIMEOUT_MS, "D");
        char logicalFaceForPhysicalD = detectAppliedFaceClockwise(stateBeforeProbe, stateAfterD);
        if (logicalFaceForPhysicalD == 0) {
            throw new IllegalStateException("Cannot infer orientation from D probe");
        }

        String stateAfterF = waitForSmartCubeStateChange(stateAfterD, SMART_CUBE_PROBE_TIMEOUT_MS, "F");
        char logicalFaceForPhysicalF = detectAppliedFaceClockwise(stateAfterD, stateAfterF);
        if (logicalFaceForPhysicalF == 0) {
            throw new IllegalStateException("Cannot infer orientation from F probe");
        }
        waitRobotIdleForProbe();

        Map<Character, Character> logicalToPhysical = buildLogicalToPhysicalFaceMap(logicalFaceForPhysicalD, logicalFaceForPhysicalF);
        return new OrientationPlan(stateAfterF, logicalToPhysical);
    }

    private static String remapAlgorithmWithFaceMap(String algorithm, Map<Character, Character> logicalToPhysicalFaceMap) {
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

    public static void warmUpSolver() {
        IO_EXECUTOR.execute(() -> {
            long startMs = SystemClock.elapsedRealtime();
            GanRobotFiveFaceSolver.warmUp();
            Log.i(TAG, "Five-face solver warm-up completed in "
                    + (SystemClock.elapsedRealtime() - startMs) + "ms");
        });
    }

    public static void setListener(Listener listener) {
        GanRobotExecutor.listener = listener;
    }

    public static void clearListener(Listener listener) {
        if (GanRobotExecutor.listener == listener) {
            GanRobotExecutor.listener = null;
        }
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
        GanRobotBleClient.writeMovePacket(robotContext(), packet);
        postStatus("TX fff3: " + toHex(packet));
    }

    private static int waitRobotIdle() throws Exception {
        return waitRobotIdleInternal(
                ROBOT_IDLE_TIMEOUT_MS_EXECUTE,
                ROBOT_IDLE_ZERO_STREAK_EXECUTE,
                true,
                false,
                "execute"
        );
    }

    private static int waitRobotIdleForProbe() throws Exception {
        return waitRobotIdleInternal(
                ROBOT_IDLE_TIMEOUT_MS_PROBE,
                ROBOT_IDLE_ZERO_STREAK_PROBE,
                false,
                true,
                "probe"
        );
    }

    private static int waitRobotIdleInternal(
            long timeoutMs,
            int zeroStreakTarget,
            boolean logStatus,
            boolean probeConfirmedStarted,
            String waitLabel
    ) throws Exception {
        boolean seenNonZero = false;
        int zeroStreak = 0;
        long waitStartMs = SystemClock.elapsedRealtime();
        long deadline = waitStartMs + timeoutMs;
        long nextDelayMs = probeConfirmedStarted ? 0L : ROBOT_STATUS_INITIAL_DELAY_MS_EXECUTE;
        long totalReadTimeMs = 0L;
        int sampleCount = 0;
        int firstValue = -1;
        long firstSampleMs = -1L;
        int lastNonZeroValue = -1;
        long lastNonZeroMs = -1L;
        int lastValue = 0;
        int lastLoggedValue = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nextDelayMs > 0L) {
                Thread.sleep(nextDelayMs);
            }
            long readStartMs = SystemClock.elapsedRealtime();
            GanRobotBleClient.StatusSample sample = GanRobotBleClient.readMovesRemaining(robotContext());
            totalReadTimeMs += SystemClock.elapsedRealtime() - readStartMs;
            lastValue = sample.movesRemaining;
            sampleCount++;
            long sampleElapsedMs = SystemClock.elapsedRealtime() - waitStartMs;
            if (firstValue < 0) {
                firstValue = lastValue;
                firstSampleMs = sampleElapsedMs;
            }
            if (logStatus && lastValue != lastLoggedValue) {
                postRemainingChanged(lastValue);
                lastLoggedValue = lastValue;
            }
            if (lastValue > 0) {
                seenNonZero = true;
                zeroStreak = 0;
                lastNonZeroValue = lastValue;
                lastNonZeroMs = sampleElapsedMs;
            } else {
                zeroStreak++;
                if (seenNonZero || zeroStreak >= zeroStreakTarget) {
                    logRobotIdleWait(
                            waitLabel,
                            waitStartMs,
                            sampleCount,
                            firstValue,
                            firstSampleMs,
                            lastNonZeroValue,
                            lastNonZeroMs,
                            totalReadTimeMs,
                            false
                    );
                    return lastValue;
                }
            }
            if (probeConfirmedStarted) {
                nextDelayMs = ROBOT_STATUS_POLL_MS_PROBE;
            } else if (lastValue <= 2) {
                nextDelayMs = ROBOT_STATUS_POLL_MS_NEAR_IDLE;
            } else {
                nextDelayMs = ROBOT_STATUS_POLL_MS_EXECUTE;
            }
        }
        logRobotIdleWait(
                waitLabel,
                waitStartMs,
                sampleCount,
                firstValue,
                firstSampleMs,
                lastNonZeroValue,
                lastNonZeroMs,
                totalReadTimeMs,
                true
        );
        throw new IllegalStateException(getString(R.string.gan_robot_status_timeout));
    }

    private static void logRobotIdleWait(
            String waitLabel,
            long waitStartMs,
            int sampleCount,
            int firstValue,
            long firstSampleMs,
            int lastNonZeroValue,
            long lastNonZeroMs,
            long totalReadTimeMs,
            boolean timedOut
    ) {
        Log.i(TAG, "FFF2 wait " + waitLabel
                + ": elapsedMs=" + (SystemClock.elapsedRealtime() - waitStartMs)
                + ", samples=" + sampleCount
                + ", first=" + firstValue + "@" + firstSampleMs + "ms"
                + ", lastNonZero=" + lastNonZeroValue + "@" + lastNonZeroMs + "ms"
                + ", readMs=" + totalReadTimeMs
                + ", timedOut=" + timedOut);
    }

    private static void ensureGattConnected() {
        if (!GanRobotActivity.isConnectedAndReady()) {
            throw new IllegalStateException(getString(R.string.gan_robot_wait_connect));
        }
    }

    private static void setSending(final boolean sending) {
        isSending = sending;
        postSendingChanged();
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

    private static RobotSolvePlan buildStateToStateAlgorithm(
            String startFacelet,
            String targetFacelet,
            char forbiddenLogicalFace
    ) {
        String start = normalizeFacelet(startFacelet);
        String target = normalizeFacelet(targetFacelet);
        if (TextUtils.equals(start, target)) {
            return new RobotSolvePlan("", "already-at-target", 0, 0);
        }
        String scrambleFacelet = Tools.getScrambleFacelet(start, target);
        if (scrambleFacelet == null) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
        }
        Log.i(TAG, "Robot solver correction facelet: " + scrambleFacelet);
        RobotSolvePlan solvePlan = buildRobotOptimizedStateSolution(scrambleFacelet, forbiddenLogicalFace);
        String algorithm = solvePlan.algorithmLogical;
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
        }
        if (algorithm.startsWith("Error")) {
            throw new IllegalStateException(algorithm);
        }
        return new RobotSolvePlan(algorithm.trim(), solvePlan.strategyLabel, solvePlan.evaluatedCandidates, solvePlan.searchTimeMs);
    }

    private static int countAlgorithmMoves(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return 0;
        }
        String trimmed = algorithm.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private static String resolveTargetStateForSubmit() {
        String targetState = normalizeFacelet(GanRobotSessionState.getLatestMainTargetState());
        if (TextUtils.isEmpty(targetState)) {
            throw new IllegalStateException(getString(R.string.gan_robot_send_failed_short));
        }
        return targetState;
    }

    private static RobotSolvePlan buildRobotOptimizedStateSolution(String scrambleFacelet, char forbiddenLogicalFace) {
        long searchStartMs = SystemClock.elapsedRealtime();
        String noForbiddenAxisSolution = GanRobotFiveFaceSolver.solve(
                scrambleFacelet,
                ROBOT_FIVE_FACE_SOLVER_TIMEOUT_MS,
                forbiddenLogicalFace
        );
        long searchTimeMs = SystemClock.elapsedRealtime() - searchStartMs;
        Log.i(TAG, "Five-face search: forbidden=" + forbiddenLogicalFace
                + ", elapsedMs=" + searchTimeMs
                + ", " + GanRobotFiveFaceSolver.getLastDebugStats());
        boolean fiveFaceTimedOut = TextUtils.isEmpty(noForbiddenAxisSolution);
        boolean fiveFaceVerified = !fiveFaceTimedOut
                && isLogicalSolutionCorrect(scrambleFacelet, noForbiddenAxisSolution);
        if (!fiveFaceVerified) {
            if (fiveFaceTimedOut) {
                Log.w(TAG, "Five-face solver timed out; switching to fallback");
            } else {
                Log.w(TAG, "Five-face candidate rejected by logical state verification");
            }
            cs.min2phase.Search fallbackSearch = new cs.min2phase.Search();
            String fallbackSolution = fallbackSearch.solution(scrambleFacelet, 30, 50000L, 100L, 2);
            if (TextUtils.isEmpty(fallbackSolution) || fallbackSolution.startsWith("Error")) {
                throw new IllegalStateException("Robot five-face solver timed out and fallback solver failed");
            }
            Log.w(TAG, "Fallback solver used: moves=" + countAlgorithmMoves(fallbackSolution)
                    + ", elapsedMs=" + (SystemClock.elapsedRealtime() - searchStartMs));
            return new RobotSolvePlan(
                    fallbackSolution.trim(),
                    "five-face-timeout-fallback-" + forbiddenLogicalFace,
                    1,
                    SystemClock.elapsedRealtime() - searchStartMs
            );
        }
        String invertedSolution = invertSolverAlgorithm(noForbiddenAxisSolution);
        return new RobotSolvePlan(invertedSolution, "five-face-no-" + forbiddenLogicalFace, 1, searchTimeMs);
    }

    private static String invertSolverAlgorithm(String algorithm) {
        if (TextUtils.isEmpty(algorithm)) {
            return algorithm;
        }
        String[] tokens = algorithm.trim().split("\\s+");
        StringBuilder inverted = new StringBuilder(algorithm.length());
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (inverted.length() > 0) {
                inverted.append(' ');
            }
            String token = tokens[i];
            if (token.endsWith("2")) {
                inverted.append(token);
            } else if (token.endsWith("'")) {
                inverted.append(token, 0, token.length() - 1);
            } else {
                inverted.append(token).append('\'');
            }
        }
        return inverted.toString();
    }

    private static boolean isLogicalSolutionCorrect(String facelets, String algorithm) {
        if (TextUtils.isEmpty(algorithm) || algorithm.startsWith("Error")) {
            return false;
        }
        CubieCube cube = new CubieCube();
        if (Util.toCubieCube(facelets, cube) != 0) {
            return false;
        }
        for (String token : algorithm.trim().split("\\s+")) {
            int move = solverMoveIndex(token);
            if (move < 0) {
                return false;
            }
            cube = cube.move(move);
        }
        String resultFacelet = Util.toFaceCube(cube);
        boolean solved = SOLVED_FACELET.equals(resultFacelet);
        if (!solved) {
            Log.w(TAG, "Logical formula verification failed. result=" + resultFacelet);
        }
        return solved;
    }

    private static int solverMoveIndex(String token) {
        String[] moves = {
                "U", "U2", "U'", "R", "R2", "R'", "F", "F2", "F'",
                "D", "D2", "D'", "L", "L2", "L'", "B", "B2", "B'"
        };
        for (int i = 0; i < moves.length; i++) {
            if (moves[i].equals(token)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeFacelet(String facelet) {
        if (TextUtils.isEmpty(facelet)) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_need_cube_state));
        }
        String normalized = facelet.trim();
        if (normalized.length() != 54) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
        }
        if (!normalized.matches("^[URFDLB]{54}$")) {
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
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
            throw new IllegalStateException(getString(R.string.gan_robot_solve_invalid_cube_state));
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

    private static char findLogicalFaceForPhysicalFace(Map<Character, Character> logicalToPhysical, char physicalFace) {
        if (logicalToPhysical != null) {
            for (Map.Entry<Character, Character> entry : logicalToPhysical.entrySet()) {
                if (entry.getValue() != null && entry.getValue() == physicalFace) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalStateException("Orientation mapping missing physical face: " + physicalFace);
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
        postStatus(getString(messageResId));
        showToast(messageResId);
    }

    private static void postSendFailed(Exception e) {
        postStatus(getString(R.string.gan_robot_send_failed, e.getMessage()));
        showToast(R.string.gan_robot_send_failed_short);
    }

    private static void showToast(int messageResId) {
        Listener current = listener;
        if (current != null) {
            MAIN_HANDLER.post(() -> current.onToast(messageResId));
            return;
        }
        Context context = robotContext();
        if (context != null) {
            MAIN_HANDLER.post(() -> Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show());
        }
    }

    private static void postStatus(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        Listener current = listener;
        if (current != null) {
            MAIN_HANDLER.post(() -> current.onStatus(message));
        }
    }

    private static void postRemainingChanged(int remaining) {
        Listener current = listener;
        if (current != null) {
            MAIN_HANDLER.post(() -> current.onRemainingChanged(remaining));
        }
    }

    private static void postSendingChanged() {
        Listener current = listener;
        if (current != null) {
            MAIN_HANDLER.post(current::onSendingChanged);
        }
    }

    private static Context robotContext() {
        Context context = APP.getInstance();
        return context == null ? null : context.getApplicationContext();
    }

    private static String getString(int resId, Object... args) {
        Context context = robotContext();
        if (context == null) {
            return "";
        }
        return args == null || args.length == 0
                ? context.getString(resId)
                : context.getString(resId, args);
    }
}
