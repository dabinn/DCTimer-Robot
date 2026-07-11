package com.dctimer.util;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import cs.min2phase.Tools;

public final class GanRobotExecutor {
    private static final String TAG = "GanRobotExecutor";
    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final String ORIENTATION_PROBE_ROLLBACK = "F' D'";

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

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
                        GanRobotActivity.OrientationPlan orientationPlan = GanRobotActivity.runOrientationProbePlan(currentCubeState);
                        String remappedScramble = GanRobotActivity.remapAlgorithmWithFaceMap(effectiveScramble, orientationPlan.logicalToPhysicalFaceMap);
                        String finalAlgorithm = prependProbeRollback(remappedScramble);
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Manual plan (probe rollback): " + finalAlgorithm));
                        GanRobotActivity.executeAlgorithm(finalAlgorithm);
                        return;
                    } catch (Exception e) {
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Orientation probe failed, fallback direct execute"));
                    }
                }
                GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely(useMainTargetState
                        ? "No smart cube state -> direct execute mode"
                        : "Manual scramble -> direct execute mode"));
                GanRobotActivity.executeAlgorithm(effectiveScramble);
            } finally {
                GanRobotSessionState.setRobotMoving(false);
            }
        });
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

    private static String normalizeFacelet(String facelet) {
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
