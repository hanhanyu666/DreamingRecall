package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class ReplayStateMaterializer implements AutoCloseable {
    public static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(ReplayStateMaterializer.class.getName());

    private final ArchiveDataSource source;
    private final long checkpointIntervalNanos;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("DreamingRecall-StateMaterializer-", 0).factory()
    );
    private final AtomicReference<ReadCancellation> activeSeek = new AtomicReference<>();
    private final ReadCancellation indexCancellation = new ReadCancellation();
    private final Map<Long, ReplayWorldSnapshot> persistedCheckpointCache = new LinkedHashMap<>(8, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ReplayWorldSnapshot> eldest) {
            return size() > 4;
        }
    };

    private CompletableFuture<ReplayStateIndex> indexFuture;

    public ReplayStateMaterializer(ArchiveDataSource source) {
        this(source, DEFAULT_CHECKPOINT_INTERVAL);
    }

    public ReplayStateMaterializer(ArchiveDataSource source, Duration checkpointInterval) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(checkpointInterval, "checkpointInterval");
        if (checkpointInterval.isZero() || checkpointInterval.isNegative()) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        this.checkpointIntervalNanos = checkpointInterval.toNanos();
    }

    public synchronized CompletableFuture<ReplayStateIndex> buildIndex() {
        if (indexFuture == null) {
            indexFuture = CompletableFuture.supplyAsync(this::buildIndexBlocking, executor);
        }
        return indexFuture;
    }

    public CompletableFuture<ReplayWorldSnapshot> seek(long archiveNanos) {
        if (archiveNanos < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("archiveNanos must be non-negative")
            );
        }
        ReadCancellation cancellation = new ReadCancellation();
        ReadCancellation previous = activeSeek.getAndSet(cancellation);
        if (previous != null) {
            previous.cancel();
        }
        return buildIndex().thenCompose(index -> CompletableFuture.supplyAsync(
                () -> seekBlocking(index, archiveNanos, cancellation),
                executor
        )).whenComplete((snapshot, failure) -> activeSeek.compareAndSet(cancellation, null));
    }

    public void cancelSeek() {
        ReadCancellation cancellation = activeSeek.getAndSet(null);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    @Override
    public void close() {
        cancelSeek();
        indexCancellation.cancel();
        synchronized (persistedCheckpointCache) {
            persistedCheckpointCache.clear();
        }
        executor.shutdownNow();
    }

    private ReplayStateIndex buildIndexBlocking() {
        try {
            return source.checkpointIndex().segments().isEmpty()
                    ? buildTransientIndexBlocking()
                    : buildPersistedIndexBlocking();
        } catch (CompletionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CompletionException(failure);
        }
    }

    private ReplayStateIndex buildTransientIndexBlocking() {
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        ArrayList<ReplayStateCheckpoint> checkpoints = new ArrayList<>();
        checkpoints.add(new ReplayStateCheckpoint(0, -1, accumulator.snapshotAt(0)));
        long nextCheckpointNanos = checkpointIntervalNanos;
        long durationNanos = 0;
        long firstPopulatedNanos = Long.MAX_VALUE;
        long lastCheckpointSequence = -1;

        for (SegmentMetadata segment : source.index().segments()) {
            indexCancellation.throwIfCancelled();
            var decoded = source.readSegment(segment, indexCancellation).join();
            for (var record : decoded.records()) {
                accumulator.apply(record);
                if (firstPopulatedNanos == Long.MAX_VALUE && accumulator.hasWorldState()) {
                    firstPopulatedNanos = record.archiveNanos();
                }
            }
            durationNanos = Math.max(durationNanos, segment.endArchiveNanos());
            if (segment.endArchiveNanos() >= nextCheckpointNanos) {
                checkpoints.add(new ReplayStateCheckpoint(
                        segment.endArchiveNanos(),
                        segment.sequence(),
                        accumulator.snapshotAt(segment.endArchiveNanos())
                ));
                lastCheckpointSequence = segment.sequence();
                do {
                    nextCheckpointNanos = Math.addExact(nextCheckpointNanos, checkpointIntervalNanos);
                } while (nextCheckpointNanos <= segment.endArchiveNanos());
            }
        }

        List<SegmentMetadata> segments = source.index().segments();
        if (!segments.isEmpty()) {
            SegmentMetadata last = segments.getLast();
            if (last.sequence() != lastCheckpointSequence) {
                checkpoints.add(new ReplayStateCheckpoint(
                        last.endArchiveNanos(),
                        last.sequence(),
                        accumulator.snapshotAt(last.endArchiveNanos())
                ));
            }
        }
        return new ReplayStateIndex(
                checkpoints,
                durationNanos,
                firstPopulatedNanos == Long.MAX_VALUE ? 0 : firstPopulatedNanos
        );
    }

    private ReplayStateIndex buildPersistedIndexBlocking() {
        List<SegmentMetadata> segments = source.index().segments();
        if (segments.isEmpty()) {
            throw new CompletionException(new IllegalStateException("Replay archive has no segments"));
        }
        long durationNanos = segments.getLast().endArchiveNanos();
        HashSet<Long> mainSequences = new HashSet<>();
        segments.forEach(segment -> mainSequences.add(segment.sequence()));

        ArrayList<ReplayStateCheckpoint> checkpoints = new ArrayList<>();
        checkpoints.add(new ReplayStateCheckpoint(0, -1, new ReplayStateAccumulator().snapshotAt(0)));
        source.checkpointIndex().segments().stream()
                .filter(segment -> mainSequences.contains(segment.sequence()))
                .filter(segment -> segment.endArchiveNanos() <= durationNanos)
                .map(ReplayStateCheckpoint::persisted)
                .forEach(checkpoints::add);
        if (checkpoints.size() == 1) {
            return buildTransientIndexBlocking();
        }
        return new ReplayStateIndex(checkpoints, durationNanos, findFirstPopulatedNanos(segments));
    }

    private long findFirstPopulatedNanos(List<SegmentMetadata> segments) {
        for (SegmentMetadata segment : segments) {
            indexCancellation.throwIfCancelled();
            var decoded = source.readRawSegment(segment, indexCancellation).join();
            for (var record : decoded.records()) {
                if (createsWorldState(record)) {
                    return record.archiveNanos();
                }
            }
        }
        return 0;
    }

    private static boolean createsWorldState(ReplayRecord record) {
        return CoreRecordType.fromId(record.typeId()).map(type -> switch (type) {
            case DIMENSION_STATE,
                 CHUNK_BASELINE,
                 CHUNK_OBSERVATION_END,
                 BLOCK_CHANGE,
                 BLOCK_ENTITY_STATE,
                 BLOCK_ENTITY_REMOVE,
                 CHUNK_LIGHT,
                 ENTITY_SPAWN,
                 ENTITY_STATE,
                 PLAYER_STATE,
                 CLIENT_CAMERA_SAMPLE,
                 CLIENT_PLAYER_VISUAL_SAMPLE -> true;
            default -> false;
        }).orElse(false);
    }

    private ReplayWorldSnapshot seekBlocking(
            ReplayStateIndex index,
            long requestedArchiveNanos,
            ReadCancellation cancellation
    ) {
        cancellation.throwIfCancelled();
        long target = Math.min(requestedArchiveNanos, index.durationNanos());
        ReplayStateCheckpoint checkpoint = index.checkpointAtOrBefore(target);
        ReplayWorldSnapshot checkpointState;
        while (true) {
            try {
                checkpointState = checkpoint.isPersisted()
                        ? loadPersistedCheckpoint(checkpoint, cancellation)
                        : checkpoint.snapshot();
                break;
            } catch (RuntimeException checkpointFailure) {
                if (rootCause(checkpointFailure) instanceof java.util.concurrent.CancellationException) {
                    throw checkpointFailure;
                }
                int failedIndex = index.checkpoints().indexOf(checkpoint);
                if (failedIndex <= 0) {
                    throw checkpointFailure;
                }
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Ignoring an unreadable replay checkpoint and falling back to an earlier state",
                        checkpointFailure
                );
                checkpoint = index.checkpoints().get(failedIndex - 1);
            }
        }
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator(checkpointState);

        for (SegmentMetadata segment : source.index().segments()) {
            if (segment.sequence() <= checkpoint.throughSegmentSequence()) {
                continue;
            }
            if (segment.startArchiveNanos() > target) {
                break;
            }
            cancellation.throwIfCancelled();
            var decoded = source.readSegment(segment, cancellation).join();
            for (var record : decoded.records()) {
                cancellation.throwIfCancelled();
                if (record.archiveNanos() <= target) {
                    accumulator.apply(record);
                }
            }
        }
        cancellation.throwIfCancelled();
        return accumulator.snapshotAt(target);
    }

    private ReplayWorldSnapshot loadPersistedCheckpoint(
            ReplayStateCheckpoint checkpoint,
            ReadCancellation cancellation
    ) {
        synchronized (persistedCheckpointCache) {
            ReplayWorldSnapshot cached = persistedCheckpointCache.get(checkpoint.throughSegmentSequence());
            if (cached != null) {
                return cached;
            }
        }
        cancellation.throwIfCancelled();
        ReplayStateAccumulator accumulator = new ReplayStateAccumulator();
        var decoded = source.readSegment(checkpoint.persistedSegment(), cancellation).join();
        for (var record : decoded.records()) {
            cancellation.throwIfCancelled();
            accumulator.apply(record);
        }
        ReplayWorldSnapshot loaded = accumulator.snapshotAt(checkpoint.archiveNanos());
        synchronized (persistedCheckpointCache) {
            persistedCheckpointCache.put(checkpoint.throughSegmentSequence(), loaded);
        }
        return loaded;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
