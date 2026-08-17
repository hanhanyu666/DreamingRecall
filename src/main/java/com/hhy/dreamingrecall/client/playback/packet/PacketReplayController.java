package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.archive.track.TrackNames;
import com.hhy.dreamingrecall.client.playback.ReplayViewController;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.decode.PortableRecordDecoder;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PacketReplayController implements AutoCloseable {
    private static final int MAX_BATCH_PACKETS = 256;
    private static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    private static final PortableRecordDecoder PORTABLE_DECODER = new PortableRecordDecoder();

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
    private volatile UUID selectedPlayerId;
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
        return buildIndex().thenCompose(index -> {
            PacketReplayIndex.PlayerTrack track = selectedTrack(index);
            if (track == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No playable player packet track"));
            }
            return CompletableFuture.supplyAsync(
                    () -> seekBlocking(index, track, requestedNanos, requestedGeneration, cancellation),
                    executor
            );
        }).whenComplete((result, failure) -> activeRead.compareAndSet(cancellation, null));
    }

    public long currentNanos() {
        return currentNanos;
    }

    public ReplayViewController view() {
        ReplayPacketSession current = session;
        return current == null ? null : current.view();
    }

    public synchronized boolean selectPlayerTrack(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PacketReplayIndex built;
        try {
            built = indexFuture == null ? null : indexFuture.getNow(null);
        } catch (CompletionException failedIndex) {
            return false;
        }
        if (built == null || built.track(playerId).isEmpty()) {
            return false;
        }
        if (playerId.equals(selectedPlayerId)) {
            return true;
        }
        selectedPlayerId = playerId;
        deactivate();
        return true;
    }

    public Optional<UUID> selectedPlayerId() {
        return Optional.ofNullable(selectedPlayerId);
    }

    public void deactivate() {
        cancelSeek();
        cursor = null;
        currentNanos = 0;
        ReplayPacketSession current = session;
        session = null;
        if (current != null) {
            if (minecraft.isSameThread()) {
                current.close();
            } else {
                runOnMainThread(current::close).join();
            }
        }
    }

    private synchronized PacketReplayIndex.PlayerTrack selectedTrack(PacketReplayIndex index) {
        if (selectedPlayerId != null) {
            PacketReplayIndex.PlayerTrack selected = index.track(selectedPlayerId).orElse(null);
            if (selected != null) {
                return selected;
            }
        }
        PacketReplayIndex.PlayerTrack selected = index.defaultTrack().orElse(null);
        selectedPlayerId = selected == null ? null : selected.playerId();
        return selected;
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
            PacketReplayIndex.PlayerTrack track,
            long requestedNanos,
            long requestedGeneration,
            ReadCancellation cancellation
    ) {
        ensureActive(requestedGeneration, cancellation);
        long target = Math.max(track.worldStartNanos(), Math.min(index.durationNanos(), requestedNanos));
        boolean rebuild = session == null || cursor == null || target < currentNanos;
        if (rebuild) {
            runOnMainThread(() -> {
                ReplayPacketSession previous = session;
                if (previous != null) {
                    previous.close();
                }
                try {
                    session = new ReplayPacketSession(minecraft, track);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }).join();
            cursor = new Cursor(source.index().segments(), track.trackId(), track.playerId());
            currentNanos = 0;
        }

        Cursor activeCursor = cursor;
        if (activeCursor == null) {
            throw new java.util.concurrent.CancellationException("Replay packet cursor disappeared");
        }
        long applied = advanceCursor(activeCursor, target, requestedGeneration, cancellation);
        currentNanos = target;
        DecodedPayload.PlayerVisualSample playerVisual = activeCursor.playerVisual;
        DecodedPayload.CameraSample camera = activeCursor.camera;
        runOnMainThread(() -> {
            ensureActive(requestedGeneration, cancellation);
            ReplayPacketSession current = session;
            if (current != null) {
                current.finishBatch();
                current.applyTelemetry(playerVisual, camera);
            }
        }).join();
        ReplayViewController replayView = view();
        return new PlaybackResult(target, applied, replayView != null);
    }

    private long advanceCursor(
            Cursor activeCursor,
            long target,
            long requestedGeneration,
            ReadCancellation cancellation
    ) {
        ArrayList<PacketEnvelope> batch = new ArrayList<>(MAX_BATCH_PACKETS);
        int batchBytes = 0;
        long applied = 0;

        while (activeCursor.segmentIndex < activeCursor.segments.size()) {
            ensureActive(requestedGeneration, cancellation);
            SegmentMetadata metadata = activeCursor.segments.get(activeCursor.segmentIndex);
            if (metadata.startArchiveNanos() > target) {
                break;
            }
            if (activeCursor.loaded == null) {
                activeCursor.loaded = source.readRawSegment(metadata, cancellation).join();
                activeCursor.recordIndex = 0;
            }
            List<ReplayRecord> records = activeCursor.loaded.records();
            while (activeCursor.recordIndex < records.size()) {
                ensureActive(requestedGeneration, cancellation);
                ReplayRecord record = records.get(activeCursor.recordIndex);
                if (record.archiveNanos() > target) {
                    flush(batch, requestedGeneration, cancellation);
                    return applied;
                }
                activeCursor.recordIndex++;
                if (record.typeId() == CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE.id()
                        || record.typeId() == CoreRecordType.CLIENT_CAMERA_SAMPLE.id()) {
                    captureTelemetry(activeCursor, record);
                    continue;
                }
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
                if (!envelope.trackId().equals(activeCursor.trackId)
                        && !envelope.trackId().equals(TrackNames.SHARED_WORLD)) {
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
            activeCursor.segmentIndex++;
            activeCursor.recordIndex = 0;
            activeCursor.loaded = null;
        }
        flush(batch, requestedGeneration, cancellation);
        return applied;
    }

    private void captureTelemetry(Cursor activeCursor, ReplayRecord record) {
        try {
            DecodedPayload decoded = PORTABLE_DECODER.decode(record);
            if (decoded instanceof DecodedPayload.PlayerVisualSample visual
                    && visual.playerId().equals(activeCursor.playerId)) {
                activeCursor.playerVisual = visual;
            } else if (decoded instanceof DecodedPayload.CameraSample camera
                    && camera.playerId().equals(activeCursor.playerId)) {
                activeCursor.camera = camera;
            }
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
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
        deactivate();
        executor.shutdownNow();
    }

    public record PlaybackResult(long archiveNanos, long appliedPackets, boolean worldReady) {
    }

    private static final class Cursor {
        private final List<SegmentMetadata> segments;
        private final String trackId;
        private final UUID playerId;
        private int segmentIndex;
        private int recordIndex;
        private SegmentReadResult loaded;
        private DecodedPayload.PlayerVisualSample playerVisual;
        private DecodedPayload.CameraSample camera;

        private Cursor(List<SegmentMetadata> segments, String trackId, UUID playerId) {
            this.segments = List.copyOf(segments);
            this.trackId = trackId;
            this.playerId = playerId;
        }
    }
}
