package com.dctimer.util;

import android.text.TextUtils;
import android.widget.Toast;

import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GanRobotExecutor {
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private GanRobotExecutor() {
    }

    public static void solveFromSmartCubeState() {
        if (!GanRobotActivity.isConnectedAndReady()) {
            GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show());
            return;
        }
        if (GanRobotActivity.isSending() || GanRobotSessionState.isRobotMoving()) {
            GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show());
            return;
        }
        IO_EXECUTOR.execute(() -> {
            GanRobotSessionState.setRobotMoving(true);
            try {
                String currentCubeState = GanRobotActivity.waitForRobotSmartCubeStateSnapshot(400L);
                if (TextUtils.isEmpty(currentCubeState)) {
                    GanRobotActivity.postOnMainThread(() -> {
                        GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_solve_requires_smart_cube));
                        Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_solve_requires_smart_cube, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                GanRobotActivity.executeStateToStatePlan(currentCubeState, GanRobotActivity.SOLVED_FACELET, "Solve plan");
            } catch (Exception e) {
                GanRobotActivity.postOnMainThread(() -> {
                    GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_failed, e.getMessage()));
                    Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
                });
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
                    GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_invalid_scramble_short, Toast.LENGTH_SHORT).show());
                    return;
                }
                String currentCubeState = GanRobotActivity.waitForRobotSmartCubeStateSnapshot(250L);
                boolean smartCubeAvailable = GanRobotActivity.isSmartCubeModeActive() && !TextUtils.isEmpty(currentCubeState);
                if (smartCubeAvailable && useMainTargetState) {
                    try {
                        String targetState = GanRobotActivity.resolveTargetStateForSubmit();
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Smart cube detected -> state-to-state mode"));
                        GanRobotActivity.executeStateToStatePlan(currentCubeState, targetState, "State plan");
                        return;
                    } catch (Exception e) {
                        GanRobotActivity.postOnMainThread(() -> {
                            GanRobotActivity.appendStatusSafely(GanRobotActivity.robotContext().getString(R.string.gan_robot_send_failed, e.getMessage()));
                            Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_failed_short, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                }
                if (smartCubeAvailable) {
                    try {
                        GanRobotActivity.postOnMainThread(() -> GanRobotActivity.appendStatusSafely("Manual scramble + smart cube -> orientation probe mode"));
                        GanRobotActivity.OrientationPlan orientationPlan = GanRobotActivity.runOrientationProbePlan(currentCubeState);
                        String remappedScramble = GanRobotActivity.remapAlgorithmWithFaceMap(effectiveScramble, orientationPlan.logicalToPhysicalFaceMap);
                        String finalAlgorithm = GanRobotActivity.prependProbeRollback(remappedScramble);
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
}
