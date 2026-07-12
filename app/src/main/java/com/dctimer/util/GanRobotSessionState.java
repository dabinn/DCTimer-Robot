package com.dctimer.util;

import android.os.SystemClock;
import android.text.TextUtils;

public final class GanRobotSessionState {
    private static String latestMainScramble = "";
    private static String latestMainTargetState = "";
    private static String latestSmartCubeState = "";
    private static boolean latestUseMainTargetState = true;
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

    private GanRobotSessionState() { }

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

    public static synchronized void setUseMainTargetState(boolean useMainTargetState) {
        latestUseMainTargetState = useMainTargetState;
    }

    public static synchronized boolean getUseMainTargetState() {
        return latestUseMainTargetState;
    }

    public static synchronized String getLatestSmartCubeState() {
        return latestSmartCubeState;
    }

    public static synchronized boolean hasSmartCubeState() {
        return !TextUtils.isEmpty(latestSmartCubeState);
    }

    public static String waitForSmartCubeStateSnapshot(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            String cubeState = getLatestSmartCubeState();
            if (!TextUtils.isEmpty(cubeState)) {
                return cubeState;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return getLatestSmartCubeState();
    }

    public static void setRobotMoving(boolean moving) {
        OnRobotStateChangeListener listener;
        boolean notifyStart;
        boolean notifyEnd;
        synchronized (GanRobotSessionState.class) {
            boolean wasMoving = isRobotMoving;
            isRobotMoving = moving;
            listener = stateChangeListener;
            notifyStart = !wasMoving && moving && listener != null;
            notifyEnd = wasMoving && !moving && listener != null;
        }
        if (notifyStart) {
            listener.onRobotExecutionStart();
        } else if (notifyEnd) {
            listener.onRobotExecutionEnd();
        }
    }

    public static synchronized boolean isRobotMoving() {
        return isRobotMoving;
    }

    public static synchronized void setStateChangeListener(OnRobotStateChangeListener listener) {
        stateChangeListener = listener;
    }
}
