package com.hhy.dreamingrecall.client.playback;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Keeps packet-backed replay entity ticks on the archive clock instead of the
 * live client's clock. All methods are called from the client thread.
 */
public final class ReplayClock {
    private static final double NANOS_PER_TICK = 50_000_000.0;
    private static final int MAX_TICKS_PER_CLIENT_TICK = 10;

    private static ClientLevel activeLevel;
    private static double pendingTicks;
    private static boolean nativeTickAllowed;
    private static int extraTickDepth;

    private ReplayClock() {
    }

    public static void activate(ClientLevel level) {
        if (activeLevel != level) {
            activeLevel = level;
            pendingTicks = 0.0;
        }
        nativeTickAllowed = false;
    }

    public static void deactivate(ClientLevel level) {
        if (activeLevel == level) {
            activeLevel = null;
            pendingTicks = 0.0;
            nativeTickAllowed = false;
        }
    }

    /**
     * Prepares the entity ticks that Minecraft will perform after the screen
     * tick. One tick is consumed by Minecraft itself; any remainder is run
     * explicitly so fast playback does not leave animations behind.
     */
    public static int prepare(ClientLevel level, long elapsedNanos, double speed, boolean running) {
        if (activeLevel != level) {
            activate(level);
        }
        if (!running || elapsedNanos <= 0L || speed <= 0.0) {
            nativeTickAllowed = false;
            return 0;
        }
        pendingTicks += (elapsedNanos / NANOS_PER_TICK) * speed;
        int ticks = Math.min(MAX_TICKS_PER_CLIENT_TICK, (int) pendingTicks);
        if (ticks > 0) {
            pendingTicks -= ticks;
        }
        nativeTickAllowed = ticks > 0;
        return Math.max(0, ticks - 1);
    }

    public static boolean allowEntityTick(ClientLevel level) {
        if (activeLevel != level) {
            return true;
        }
        if (extraTickDepth > 0) {
            return true;
        }
        if (!nativeTickAllowed) {
            return false;
        }
        nativeTickAllowed = false;
        return true;
    }

    public static void runExtraTicks(ClientLevel level, int count) {
        if (activeLevel != level || count <= 0) {
            return;
        }
        extraTickDepth++;
        try {
            for (int index = 0; index < count; index++) {
                level.tickEntities();
            }
        } finally {
            extraTickDepth--;
        }
    }
}
