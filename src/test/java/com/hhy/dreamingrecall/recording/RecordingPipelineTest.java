package com.hhy.dreamingrecall.recording;

import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveInspector;
import com.hhy.dreamingrecall.archive.ArchiveScanResult;
import com.hhy.dreamingrecall.archive.ArchiveScanner;
import com.hhy.dreamingrecall.archive.ContentReferenceCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.RecordPriority;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;
import com.hhy.dreamingrecall.playback.state.ReplayStateMaterializer;
import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingPipelineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void drainsAcceptedRecordsAndCommitsCleanCompletion() throws Exception {
        RecordingPipeline pipeline = pipeline(RecordingSettings.defaults());
        pipeline.start();
        pipeline.readyFuture().get(5, TimeUnit.SECONDS);

        for (int index = 0; index < 100; index++) {
            OfferResult result = pipeline.offer(ReplayRecord.core(
                    CoreRecordType.PLAYER_STATE,
                    index * 50_000_000L,
                    index,
                    "minecraft:overworld",
                    new byte[]{(byte) index}
            ));
            assertEquals(OfferResult.ACCEPTED, result);
        }
        pipeline.requestStop(5_000_000_000L, 100);
        Path archive = pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);

        ArchiveScanResult scan = ArchiveScanner.scan(archive, true);
        List<ReplayRecord> records = new ArrayList<>();
        scan.index().segments().forEach(segment -> {
            try {
                records.addAll(SegmentCodec.read(segment.path()).records());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        assertEquals(PipelineState.STOPPED, pipeline.state());
        assertEquals(102, records.size());
        assertEquals(CoreRecordType.SESSION_START.id(), records.getFirst().typeId());
        assertEquals(CoreRecordType.SESSION_END.id(), records.getLast().typeId());
        assertTrue(scan.diagnostics().isEmpty());
    }

    @Test
    void normalizesOutOfOrderTimestampsBeforeCommit() throws Exception {
        RecordingPipeline pipeline = pipeline(RecordingSettings.defaults());
        pipeline.start();
        pipeline.readyFuture().get(5, TimeUnit.SECONDS);

        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.SERVER_TICK, 100, 1, "", new byte[0]
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.SERVER_TICK, 10, 2, "", new byte[0]
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.SERVER_TICK, 50, 3, "", new byte[0]
        )));

        pipeline.requestStop(20, 4);
        Path archive = pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);

        assertTrue(ArchiveInspector.inspect(archive).isHealthy());
        List<Long> times = ArchiveScanner.scan(archive, true).index().segments().stream()
                .flatMap(segment -> {
                    try {
                        return SegmentCodec.read(segment.path()).records().stream();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                })
                .map(ReplayRecord::archiveNanos)
                .toList();
        assertTrue(times.stream().sorted().toList().equals(times));
        assertTrue(times.getLast() >= 100);
    }

    @Test
    void oversizedCoreStartsExplicitGapButEnhancementOnlyDrops() throws Exception {
        RecordingSettings constrained = new RecordingSettings(
                16,
                4096,
                128,
                Duration.ofSeconds(10),
                1024,
                1,
                Duration.ofSeconds(30),
                Duration.ofMillis(5)
        );
        RecordingPipeline pipeline = pipeline(constrained);
        pipeline.start();
        pipeline.readyFuture().get(5, TimeUnit.SECONDS);

        byte[] tooLarge = new byte[129];
        assertEquals(
                OfferResult.DROPPED_ENHANCEMENT,
                pipeline.offer(new ReplayRecord(2000, RecordPriority.ENHANCEMENT, 10, 1, "", tooLarge))
        );
        assertFalse(pipeline.requiresBaseline());
        assertEquals(
                OfferResult.REJECTED_TOO_LARGE,
                pipeline.offer(ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 20, 1, "minecraft:overworld", tooLarge))
        );
        assertTrue(pipeline.requiresBaseline());
        long generation = pipeline.baselineGeneration();
        assertTrue(pipeline.markBaselineComplete(generation));

        pipeline.requestStop(30, 2);
        Path archive = pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);
        List<Integer> types = ArchiveScanner.scan(archive, true).index().segments().stream()
                .flatMap(segment -> {
                    try {
                        return SegmentCodec.read(segment.path()).records().stream();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                })
                .map(ReplayRecord::typeId)
                .toList();

        assertTrue(types.contains(CoreRecordType.RECORDING_GAP.id()));
        assertEquals(1, pipeline.metrics().droppedCoreRecords());
        assertEquals(1, pipeline.metrics().droppedEnhancementRecords());
    }

    @Test
    void externalizesChunkBaselinesAndResolvesThemForPlayback() throws Exception {
        RecordingPipeline pipeline = pipeline(RecordingSettings.defaults());
        pipeline.start();
        pipeline.readyFuture().get(5, TimeUnit.SECONDS);

        byte[] baseline = ByteBuffer.allocate(21)
                .putInt(2)
                .put((byte) 1)
                .putInt(7)
                .putInt(-9)
                .putInt(0)
                .putInt(0)
                .array();
        for (int index = 0; index < 2; index++) {
            assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                    CoreRecordType.CHUNK_BASELINE,
                    index * 50_000_000L,
                    index,
                    "minecraft:overworld",
                    baseline
            )));
        }

        pipeline.requestStop(100_000_000L, 2);
        Path archive = pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);
        ArchiveScanResult scan = ArchiveScanner.scan(archive, true);

        List<ReplayRecord> encodedChunks = new ArrayList<>();
        for (var segment : scan.index().segments()) {
            SegmentCodec.read(segment.path()).records().stream()
                    .filter(record -> record.typeId() == CoreRecordType.CHUNK_BASELINE.id())
                    .forEach(encodedChunks::add);
        }
        assertEquals(2, encodedChunks.size());
        assertTrue(encodedChunks.stream().allMatch(record ->
                ContentReferenceCodec.decode(record.payloadCopy()).isPresent()));
        try (var files = Files.walk(archive.resolve("content"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1")) {
            List<ReplayRecord> resolvedChunks = new ArrayList<>();
            for (var segment : source.index().segments()) {
                source.readSegment(segment, new ReadCancellation()).get(5, TimeUnit.SECONDS).records().stream()
                        .filter(record -> record.typeId() == CoreRecordType.CHUNK_BASELINE.id())
                        .forEach(resolvedChunks::add);
            }
            assertEquals(2, resolvedChunks.size());
            resolvedChunks.forEach(record -> assertArrayEquals(baseline, record.payloadCopy()));
        }
    }

    @Test
    void queuedEnhancementsAreEvictedBeforeAWaitingCoreRecord() throws Exception {
        RecordingSettings constrained = new RecordingSettings(
                16,
                4096,
                1024,
                Duration.ofSeconds(30),
                1024,
                1,
                Duration.ofSeconds(30),
                Duration.ofMillis(50)
        );
        List<ReplayRecord> droppedEnhancements = new ArrayList<>();
        RecordingPipeline pipeline = pipeline(constrained, droppedEnhancements::add);
        pipeline.start();

        byte[] optionalPayload = new byte[512];
        for (int index = 0; index < 256; index++) {
            pipeline.offer(new ReplayRecord(
                    2000,
                    RecordPriority.ENHANCEMENT,
                    index,
                    index,
                    "",
                    optionalPayload
            ));
        }
        OfferResult coreResult = pipeline.offer(ReplayRecord.core(
                CoreRecordType.SERVER_TICK,
                1_000,
                1_000,
                "",
                new byte[0]
        ));

        assertEquals(OfferResult.ACCEPTED, coreResult);
        assertEquals(0, pipeline.metrics().droppedCoreRecords());
        assertTrue(pipeline.metrics().droppedEnhancementRecords() > 0);
        assertEquals(
                pipeline.metrics().droppedEnhancementRecords(),
                (long) droppedEnhancements.size()
        );

        pipeline.requestStop(2_000, 1_001);
        pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void persistsPortableStateCheckpointForImmediateRandomSeeking() throws Exception {
        RecordingPipeline pipeline = pipeline(RecordingSettings.defaults());
        pipeline.start();
        pipeline.readyFuture().get(5, TimeUnit.SECONDS);
        int chunkX = 1;
        int chunkZ = -1;
        long blockPosition = packBlockPosition(17, 70, -2);
        byte[] baseline = ByteBuffer.allocate(21)
                .putInt(2)
                .put((byte) 1)
                .putInt(chunkX)
                .putInt(chunkZ)
                .putInt(0)
                .putInt(0)
                .array();

        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                0,
                0,
                "initial".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.DIMENSION_STATE,
                5,
                1,
                "minecraft:overworld",
                ByteBuffer.allocate(56)
                        .putInt(1)
                        .putLong(0)
                        .putLong(0)
                        .putFloat(0)
                        .putFloat(0)
                        .putInt(2)
                        .putDouble(0)
                        .putDouble(0)
                        .putDouble(60_000_000)
                        .array()
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.CHUNK_BASELINE,
                10,
                1,
                "minecraft:overworld",
                baseline
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.core(
                CoreRecordType.BLOCK_CHANGE,
                20,
                2,
                "minecraft:overworld",
                blockChange(blockPosition, "minecraft:diamond_block")
        )));
        assertEquals(OfferResult.ACCEPTED, pipeline.offer(ReplayRecord.control(
                CoreRecordType.BASELINE_END,
                30,
                3,
                new byte[0]
        )));

        pipeline.requestStop(100, 4);
        Path archive = pipeline.stoppedFuture().get(10, TimeUnit.SECONDS);
        ArchiveScanResult checkpointScan = ArchiveScanner.scanSegmentDirectory(
                archive.resolve("checkpoints"),
                true
        );
        assertEquals(1, checkpointScan.index().segments().size());
        assertTrue(checkpointScan.diagnostics().isEmpty());

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1");
             ReplayStateMaterializer materializer = new ReplayStateMaterializer(source)) {
            var index = materializer.buildIndex().get(5, TimeUnit.SECONDS);
            assertTrue(index.checkpoints().getLast().isPersisted());
            ReplayWorldSnapshot snapshot = materializer.seek(100).get(5, TimeUnit.SECONDS);
            assertEquals(
                    "minecraft:diamond_block",
                    snapshot.dimensions().get("minecraft:overworld")
                            .chunks().get(new ReplayWorldSnapshot.ChunkKey(chunkX, chunkZ))
                            .blockOverrides().get(blockPosition).blockId()
            );
        }

        Path checkpoint = checkpointScan.index().segments().getFirst().path();
        byte[] corrupted = Files.readAllBytes(checkpoint);
        corrupted[corrupted.length - 1] ^= 0x5A;
        Files.write(checkpoint, corrupted);
        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1");
             ReplayStateMaterializer materializer = new ReplayStateMaterializer(source)) {
            ReplayWorldSnapshot fallback = materializer.seek(100).get(5, TimeUnit.SECONDS);
            assertEquals(
                    "minecraft:diamond_block",
                    fallback.dimensions().get("minecraft:overworld")
                            .chunks().get(new ReplayWorldSnapshot.ChunkKey(chunkX, chunkZ))
                            .blockOverrides().get(blockPosition).blockId()
            );
        }
    }

    private static byte[] blockChange(long position, String blockId) {
        byte[] id = blockId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES + Integer.BYTES + id.length + Integer.BYTES)
                .putInt(1)
                .putLong(position)
                .putInt(id.length)
                .put(id)
                .putInt(0)
                .array();
    }

    private static long packBlockPosition(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (long) y & 0xFFFL;
    }

    private RecordingPipeline pipeline(RecordingSettings settings) {
        return pipeline(settings, ignored -> {
        });
    }

    private RecordingPipeline pipeline(
            RecordingSettings settings,
            java.util.function.Consumer<ReplayRecord> droppedEnhancementListener
    ) {
        return new RecordingPipeline(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.DEDICATED_SERVER),
                settings,
                failure -> {
                    throw new AssertionError(failure);
                },
                droppedEnhancementListener
        );
    }
}
