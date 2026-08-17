package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.ArchiveIndex;
import com.hhy.dreamingrecall.archive.ArchiveLayout;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayForwardCursorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesTheCurrentDecodedSegmentWhilePlayingForward() throws Exception {
        Path archive = ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
        int chunkX = 0;
        int chunkZ = 0;
        long position = packBlockPosition(1, 64, 1);
        ReplayRecord baselineBegin = ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                0,
                0,
                "initial".getBytes(StandardCharsets.UTF_8)
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        baselineBegin,
                        ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 1, 1, "minecraft:overworld", chunkBaseline(chunkX, chunkZ)),
                        ReplayRecord.control(CoreRecordType.BASELINE_END, 2, 2, new byte[0]),
                        ReplayRecord.core(CoreRecordType.BLOCK_CHANGE, 10, 3, "minecraft:overworld", blockChange(position, "minecraft:stone")),
                        ReplayRecord.core(CoreRecordType.BLOCK_CHANGE, 20, 4, "minecraft:overworld", blockChange(position, "minecraft:diamond_block"))
                ),
                1
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                1,
                List.of(ReplayRecord.core(
                        CoreRecordType.CHUNK_OBSERVATION_END,
                        30,
                        5,
                        "minecraft:overworld",
                        chunkCoordinates(chunkX, chunkZ)
                )),
                1
        );

        ReplayStateAccumulator initial = new ReplayStateAccumulator();
        initial.apply(baselineBegin);
        try (LocalArchiveDataSource local = LocalArchiveDataSource.open(archive, "1.21.1")) {
            CountingDataSource source = new CountingDataSource(local);
            try (ReplayForwardCursor cursor = new ReplayForwardCursor(source, initial.snapshotAt(0))) {
                ReplayWorldSnapshot at5 = cursor.advanceTo(5).get(5, TimeUnit.SECONDS);
                assertTrue(at5.baselineComplete());
                assertEquals(1, source.reads.get());

                ReplayWorldSnapshot at15 = cursor.advanceTo(15).get(5, TimeUnit.SECONDS);
                assertEquals("minecraft:stone", blockAt(at15, position));
                assertEquals(1, source.reads.get());

                ReplayWorldSnapshot at25 = cursor.advanceTo(25).get(5, TimeUnit.SECONDS);
                assertEquals("minecraft:diamond_block", blockAt(at25, position));
                assertEquals(1, source.reads.get());

                ReplayWorldSnapshot at35 = cursor.advanceTo(35).get(5, TimeUnit.SECONDS);
                assertFalse(chunkAt(at35).observed());
                assertEquals(2, source.reads.get());
            }
        }
    }

    @Test
    void lookaheadFindsMotionWithoutApplyingInterveningWorldRecords() throws Exception {
        Path archive = ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
        UUID playerId = UUID.randomUUID();
        long position = packBlockPosition(1, 64, 1);
        ReplayRecord baselineBegin = ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                0,
                0,
                "initial".getBytes(StandardCharsets.UTF_8)
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        baselineBegin,
                        ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 1, 1, "minecraft:overworld", chunkBaseline(0, 0)),
                        ReplayRecord.control(CoreRecordType.BASELINE_END, 2, 2, new byte[0]),
                        ReplayRecord.core(CoreRecordType.BLOCK_CHANGE, 10, 3, "minecraft:overworld", blockChange(position, "minecraft:stone")),
                        ReplayRecord.core(CoreRecordType.PLAYER_STATE, 15, 4, "minecraft:overworld", playerState(playerId))
                ),
                1
        );
        ReplayStateAccumulator initial = new ReplayStateAccumulator();
        initial.apply(baselineBegin);

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1");
             ReplayForwardCursor cursor = new ReplayForwardCursor(source, initial.snapshotAt(0))) {
            ReplayPlaybackFrame at5 = cursor.advanceFrameTo(5, 20).get(5, TimeUnit.SECONDS);
            assertTrue(at5.nextPlayers().containsKey(playerId));
            assertTrue(chunkAt(at5.snapshot()).blockOverrides().isEmpty());

            ReplayPlaybackFrame at12 = cursor.advanceFrameTo(12, 20).get(5, TimeUnit.SECONDS);
            assertEquals("minecraft:stone", blockAt(at12.snapshot(), position));
        }
    }

    @Test
    void legacyAdvanceToStillMaterializesCameraOnlyChanges() throws Exception {
        Path archive = ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
        UUID playerId = UUID.randomUUID();
        ReplayRecord baselineBegin = ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                0,
                0,
                "initial".getBytes(StandardCharsets.UTF_8)
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        baselineBegin,
                        ReplayRecord.control(CoreRecordType.BASELINE_END, 1, 1, new byte[0]),
                        new ReplayRecord(
                                CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                                com.hhy.dreamingrecall.archive.RecordPriority.ENHANCEMENT,
                                5,
                                1,
                                "minecraft:overworld",
                                cameraSample(playerId)
                        )
                ),
                1
        );
        ReplayStateAccumulator initial = new ReplayStateAccumulator();
        initial.apply(baselineBegin);

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1");
             ReplayForwardCursor cursor = new ReplayForwardCursor(source, initial.snapshotAt(0))) {
            ReplayWorldSnapshot snapshot = cursor.advanceTo(10).get(5, TimeUnit.SECONDS);
            assertEquals(
                    65.62,
                    snapshot.dimensions().get("minecraft:overworld").cameraSamples().get(playerId).y()
            );
        }
    }

    private static String blockAt(ReplayWorldSnapshot snapshot, long position) {
        return chunkAt(snapshot).blockOverrides().get(position).blockId();
    }

    private static ReplayWorldSnapshot.ChunkSnapshot chunkAt(ReplayWorldSnapshot snapshot) {
        return snapshot.dimensions()
                .get("minecraft:overworld")
                .chunks()
                .get(new ReplayWorldSnapshot.ChunkKey(0, 0));
    }

    private static byte[] chunkBaseline(int x, int z) throws IOException {
        return encode(output -> {
            output.writeInt(2);
            output.writeBoolean(true);
            output.writeInt(x);
            output.writeInt(z);
            output.writeInt(0);
            output.writeInt(0);
        });
    }

    private static byte[] chunkCoordinates(int x, int z) throws IOException {
        return encode(output -> {
            output.writeInt(1);
            output.writeInt(x);
            output.writeInt(z);
        });
    }

    private static byte[] blockChange(long position, String blockId) throws IOException {
        return encode(output -> {
            output.writeInt(1);
            output.writeLong(position);
            writeString(output, blockId);
            output.writeInt(0);
        });
    }

    private static byte[] playerState(UUID playerId) throws IOException {
        return encode(output -> {
            output.writeInt(1);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            writeString(output, "ReplayPlayer");
            output.writeDouble(1.0);
            output.writeDouble(64.0);
            output.writeDouble(1.0);
            output.writeFloat(0.0F);
            output.writeFloat(0.0F);
            output.writeDouble(0.0);
            output.writeDouble(0.0);
            output.writeDouble(0.0);
            writeString(output, "standing");
            output.writeBoolean(true);
            output.writeDouble(1.0);
            output.writeDouble(65.62);
            output.writeDouble(1.0);
            output.writeFloat(0.0F);
            output.writeFloat(0.0F);
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
            output.writeDouble(65.62);
            output.writeDouble(1.0);
            output.writeFloat(0.0F);
            output.writeFloat(0.0F);
            output.writeFloat(0.0F);
            output.writeFloat(70.0F);
        });
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

    private static final class CountingDataSource implements ArchiveDataSource {
        private final ArchiveDataSource delegate;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingDataSource(ArchiveDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public ArchiveManifest manifest() {
            return delegate.manifest();
        }

        @Override
        public ArchiveIndex index() {
            return delegate.index();
        }

        @Override
        public CompletableFuture<SegmentReadResult> readSegment(
                SegmentMetadata segment,
                ReadCancellation cancellation
        ) {
            reads.incrementAndGet();
            return delegate.readSegment(segment, cancellation);
        }

        @Override
        public void close() {
        }
    }
}
