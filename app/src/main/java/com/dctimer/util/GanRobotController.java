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

    private static final String PREF_NAME = "dctimer";
    private static final String PREF_KEY_BUTTON_ACTION = "ganrobot_button_action";

    private static int robotButtonAction = ACTION_SOLVE;
    private static boolean prefsLoaded = false;

    private GanRobotController() { }

    private static synchronized void setRobotButtonAction(int action) {
        robotButtonAction = action;
        prefsLoaded = true;
    }

    public static void saveRobotButtonAction(Context context, int action) {
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(PREF_KEY_BUTTON_ACTION, action).apply();
        }
        setRobotButtonAction(action);
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

    public static int getSelectionForAction(int action) {
        if (action == ACTION_SCRAMBLE) {
            return 1;
        }
        if (action == ACTION_NONE) {
            return 2;
        }
        return 0;
    }

    public static int getActionForSelection(int position) {
        if (position == 1) {
            return ACTION_SCRAMBLE;
        }
        if (position == 2) {
            return ACTION_NONE;
        }
        return ACTION_SOLVE;
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
        // No visible Activity - execute directly if already connected.
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
            GanRobotExecutor.executeScramble(null, true);
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
