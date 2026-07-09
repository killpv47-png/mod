package com.killpv47.stealthaddon.features;

/**
 * FakePing - Always active feature (NOT a module).
 * Reduces the ping value displayed for the local player by a constant amount
 * to make it look lower when other players inspect it via TAB list / F3.
 *
 * This works purely on the CLIENT display side; the actual server latency
 * is unchanged. It only affects what appears in the tab list for the local
 * player entry, which is what other people see when they open TAB while
 * looking at this player's server info screenshots / streams.
 *
 * Configuration: constant subtraction of PING_REDUCTION milliseconds,
 * clamped to a minimum of MIN_PING so it never becomes 0 (which would look
 * unnatural / obviously fake).
 */
public class FakePing {
    /** How many ms to subtract from the displayed ping. */
    public static final int PING_REDUCTION = 30;

    /** Never display below this value, keeps it looking realistic. */
    public static final int MIN_PING = 5;

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        // The real work happens in PlayerListHudMixin.
        // This init just marks the feature as active.
    }

    /**
     * Apply the fake reduction to a real latency value.
     * Called from the mixin.
     */
    public static int applyFakePing(int realPing) {
        if (realPing <= 0) return realPing; // negative / zero means unknown
        int fake = realPing - PING_REDUCTION;
        if (fake < MIN_PING) fake = MIN_PING;
        return fake;
    }
}
