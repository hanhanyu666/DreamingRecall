package com.hhy.dreamingrecall.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class DreamingRecallClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue RECORD_ON_JOIN;
    public static final ModConfigSpec.BooleanValue CAPTURE_CAMERA_TRACK;
    public static final ModConfigSpec.IntValue CAMERA_SAMPLE_HZ;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        RECORD_ON_JOIN = builder
                .comment("Request replay recording when entering a singleplayer world or server. Disabled by default.")
                .define("recordOnJoin", false);
        CAPTURE_CAMERA_TRACK = builder
                .comment("Opt in to sending high-precision camera samples while playing. Disabled by default.")
                .define("captureCameraTrack", false);
        CAMERA_SAMPLE_HZ = builder
                .comment("Maximum high-precision camera samples sent per second.")
                .defineInRange("cameraSampleHz", 20, 1, 60);
        SPEC = builder.build();
    }

    private DreamingRecallClientConfig() {
    }
}
