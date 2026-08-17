package com.hhy.dreamingrecall.client.playback.packet;

public final class ReplayPacketDispatchContext {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ReplayPacketDispatchContext() {
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static boolean suppressScreenChanges() {
        return isActive();
    }

    public static void run(Runnable action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }
}
