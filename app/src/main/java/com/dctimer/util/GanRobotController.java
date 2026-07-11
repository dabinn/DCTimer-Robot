package com.dctimer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GanRobotController {
    public static final int ACTION_NONE = 0;
    public static final int ACTION_SOLVE = 1;
    public static final int ACTION_SCRAMBLE = 2;

    static final String PREF_NAME = "dctimer";
    public static final String PREF_KEY_BUTTON_ACTION = "ganrobot_button_action";

    private static int robotButtonAction = ACTION_SOLVE;
    private static boolean prefsLoaded = false;
    static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private GanRobotController() { }

    public static synchronized void setRobotButtonAction(int action) {
        robotButtonAction = action;
        prefsLoaded = true;
    }

    public static synchronized int getRobotButtonAction() {
        if (!prefsLoaded) {
            Context context = APP.getInstance();
            if (context != null) {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                robotButtonAction = prefs.getInt(PREF_KEY_BUTTON_ACTION, ACTION_SOLVE);
                prefsLoaded = true;
            }
        }
        return robotButtonAction;
    }

    public static void solveFromSmartCubeState() {
        if (!GanRobotActivity.isConnectedAndReady()) {
            GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_wait_connect, Toast.LENGTH_SHORT).show());
            return;
        }
        if (GanRobotActivity.isSending() || RobotSessionState.isRobotMoving()) {
            GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_send_in_progress, Toast.LENGTH_SHORT).show());
            return;
        }
        ioExecutor.execute(() -> {
            RobotSessionState.setRobotMoving(true);
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
                RobotSessionState.setRobotMoving(false);
            }
        });
    }

    public static void executeScrambleAsync(String scramble, boolean useMainTargetState) {
        ioExecutor.execute(() -> {
            RobotSessionState.setRobotMoving(true);
            try {
                // If scramble not supplied, read from state now that onRobotExecutionStart has fired
                String effectiveScramble = TextUtils.isEmpty(scramble)
                        ? RobotSessionState.getLatestMainScramble()
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
                RobotSessionState.setRobotMoving(false);
            }
        });
    }

    public static void handleRobotButtonEvent(byte[] rawValue) {
        int action = getRobotButtonAction();
        // Only react to button press packet (02 FF); ignore initial handshake (03 00 00 FF 00 00)
        if (rawValue == null || rawValue.length == 0 || (rawValue[0] & 0xff) != 0x02) {
            return;
        }
        if (action == ACTION_NONE) {
            return;
        }
        GanRobotActivity activity = GanRobotActivity.getActiveActivity();
        if (activity != null) {
            if (GanRobotActivity.isSending() || RobotSessionState.isRobotMoving()) {
                GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_busy, Toast.LENGTH_SHORT).show());
                return;
            }
            showButtonActionToast(action);
            activity.runOnUiThread(() -> activity.requestRobotButtonAction(action));
            return;
        }
        // No visible Activity — execute directly if already connected (stays in background)
        if (action == ACTION_SOLVE && GanRobotActivity.isConnectedAndReady()) {
            if (GanRobotActivity.isSending() || RobotSessionState.isRobotMoving()) {
                GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_busy, Toast.LENGTH_SHORT).show());
                return;
            }
            showButtonActionToast(action);
            solveFromSmartCubeState();
            return;
        }
        if (action == ACTION_SCRAMBLE && GanRobotActivity.isConnectedAndReady()) {
            if (GanRobotActivity.isSending() || RobotSessionState.isRobotMoving()) {
                GanRobotActivity.postOnMainThread(() -> Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_busy, Toast.LENGTH_SHORT).show());
                return;
            }
            showButtonActionToast(action);
            executeScrambleAsync(null, false);
            return;
        }
        // Cannot reach here in normal operation: button notifications only fire when BLE is connected
    }

    private static void showButtonActionToast(int action) {
        Context context = APP.getInstance();
        if (context == null) return;
        String actionName = context.getString(action == ACTION_SOLVE
                ? R.string.gan_robot_button_action_solve
                : R.string.gan_robot_button_action_scramble);
        String msg = context.getString(R.string.gan_robot_button_executing, actionName);
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }
}
