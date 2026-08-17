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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayStateMaterializerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void seeksFromNearestCompletedSegmentCheckpoint() throws Exception {
        Path archive = ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
        int chunkX = 2;
        int chunkZ = -3;
        long position = packBlockPosition(35, 64, -33);
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        ReplayRecord.control(CoreRecordType.BASELINE_BEGIN, 0, 0, "initial".getBytes(StandardCharsets.UTF_8)),
                        ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 1, 1, "minecraft:overworld", chunkBaseline(chunkX, chunkZ)),
                        ReplayRecord.control(CoreRecordType.BASELINE_END, 10, 2, new byte[0]),
                        ReplayRecord.core(CoreRecordType.BLOCK_CHANGE, 12, 3, "minecraft:overworld", blockChange(position, "minecraft:stone"))
                ),
                1
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                1,
                List.of(
                        ReplayRecord.core(CoreRecordType.BLOCK_CHANGE, 20, 4, "minecraft:overworld", blockChange(position, "minecraft:diamond_block")),
                        ReplayRecord.core(CoreRecordType.CHUNK_OBSERVATION_END, 25, 5, "minecraft:overworld", chunkCoordinates(chunkX, chunkZ)),
                        ReplayRecord.control(CoreRecordType.SESSION_END, 30, 6, new byte[0])
                ),
                1
        );

        try (LocalArchiveDataSource local = LocalArchiveDataSource.open(archive, "1.21.1")) {
            CountingDataSource source = new CountingDataSource(local);
            try (ReplayStateMaterializer materializer = new ReplayStateMaterializer(source, Duration.ofNanos(10))) {
                ReplayStateIndex index = materializer.buildIndex().get(5, TimeUnit.SECONDS);
                assertEquals(List.of(0L, 12L, 30L), index.checkpoints().stream()
                        .map(ReplayStateCheckpoint::archiveNanos)
                        .toList());
                assertEquals(1, index.firstPopulatedNanos());
                assertEquals(2, source.reads.get());

                ReplayWorldSnapshot at15 = materializer.seek(15).get(5, TimeUnit.SECONDS);
                assertEquals("minecraft:stone", blockAt(at15, chunkX, chunkZ, position));
                assertEquals(2, source.reads.get());

                ReplayWorldSnapshot at22 = materializer.seek(22).get(5, TimeUnit.SECONDS);
                assertEquals("minecraft:diamond_block", blockAt(at22, chunkX, chunkZ, position));
                assertTrue(chunkAt(at22, chunkX, chunkZ).observed());
                assertEquals(3, source.reads.get());

                ReplayWorldSnapshot beyondEnd = materializer.seek(1_000).get(5, TimeUnit.SECONDS);
                assertEquals(30, beyondEnd.archiveNanos());
                assertFalse(chunkAt(beyondEnd, chunkX, chunkZ).observed());
                assertEquals(3, source.reads.get());
            }
        }
    }

    @Test
    void aNewSeekCompletesWithoutWaitingForSupersededIo() throws Exception {
        SegmentMetadata metadata = new SegmentMetadata(
                temporaryDirectory.resolve("controlled.drseg"),
                0,
                0,
                100,
                2,
                0,
                0,
                0
        );
        List<ReplayRecord> records = List.of(
                ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, "test".getBytes(StandardCharsets.UTF_8)),
                ReplayRecord.core(CoreRecordType.SERVER_TICK, 100, 2, "", new byte[0])
        );
        ControlledDataSource source = new ControlledDataSource(metadata, records);

        try (ReplayStateMaterializer materializer = new ReplayStateMaterializer(source, Duration.ofSeconds(30))) {
            materializer.buildIndex().get(5, TimeUnit.SECONDS);
            CompletableFuture<ReplayWorldSnapshot> superseded = materializer.seek(50);
            assertTrue(source.firstSeekStarted.await(5, TimeUnit.SECONDS));

            ReplayWorldSnapshot latest = materializer.seek(60).get(5, TimeUnit.SECONDS);
            assertEquals(60, latest.archiveNanos());

            source.completeFirstSeek();
            CompletionException cancellation = assertThrows(CompletionException.class, superseded::join);
            assertTrue(cancellation.getCause() instanceof CancellationException);
        }
    }

    private static String blockAt(ReplayWorldSnapshot snapshot, int chunkX, int chunkZ, long position) {
        return chunkAt(snapshot, chunkX, chunkZ).blockOverrides().get(position).blockId();
    }

    private static ReplayWorldSnapshot.ChunkSnapshot chunkAt(
            ReplayWorldSnapshot snapshot,
            int chunkX,
            int chunkZ
    ) {
        return snapshot.dimensions()
                .get("minecraft:overworld")
                .chunks()
                .get(new ReplayWorldSnapshot.ChunkKey(chunkX, chunkZ));
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

    private static final class ControlledDataSource implements ArchiveDataSource {
        private final ArchiveManifest manifest = ArchiveManifest.create(
                "1.21.1",
                "test",
                ArchiveManifest.SourceKind.SINGLEPLAYER
        );
        private final SegmentMetadata metadata;
        private final SegmentReadResult result;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch firstSeekStarted = new CountDownLatch(1);
        private final CompletableFuture<SegmentReadResult> firstSeekRead = new CompletableFuture<>();

        private ControlledDataSource(SegmentMetadata metadata, List<ReplayRecord> records) {
            this.metadata = metadata;
            this.result = new SegmentReadResult(metadata, records);
        }

        @Override
        public ArchiveManifest manifest() {
            return manifest;
        }

        @Override
        public ArchiveIndex index() {
            return new ArchiveIndex(List.of(metadata));
        }

        @Override
        public CompletableFuture<SegmentReadResult> readSegment(
                SegmentMetadata segment,
                ReadCancellation cancellation
        ) {
            int read = reads.incrementAndGet();
            if (read == 2) {
                firstSeekStarted.countDown();
                return firstSeekRead;
            }
            return CompletableFuture.completedFuture(result);
        }

        private void completeFirstSeek() {
            firstSeekRead.complete(result);
        }

        @Override
        public void close() {
        }
    }
}
