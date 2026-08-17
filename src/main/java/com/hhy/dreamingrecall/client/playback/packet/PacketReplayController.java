package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.client.playback.ReplayViewController;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PacketReplayController implements AutoCloseable {
    private static final int MAX_BATCH_PACKETS = 256;
    private static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;

    private final Minecraft minecraft;
    private final ArchiveDataSource source;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("DreamingRecall-PacketReplay-", 0).factory()
    );
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<ReadCancellation> activeRead = new AtomicReference<>();

    private CompletableFuture<PacketReplayIndex> indexFuture;
    private volatile ReplayPacketSession session;
    private Cursor cursor;
    private volatile long currentNanos;
    private volatile boolean closed;

    public PacketReplayController(Minecraft minecraft, ArchiveDataSource source) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.source = Objects.requireNonNull(source, "source");
    }

    public synchronized CompletableFuture<PacketReplayIndex> buildIndex() {
        if (indexFuture == null) {
            ReadCancellation cancellation = new ReadCancellation();
            indexFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return PacketReplayIndexer.scan(source, cancellation);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, executor);
        }
        return indexFuture;
    }

    public CompletableFuture<PlaybackResult> seek(long requestedNanos) {
        if (requestedNanos < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Replay time must be non-negative"));
        }
        long requestedGeneration = generation.incrementAndGet();
        ReadCancellation cancellation = new ReadCancellation();
        ReadCancellation previous = activeRead.getAndSet(cancellation);
        if (previous != null) {
            previous.cancel();
        }
        return buildIndex().thenCompose(index -> CompletableFuture.supplyAsync(
                () -> seekBlocking(index, requestedNanos, requestedGeneration, cancellation),
                executor
        )).whenComplete((result, failure) -> activeRead.compareAndSet(cancellation, null));
    }

    public long currentNanos() {
        return currentNanos;
    }

    public ReplayViewController view() {
        ReplayPacketSession current = session;
        return current == null ? null : current.view();
    }

    public void cancelSeek() {
        generation.incrementAndGet();
        ReadCancellation cancellation = activeRead.getAndSet(null);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    private PlaybackResult seekBlocking(
            PacketReplayIndex index,
            long requestedNanos,
            long requestedGeneration,
            ReadCancellation cancellation
    ) {
        ensureActive(requestedGeneration, cancellation);
        long target = Math.min(index.durationNanos(), requestedNanos);
        boolean rebuild = session == null || cursor == null || target < currentNanos;
        if (rebuild) {
            runOnMainThread(() -> {
                ReplayPacketSession previous = session;
                if (previous != null) {
                    previous.close();
                }
                try {
                    session = new ReplayPacketSession(minecraft, index);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }).join();
            cursor = new Cursor(source.index().segments());
            currentNanos = 0;
        }

        long applied = advanceCursor(target, requestedGeneration, cancellation);
        currentNanos = target;
        runOnMainThread(() -> {
            ReplayPacketSession current = session;
            if (current != null) {
                current.finishBatch();
            }
        }).join();
        ReplayViewController replayView = view();
        return new PlaybackResult(target, applied, replayView != null);
    }

    private long advanceCursor(long target, long requestedGeneration, ReadCancellation cancellation) {
        ArrayList<PacketEnvelope> batch = new ArrayList<>(MAX_BATCH_PACKETS);
        int batchBytes = 0;
        long applied = 0;

        while (cursor.segmentIndex < cursor.segments.size()) {
            ensureActive(requestedGeneration, cancellation);
            SegmentMetadata metadata = cursor.segments.get(cursor.segmentIndex);
            if (metadata.startArchiveNanos() > target) {
                break;
            }
            if (cursor.loaded == null) {
                cursor.loaded = source.readRawSegment(metadata, cancellation).join();
                cursor.recordIndex = 0;
            }
            List<ReplayRecord> records = cursor.loaded.records();
            while (cursor.recordIndex < records.size()) {
                ensureActive(requestedGeneration, cancellation);
                ReplayRecord record = records.get(cursor.recordIndex);
                if (record.archiveNanos() > target) {
                    flush(batch, requestedGeneration, cancellation);
                    return applied;
                }
                cursor.recordIndex++;
                if (record.typeId() != CoreRecordType.PACKET_FRAME.id()) {
                    continue;
                }
                PacketEnvelope envelope;
                try {
                    envelope = PacketEnvelopeCodec.decode(record.payloadCopy());
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
                if (envelope.phase() != ProtocolPhase.PLAY) {
                    continue;
                }
                int envelopeBytes = envelope.packetBytes().length;
                if (!batch.isEmpty()
                        && (batch.size() >= MAX_BATCH_PACKETS || batchBytes + envelopeBytes > MAX_BATCH_BYTES)) {
                    flush(batch, requestedGeneration, cancellation);
                    batchBytes = 0;
                }
                batch.add(envelope);
                batchBytes += envelopeBytes;
                applied++;
            }
            cursor.segmentIndex++;
            cursor.recordIndex = 0;
            cursor.loaded = null;
        }
        flush(batch, requestedGeneration, cancellation);
        return applied;
    }

    private void flush(
            ArrayList<PacketEnvelope> batch,
            long requestedGeneration,
            ReadCancellation cancellation
    ) {
        if (batch.isEmpty()) {
            return;
        }
        ensureActive(requestedGeneration, cancellation);
        List<PacketEnvelope> immutable = List.copyOf(batch);
        batch.clear();
        runOnMainThread(() -> {
            ensureActive(requestedGeneration, cancellation);
            ReplayPacketSession current = session;
            if (current == null) {
                throw new IllegalStateException("Replay packet session disappeared");
            }
            try {
                for (PacketEnvelope envelope : immutable) {
                    ensureActive(requestedGeneration, cancellation);
                    current.apply(envelope);
                }
                current.finishBatch();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).join();
    }

    private CompletableFuture<Void> runOnMainThread(Runnable action) {
        if (minecraft.isSameThread()) {
            try {
                action.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void ensureActive(long requestedGeneration, ReadCancellation cancellation) {
        cancellation.throwIfCancelled();
        if (closed || generation.get() != requestedGeneration) {
            throw new java.util.concurrent.CancellationException("Replay seek was superseded");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelSeek();
        cursor = null;
        ReplayPacketSession current = session;
        session = null;
        if (current != null) {
            if (minecraft.isSameThread()) {
                current.close();
            } else {
                runOnMainThread(current::close).join();
            }
        }
        executor.shutdownNow();
    }

    public record PlaybackResult(long archiveNanos, long appliedPackets, boolean worldReady) {
    }

    private static final class Cursor {
        private final List<SegmentMetadata> segments;
        private int segmentIndex;
        private int recordIndex;
        private SegmentReadResult loaded;

        private Cursor(List<SegmentMetadata> segments) {
            this.segments = List.copyOf(segments);
        }
    }
}
