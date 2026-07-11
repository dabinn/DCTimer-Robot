package com.dctimer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.dctimer.APP;
import com.dctimer.R;
import com.dctimer.activity.GanRobotActivity;

public final class GanRobotController {
    public static final int ACTION_NONE = 0;
    public static final int ACTION_SOLVE = 1;
    public static final int ACTION_SCRAMBLE = 2;

    static final String PREF_NAME = "dctimer";
    public static final String PREF_KEY_BUTTON_ACTION = "ganrobot_button_action";

    private static int robotButtonAction = ACTION_SOLVE;
    private static boolean prefsLoaded = false;

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

    public static void handleRobotButtonEvent(byte[] rawValue) {
        int action = getRobotButtonAction();
        if (!GanRobotProtocol.isButtonPressEvent(rawValue)) {
            return;
        }
        if (action == ACTION_NONE) {
            return;
        }
        GanRobotActivity activity = GanRobotActivity.getActiveActivity();
        if (activity != null) {
            if (isBusy()) {
                showBusyToast();
                return;
            }
            showButtonActionToast(action);
            activity.runOnUiThread(() -> activity.requestRobotButtonAction(action));
            return;
        }
        // No visible Activity — execute directly if already connected (stays in background)
        if (action == ACTION_SOLVE && GanRobotActivity.isConnectedAndReady()) {
            if (isBusy()) {
                showBusyToast();
                return;
            }
            showButtonActionToast(action);
            GanRobotExecutor.solveFromSmartCubeState();
            return;
        }
        if (action == ACTION_SCRAMBLE && GanRobotActivity.isConnectedAndReady()) {
            if (isBusy()) {
                showBusyToast();
                return;
            }
            showButtonActionToast(action);
            GanRobotExecutor.executeScramble(null, false);
            return;
        }
        // Cannot reach here in normal operation: button notifications only fire when BLE is connected
    }

    private static boolean isBusy() {
        return GanRobotActivity.isSending() || GanRobotSessionState.isRobotMoving();
    }

    private static void showBusyToast() {
        GanRobotActivity.postOnMainThread(() ->
                Toast.makeText(GanRobotActivity.robotContext(), R.string.gan_robot_busy, Toast.LENGTH_SHORT).show());
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
