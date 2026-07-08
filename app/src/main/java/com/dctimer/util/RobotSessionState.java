package com.dctimer.util;

import android.text.TextUtils;

public final class RobotSessionState {
    private static String latestMainScramble = "";
    private static String latestSmartCubeState = "";

    private RobotSessionState() { }

    public static synchronized void setLatestMainScramble(String scramble) {
        latestMainScramble = scramble == null ? "" : scramble.trim();
    }

    public static synchronized String getLatestMainScramble() {
        return latestMainScramble;
    }

    public static synchronized void setLatestSmartCubeState(String cubeState) {
        latestSmartCubeState = cubeState == null ? "" : cubeState.trim();
    }

    public static synchronized String getLatestSmartCubeState() {
        return latestSmartCubeState;
    }

    public static synchronized boolean hasSmartCubeState() {
        return !TextUtils.isEmpty(latestSmartCubeState);
    }
}
