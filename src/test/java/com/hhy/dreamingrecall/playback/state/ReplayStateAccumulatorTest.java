package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.RecordPriority;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayStateAccumulatorTest {
    @Test
    void reconstructsChunkOverridesAndObservationState() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        String dimension = "minecraft:overworld";
        int chunkX = -2;
        int chunkZ = 3;
        long blockPosition = packBlockPosition(-17, 70, 49);

        accumulator.apply(ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                0,
                0,
                "initial".getBytes(StandardCharsets.UTF_8)
        ));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.CHUNK_BASELINE,
                10,
                1,
                dimension,
                encode(output -> {
                    output.writeInt(2);
                    output.writeBoolean(true);
                    output.writeInt(chunkX);
                    output.writeInt(chunkZ);
                    output.writeInt(0);
                    output.writeInt(0);
                })
        ));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.BLOCK_CHANGE,
                20,
                2,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeLong(blockPosition);
                    writeString(output, "example:missing_block");
                    output.writeInt(1);
                    writeString(output, "active");
                    writeString(output, "true");
                })
        ));
        accumulator.apply(ReplayRecord.control(CoreRecordType.BASELINE_END, 30, 3, new byte[0]));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.CHUNK_OBSERVATION_END,
                40,
                4,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeInt(chunkX);
                    output.writeInt(chunkZ);
                })
        ));

        ReplayWorldSnapshot snapshot = accumulator.snapshotAt(45);
        ReplayWorldSnapshot.ChunkSnapshot chunk = snapshot.dimensions()
                .get(dimension)
                .chunks()
                .get(new ReplayWorldSnapshot.ChunkKey(chunkX, chunkZ));
        assertTrue(snapshot.baselineComplete());
        assertFalse(chunk.observed());
        assertEquals("example:missing_block", chunk.blockOverrides().get(blockPosition).blockId());

        ReplayWorldSnapshot restored = new ReplayStateAccumulator(snapshot).snapshot();
        assertEquals(snapshot, restored);
    }

    @Test
    void malformedRecordsBecomeDiagnosticsAndGapsInvalidateCompleteness() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        accumulator.apply(ReplayRecord.control(CoreRecordType.BASELINE_END, 1, 1, new byte[0]));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.DIMENSION_STATE,
                2,
                2,
                "minecraft:overworld",
                new byte[]{0, 0, 0, 1}
        ));
        accumulator.apply(ReplayRecord.control(
                CoreRecordType.RECORDING_GAP,
                3,
                3,
                encode(output -> {
                    output.writeLong(2);
                    output.writeLong(3);
                    output.writeLong(7);
                })
        ));
        accumulator.apply(new ReplayRecord(9000, RecordPriority.ENHANCEMENT, 4, 4, "", new byte[]{1}));

        ReplayWorldSnapshot snapshot = accumulator.snapshot();
        assertFalse(snapshot.baselineComplete());
        assertEquals(1, snapshot.gaps().size());
        assertEquals(7, snapshot.gaps().getFirst().droppedRecords());
        assertEquals(2, snapshot.diagnostics().size());
    }

    @Test
    void highPrecisionCameraSamplesSurviveCheckpointRestore() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        UUID playerId = UUID.randomUUID();
        String dimension = "minecraft:the_nether";
        accumulator.apply(new ReplayRecord(
                CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                RecordPriority.ENHANCEMENT,
                25,
                7,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeLong(playerId.getMostSignificantBits());
                    output.writeLong(playerId.getLeastSignificantBits());
                    output.writeLong(9_000L);
                    output.writeDouble(12.25);
                    output.writeDouble(66.5);
                    output.writeDouble(-8.75);
                    output.writeFloat(91.0F);
                    output.writeFloat(-18.0F);
                    output.writeFloat(2.5F);
                    output.writeFloat(82.0F);
                })
        ));

        ReplayWorldSnapshot snapshot = accumulator.snapshot();
        assertEquals(66.5, snapshot.dimensions().get(dimension).cameraSamples().get(playerId).y());

        ReplayWorldSnapshot restored = new ReplayStateAccumulator(snapshot).snapshot();
        assertEquals(snapshot, restored);
    }

    @Test
    void removalsClearBlockEntitiesPlayersAndTheirCameraSamples() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        UUID playerId = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        long blockPosition = packBlockPosition(3, 64, 5);

        accumulator.apply(ReplayRecord.core(
                CoreRecordType.BLOCK_ENTITY_STATE,
                1,
                1,
                dimension,
                encode(output -> {
                    output.writeInt(2);
                    output.writeLong(blockPosition);
                    writeString(output, "example:missing_block_entity");
                    output.writeBoolean(false);
                    writeString(output, "not installed");
                })
        ));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.PLAYER_STATE,
                2,
                2,
                dimension,
                playerState(playerId)
        ));
        accumulator.apply(new ReplayRecord(
                CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                RecordPriority.ENHANCEMENT,
                3,
                3,
                dimension,
                cameraSample(playerId)
        ));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.BLOCK_ENTITY_REMOVE,
                4,
                4,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeLong(blockPosition);
                })
        ));
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.ENTITY_REMOVE,
                5,
                5,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeLong(playerId.getMostSignificantBits());
                    output.writeLong(playerId.getLeastSignificantBits());
                })
        ));

        ReplayWorldSnapshot.DimensionSnapshot state = accumulator.snapshot().dimensions().get(dimension);
        assertTrue(state.chunks().values().stream().allMatch(chunk -> chunk.blockEntities().isEmpty()));
        assertTrue(state.players().isEmpty());
        assertTrue(state.cameraSamples().isEmpty());
    }

    @Test
    void replacementBaselineRetainsTerrainAsUnobservedButClearsDynamicObjects() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        UUID playerId = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.CHUNK_BASELINE,
                1,
                1,
                dimension,
                encode(output -> {
                    output.writeInt(2);
                    output.writeBoolean(true);
                    output.writeInt(0);
                    output.writeInt(0);
                    output.writeInt(0);
                    output.writeInt(0);
                })
        ));
        accumulator.apply(ReplayRecord.core(CoreRecordType.PLAYER_STATE, 2, 2, dimension, playerState(playerId)));

        accumulator.apply(ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                3,
                3,
                "recorder_overload_recovery".getBytes(StandardCharsets.UTF_8)
        ));

        ReplayWorldSnapshot.DimensionSnapshot state = accumulator.snapshot().dimensions().get(dimension);
        assertFalse(state.chunks().get(new ReplayWorldSnapshot.ChunkKey(0, 0)).observed());
        assertTrue(state.players().isEmpty());
    }

    @Test
    void entityEffectsSurviveSnapshotRestore() throws Exception {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        UUID entityId = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        accumulator.apply(ReplayRecord.core(
                CoreRecordType.ENTITY_EFFECT,
                125,
                8,
                dimension,
                encode(output -> {
                    output.writeInt(1);
                    output.writeLong(entityId.getMostSignificantBits());
                    output.writeLong(entityId.getLeastSignificantBits());
                    writeString(output, "critical_hit");
                })
        ));

        ReplayWorldSnapshot snapshot = accumulator.snapshot();
        assertEquals(1, snapshot.recentEntityEffects().size());
        assertEquals(entityId, snapshot.recentEntityEffects().getFirst().effect().entityId());
        assertEquals(snapshot, new ReplayStateAccumulator(snapshot).snapshot());
    }

    private static byte[] playerState(UUID playerId) throws IOException {
        return encode(output -> {
            output.writeInt(1);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            writeString(output, "ReplayPlayer");
            writeTransform(output);
            output.writeDouble(1.0);
            output.writeDouble(66.0);
            output.writeDouble(2.0);
            output.writeFloat(10.0F);
            output.writeFloat(10.0F);
            output.writeFloat(20.0F);
            output.writeFloat(0.0F);
            output.writeInt(20);
            output.writeInt(0);
            writeString(output, "survival");
            output.writeInt(0);
        });
    }

    private static byte[] cameraSample(UUID playerId) throws IOException {
        return encode(output -> {
            output.writeInt(1);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeLong(100);
            output.writeDouble(1.0);
            output.writeDouble(66.0);
            output.writeDouble(2.0);
            output.writeFloat(10.0F);
            output.writeFloat(5.0F);
            output.writeFloat(0.0F);
            output.writeFloat(70.0F);
        });
    }

    private static void writeTransform(DataOutputStream output) throws IOException {
        output.writeDouble(1.0);
        output.writeDouble(64.0);
        output.writeDouble(2.0);
        output.writeFloat(10.0F);
        output.writeFloat(5.0F);
        output.writeDouble(0.0);
        output.writeDouble(0.0);
        output.writeDouble(0.0);
        writeString(output, "standing");
        output.writeBoolean(true);
    }

    private static long packBlockPosition(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (long) y & 0xFFFL;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.write(output);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
