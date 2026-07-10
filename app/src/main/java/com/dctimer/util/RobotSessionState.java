package com.dctimer.util;

import android.text.TextUtils;

public final class RobotSessionState {
    private static String latestMainScramble = "";
    private static String latestMainTargetState = "";
    private static String latestSmartCubeState = "";
    private static boolean isRobotMoving = false;
    private static OnRobotStateChangeListener stateChangeListener;

    public interface OnRobotStateChangeListener {
        /**
         * Called when robot execution starts.
         * Should reset all scramble deviation tracking.
         */
        void onRobotExecutionStart();

        /**
         * Called when robot execution ends.
         */
        void onRobotExecutionEnd();
    }

    private RobotSessionState() { }

    public static synchronized void setLatestSmartCubeState(String cubeState) {
        latestSmartCubeState = cubeState == null ? "" : cubeState.trim();
    }

    public static synchronized void setLatestMainScramble(String scramble) {
        latestMainScramble = scramble == null ? "" : scramble.trim();
    }

    public static synchronized String getLatestMainScramble() {
        return latestMainScramble;
    }

    public static synchronized void setLatestMainTargetState(String targetState) {
        latestMainTargetState = targetState == null ? "" : targetState.trim();
    }

    public static synchronized String getLatestMainTargetState() {
        return latestMainTargetState;
    }

    public static synchronized String getLatestSmartCubeState() {
        return latestSmartCubeState;
    }

    public static synchronized boolean hasSmartCubeState() {
        return !TextUtils.isEmpty(latestSmartCubeState);
    }

    public static synchronized void setRobotMoving(boolean moving) {
        boolean wasMoving = isRobotMoving;
        isRobotMoving = moving;

        // Notify listener of state change
        if (!wasMoving && moving && stateChangeListener != null) {
            // Robot just started executing
            stateChangeListener.onRobotExecutionStart();
        } else if (wasMoving && !moving && stateChangeListener != null) {
            // Robot just finished executing
            stateChangeListener.onRobotExecutionEnd();
        }
    }

    public static synchronized boolean isRobotMoving() {
        return isRobotMoving;
    }

    public static synchronized void setStateChangeListener(OnRobotStateChangeListener listener) {
        stateChangeListener = listener;
    }
}
