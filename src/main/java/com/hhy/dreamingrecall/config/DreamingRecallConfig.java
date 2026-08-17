package com.hhy.dreamingrecall.config;

import com.hhy.dreamingrecall.recording.RecordingSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.Duration;

public final class DreamingRecallConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_RECORDING;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_RECORDING;
    public static final ModConfigSpec.BooleanValue CAPTURE_CHAT;
    public static final ModConfigSpec.BooleanValue CAPTURE_SOUNDS;
    public static final ModConfigSpec.BooleanValue CLIENT_CAMERA_TRACKS_ALLOWED;
    public static final ModConfigSpec.IntValue QUEUE_CAPACITY;
    public static final ModConfigSpec.IntValue QUEUE_MEBIBYTES;
    public static final ModConfigSpec.IntValue MAX_RECORD_MEBIBYTES;
    public static final ModConfigSpec.IntValue SEGMENT_SECONDS;
    public static final ModConfigSpec.IntValue SEGMENT_MEBIBYTES;
    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL;
    public static final ModConfigSpec.IntValue CHECKPOINT_SECONDS;
    public static final ModConfigSpec.IntValue BASELINE_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue NEW_ENTITIES_PER_TICK;
    public static final ModConfigSpec.IntValue ENTITY_UPDATES_PER_TICK;
    public static final ModConfigSpec.IntValue AUTOMATIC_QUOTA_MEBIBYTES;
    public static final ModConfigSpec.IntValue RETENTION_MIN_FREE_MEBIBYTES;
    public static final ModConfigSpec.IntValue RESOURCE_PACK_MAX_MEBIBYTES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("recording");
        AUTO_RECORDING = builder
                .comment("Start a new automatic archive when the server finishes starting.")
                .define("autoRecording", false);
        ANNOUNCE_RECORDING = builder
                .comment("Tell online players when server recording starts or stops.")
                .define("announceRecording", true);
        CAPTURE_CHAT = builder
                .comment("Capture standard chat-box entries per recipient.")
                .define("captureChat", true);
        CAPTURE_SOUNDS = builder
                .comment("Capture ordinary level sound events.")
                .define("captureSounds", true);
        CLIENT_CAMERA_TRACKS_ALLOWED = builder
                .comment("Allow clients to opt in to high-precision camera samples. Disabled by default.")
                .define("clientCameraTracksAllowed", false);
        builder.pop();

        builder.push("performance");
        QUEUE_CAPACITY = builder.defineInRange("queueCapacity", 8192, 128, 131072);
        QUEUE_MEBIBYTES = builder.defineInRange("queueMiB", 64, 8, 2048);
        MAX_RECORD_MEBIBYTES = builder.defineInRange("maxRecordMiB", 16, 1, 256);
        SEGMENT_SECONDS = builder.defineInRange("segmentSeconds", 10, 1, 60);
        SEGMENT_MEBIBYTES = builder.defineInRange("segmentMiB", 16, 1, 256);
        COMPRESSION_LEVEL = builder.defineInRange("compressionLevel", 1, 0, 9);
        CHECKPOINT_SECONDS = builder
                .comment("Interval between persisted random-seek state checkpoints.")
                .defineInRange("checkpointSeconds", 30, 10, 600);
        BASELINE_CHUNKS_PER_TICK = builder.defineInRange("baselineChunksPerTick", 1, 1, 64);
        NEW_ENTITIES_PER_TICK = builder.defineInRange("newEntitiesPerTick", 64, 1, 4096);
        ENTITY_UPDATES_PER_TICK = builder
                .comment("Maximum non-player entities fingerprinted per server tick.")
                .defineInRange("entityUpdatesPerTick", 512, 16, 65536);
        AUTOMATIC_QUOTA_MEBIBYTES = builder
                .comment("Maximum total size of automatic archives. Zero disables automatic rotation.")
                .defineInRange("automaticQuotaMiB", 0, 0, 1024 * 1024);
        RETENTION_MIN_FREE_MEBIBYTES = builder
                .comment("When rotation is enabled, keep at least this much free space.")
                .defineInRange("retentionMinFreeMiB", 10240, 0, 1024 * 1024);
        RESOURCE_PACK_MAX_MEBIBYTES = builder
                .comment("Maximum optional server resource-pack attachment size per archive.")
                .defineInRange("resourcePackMaxMiB", 256, 0, 4096);
        builder.pop();

        SPEC = builder.build();
    }

    private DreamingRecallConfig() {
    }

    public static RecordingSettings recordingSettings() {
        long queueBytes = QUEUE_MEBIBYTES.get() * 1024L * 1024L;
        int maxRecordBytes = Math.toIntExact(Math.min(
                MAX_RECORD_MEBIBYTES.get() * 1024L * 1024L,
                queueBytes
        ));
        int segmentBytes = Math.toIntExact(Math.min(
                SEGMENT_MEBIBYTES.get() * 1024L * 1024L,
                queueBytes
        ));
        return new RecordingSettings(
                QUEUE_CAPACITY.get(),
                queueBytes,
                maxRecordBytes,
                Duration.ofSeconds(SEGMENT_SECONDS.get()),
                segmentBytes,
                COMPRESSION_LEVEL.get(),
                Duration.ofSeconds(CHECKPOINT_SECONDS.get()),
                Duration.ofMillis(50)
        );
    }
}
