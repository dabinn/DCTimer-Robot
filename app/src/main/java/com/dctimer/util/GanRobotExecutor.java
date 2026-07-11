package com.dctimer.util;

import android.text.TextUtils;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GanRobotExecutor {
    private static final String SOLVED_FACELET = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";
    private static final String ORIENTATION_PROBE_ROLLBACK = "F' D'";

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private GanRobotExecutor() {
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
