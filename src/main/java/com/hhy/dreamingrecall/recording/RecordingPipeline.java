package com.hhy.dreamingrecall.recording;

import com.hhy.dreamingrecall.archive.ArchiveCompletion;
import com.hhy.dreamingrecall.archive.ArchiveAttachmentStore;
import com.hhy.dreamingrecall.archive.ArchiveLayout;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ContentAddressedStore;
import com.hhy.dreamingrecall.archive.ContentReference;
import com.hhy.dreamingrecall.archive.ContentReferenceCodec;
import com.hhy.dreamingrecall.archive.RecordPriority;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import com.hhy.dreamingrecall.archive.SegmentMetadata;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class RecordingPipeline {
    private static final System.Logger LOGGER = System.getLogger(RecordingPipeline.class.getName());

    private final Path archiveRoot;
    private final ArchiveManifest manifest;
    private final RecordingSettings settings;
    private final ArrayBlockingQueue<QueuedRecord> queue;
    private final Object offerLock = new Object();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicReference<PipelineState> state = new AtomicReference<>(PipelineState.NEW);
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicLong gapGeneration = new AtomicLong();
    private final AtomicBoolean baselineRequired = new AtomicBoolean();
    private final GapAccumulator gaps = new GapAccumulator();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final CompletableFuture<Path> ready = new CompletableFuture<>();
    private final CompletableFuture<Path> stopped = new CompletableFuture<>();
    private final Consumer<Throwable> failureListener;
    private final Consumer<ReplayRecord> droppedEnhancementListener;
    private final Thread writerThread;
    private volatile Path archiveDirectory;

    private long lastAcceptedSequence;
    private long lastAcceptedArchiveNanos;
    private volatile long stopArchiveNanos;
    private volatile long stopServerTick;

    public RecordingPipeline(
            Path archiveRoot,
            ArchiveManifest manifest,
            RecordingSettings settings,
            Consumer<Throwable> failureListener
    ) {
        this(archiveRoot, manifest, settings, failureListener, ignored -> {
        });
    }

    public RecordingPipeline(
            Path archiveRoot,
            ArchiveManifest manifest,
            RecordingSettings settings,
            Consumer<Throwable> failureListener,
            Consumer<ReplayRecord> droppedEnhancementListener
    ) {
        this.archiveRoot = Objects.requireNonNull(archiveRoot, "archiveRoot");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        this.droppedEnhancementListener = Objects.requireNonNull(
                droppedEnhancementListener,
                "droppedEnhancementListener"
        );
        this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
        this.writerThread = Thread.ofPlatform()
                .name("DreamingRecall-ArchiveWriter-" + manifest.archiveId().toString().substring(0, 8))
                .daemon(true)
                .unstarted(this::runWriter);
    }

    public void start() {
        if (!state.compareAndSet(PipelineState.NEW, PipelineState.STARTING)) {
            throw new IllegalStateException("Recording pipeline has already been started");
        }
        writerThread.start();
    }

    public OfferResult offer(ReplayRecord record) {
        Objects.requireNonNull(record, "record");
        PipelineState current = state.get();
        if (current != PipelineState.STARTING && current != PipelineState.RUNNING) {
            return OfferResult.REJECTED_NOT_RUNNING;
        }

        int retainedBytes = estimatedRetainedBytes(record);
        if (record.payloadSize() > settings.maxRecordBytes() || retainedBytes > settings.maxQueuedBytes()) {
            return rejectForSize(record);
        }

        synchronized (offerLock) {
            if (record.priority() != RecordPriority.ENHANCEMENT) {
                evictEnhancementsUntilFits(retainedBytes);
            }
            long currentBytes = queuedBytes.get();
            if (currentBytes + retainedBytes <= settings.maxQueuedBytes()) {
                long orderedArchiveNanos = Math.max(lastAcceptedArchiveNanos, record.archiveNanos());
                ReplayRecord orderedRecord = withArchiveNanos(record, orderedArchiveNanos);
                long sequence = lastAcceptedSequence + 1;
                QueuedRecord queued = new QueuedRecord(sequence, orderedRecord, retainedBytes);
                if (queue.offer(queued)) {
                    lastAcceptedSequence = sequence;
                    lastAcceptedArchiveNanos = orderedArchiveNanos;
                    long updatedBytes = queuedBytes.addAndGet(retainedBytes);
                    metrics.accepted(queue.size(), updatedBytes);
                    return OfferResult.ACCEPTED;
                }
            }
            return rejectForCapacity(record, lastAcceptedSequence);
        }
    }

    private void evictEnhancementsUntilFits(int retainedBytes) {
        while (queue.remainingCapacity() == 0
                || queuedBytes.get() + retainedBytes > settings.maxQueuedBytes()) {
            QueuedRecord candidate = null;
            for (QueuedRecord queued : queue) {
                if (queued.record().priority() == RecordPriority.ENHANCEMENT) {
                    candidate = queued;
                    break;
                }
            }
            if (candidate == null) {
                return;
            }
            if (queue.remove(candidate)) {
                queuedBytes.addAndGet(-candidate.retainedBytes());
                metrics.evictedEnhancement();
                notifyEnhancementDropped(candidate.record());
            }
        }
    }

    private OfferResult rejectForSize(ReplayRecord record) {
        if (record.priority() == RecordPriority.ENHANCEMENT) {
            metrics.droppedEnhancement();
            notifyEnhancementDropped(record);
            return OfferResult.DROPPED_ENHANCEMENT;
        }
        synchronized (offerLock) {
            return rejectForCapacity(record, lastAcceptedSequence);
        }
    }

    private OfferResult rejectForCapacity(ReplayRecord record, long boundarySequence) {
        if (record.priority() == RecordPriority.ENHANCEMENT) {
            metrics.droppedEnhancement();
            notifyEnhancementDropped(record);
            return OfferResult.DROPPED_ENHANCEMENT;
        }
        metrics.droppedCore();
        gaps.record(boundarySequence, record.archiveNanos());
        baselineRequired.set(true);
        gapGeneration.incrementAndGet();
        return record.payloadSize() > settings.maxRecordBytes()
                ? OfferResult.REJECTED_TOO_LARGE
                : OfferResult.CORE_GAP_STARTED;
    }

    private void notifyEnhancementDropped(ReplayRecord record) {
        try {
            droppedEnhancementListener.accept(record);
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Dropped-enhancement listener failed", failure);
        }
    }

    public boolean requiresBaseline() {
        return baselineRequired.get();
    }

    public long baselineGeneration() {
        return gapGeneration.get();
    }

    public boolean markBaselineComplete(long generation) {
        return gapGeneration.get() == generation && baselineRequired.compareAndSet(true, false);
    }

    public void requestStop(long archiveNanos, long serverTick) {
        stopArchiveNanos = Math.max(0, archiveNanos);
        stopServerTick = serverTick;
        stopRequested.set(true);
        state.updateAndGet(current -> switch (current) {
            case STARTING, RUNNING -> PipelineState.STOPPING;
            default -> current;
        });
    }

    public PipelineState state() {
        return state.get();
    }

    public RecordingMetricsSnapshot metrics() {
        return metrics.snapshot(queue.size(), queuedBytes.get());
    }

    public CompletableFuture<Path> readyFuture() {
        return ready;
    }

    public CompletableFuture<Path> stoppedFuture() {
        return stopped;
    }

    public Optional<Throwable> failure() {
        if (!stopped.isCompletedExceptionally()) {
            return Optional.empty();
        }
        try {
            stopped.join();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.ofNullable(failure.getCause());
        }
    }

    /**
     * Copies an optional server resource pack into the already-created archive.
     * This method is intentionally explicit: no mod JAR or arbitrary server
     * asset is discovered or bundled implicitly.
     */
    public Optional<ArchiveAttachmentStore.AttachmentReference> attachResourcePack(
            Path source,
            long maxBytes
    ) throws IOException {
        Path directory = archiveDirectory;
        if (directory == null || state.get() == PipelineState.FAILED || state.get() == PipelineState.STOPPED) {
            return Optional.empty();
        }
        return Optional.of(new ArchiveAttachmentStore(directory).put(source, maxBytes));
    }

    private void runWriter() {
        Path archiveDirectory = null;
        PersistedCheckpointWriter checkpointWriter = null;
        try {
            archiveDirectory = ArchiveLayout.initialize(archiveRoot, manifest);
            this.archiveDirectory = archiveDirectory;
            ContentAddressedStore contentStore = new ContentAddressedStore(archiveDirectory, settings.compressionLevel());
            ready.complete(archiveDirectory);
            state.compareAndSet(PipelineState.STARTING, PipelineState.RUNNING);

            long sequence = 0;
            long processedQueueSequence = 0;
            long segmentOpenedAt = System.nanoTime();
            long lastCheckpointNanos = Long.MIN_VALUE;
            long lastWrittenArchiveNanos = 0;
            int estimatedSegmentBytes = 0;
            ArrayList<ReplayRecord> segment = new ArrayList<>();
            ReplayCheckpointBuilder checkpointBuilder = new ReplayCheckpointBuilder();
            checkpointWriter = new PersistedCheckpointWriter(
                    archiveDirectory.resolve("checkpoints"),
                    settings.compressionLevel(),
                    manifest.archiveId().toString().substring(0, 8)
            );
            boolean checkpointingAvailable = true;
            ReplayRecord sessionStart = ReplayRecord.control(
                    CoreRecordType.SESSION_START,
                    0,
                    0,
                    manifest.archiveId().toString().getBytes(StandardCharsets.UTF_8)
            );
            segment.add(sessionStart);
            estimatedSegmentBytes += estimatedRetainedBytes(sessionStart);

            while (!stopRequested.get() || !queue.isEmpty()) {
                QueuedRecord queued = queue.poll(settings.writerPollInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (queued != null) {
                    queuedBytes.addAndGet(-queued.retainedBytes());
                    ReplayRecord orderedRecord = withArchiveNanos(
                            queued.record(),
                            Math.max(lastWrittenArchiveNanos, queued.record().archiveNanos())
                    );
                    ReplayRecord archivedRecord = externalizeContent(contentStore, orderedRecord);
                    segment.add(archivedRecord);
                    lastWrittenArchiveNanos = archivedRecord.archiveNanos();
                    estimatedSegmentBytes += estimatedRetainedBytes(archivedRecord);
                    processedQueueSequence = queued.sequence();
                    if (checkpointingAvailable) {
                        try {
                            checkpointBuilder.accept(orderedRecord, archivedRecord);
                        } catch (IOException | RuntimeException checkpointFailure) {
                            checkpointingAvailable = false;
                            checkpointWriter.disable();
                            LOGGER.log(
                                    System.Logger.Level.WARNING,
                                    "Disabling persisted replay checkpoints for this archive after a state decode failure",
                                    checkpointFailure
                            );
                        }
                    }
                }

                Optional<GapSnapshot> gap = gaps.drainReady(processedQueueSequence);
                if (gap.isPresent()) {
                    ReplayRecord gapRecord = withArchiveNanos(
                            gapRecord(gap.get(), stopServerTick),
                            Math.max(lastWrittenArchiveNanos, gap.get().endArchiveNanos())
                    );
                    segment.add(gapRecord);
                    lastWrittenArchiveNanos = gapRecord.archiveNanos();
                    estimatedSegmentBytes += estimatedRetainedBytes(gapRecord);
                }

                boolean durationReached = !segment.isEmpty()
                        && System.nanoTime() - segmentOpenedAt >= settings.segmentDuration().toNanos();
                boolean sizeReached = estimatedSegmentBytes >= settings.segmentTargetBytes();
                if ((durationReached || sizeReached) && !segment.isEmpty()) {
                    SegmentMetadata committed = SegmentCodec.commit(
                            archiveDirectory.resolve("segments"),
                            sequence++,
                            segment,
                            settings.compressionLevel()
                    );
                    metrics.committed(committed);
                    if (checkpointingAvailable
                            && checkpointBuilder.canCheckpoint()
                            && (lastCheckpointNanos == Long.MIN_VALUE
                            || committed.endArchiveNanos() - lastCheckpointNanos
                            >= settings.checkpointInterval().toNanos())) {
                        boolean submitted = checkpointWriter.submit(
                                committed,
                                checkpointBuilder.snapshotRecords(
                                        committed.endArchiveNanos(),
                                        committed.endArchiveNanos() == 0 ? 0 : segment.getLast().serverTick()
                                )
                        );
                        checkpointingAvailable = checkpointWriter.isAvailable();
                        if (submitted) {
                            lastCheckpointNanos = committed.endArchiveNanos();
                        }
                    }
                    segment = new ArrayList<>();
                    estimatedSegmentBytes = 0;
                    segmentOpenedAt = System.nanoTime();
                }
            }

            Optional<GapSnapshot> finalGap = gaps.drainReady(Long.MAX_VALUE);
            if (finalGap.isPresent()) {
                ReplayRecord gapRecord = withArchiveNanos(
                        gapRecord(finalGap.get(), stopServerTick),
                        Math.max(lastWrittenArchiveNanos, finalGap.get().endArchiveNanos())
                );
                segment.add(gapRecord);
                lastWrittenArchiveNanos = gapRecord.archiveNanos();
            }
            long completionArchiveNanos = Math.max(lastWrittenArchiveNanos, stopArchiveNanos);
            ReplayRecord sessionEnd = withArchiveNanos(ReplayRecord.control(
                    CoreRecordType.SESSION_END,
                    completionArchiveNanos,
                    stopServerTick,
                    new byte[0]
            ), completionArchiveNanos);
            segment.add(sessionEnd);
            lastWrittenArchiveNanos = sessionEnd.archiveNanos();
            SegmentMetadata committed = SegmentCodec.commit(
                    archiveDirectory.resolve("segments"),
                    sequence++,
                    segment,
                    settings.compressionLevel()
            );
            metrics.committed(committed);
            if (checkpointingAvailable && checkpointBuilder.canCheckpoint()) {
                checkpointWriter.submit(
                        committed,
                        checkpointBuilder.snapshotRecords(completionArchiveNanos, stopServerTick)
                );
            }
            checkpointWriter.close();

            RecordingMetricsSnapshot snapshot = metrics();
            ArchiveManifestCodec.writeCompletion(archiveDirectory, new ArchiveCompletion(
                    Instant.now().toEpochMilli(),
                    completionArchiveNanos,
                    sequence,
                    snapshot.acceptedRecords(),
                    snapshot.droppedEnhancementRecords(),
                    snapshot.droppedCoreRecords(),
                    true
            ));
            state.set(PipelineState.STOPPED);
            stopped.complete(archiveDirectory);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(archiveDirectory, interrupted);
        } catch (Throwable failure) {
            fail(archiveDirectory, failure);
        } finally {
            if (checkpointWriter != null) {
                checkpointWriter.close();
            }
        }
    }

    private void fail(Path archiveDirectory, Throwable failure) {
        state.set(PipelineState.FAILED);
        this.archiveDirectory = archiveDirectory;
        ready.completeExceptionally(failure);
        stopped.completeExceptionally(failure);
        try {
            failureListener.accept(failure);
        } catch (Throwable listenerFailure) {
            failure.addSuppressed(listenerFailure);
        }
    }

    private static int estimatedRetainedBytes(ReplayRecord record) {
        int dimensionBytes = record.dimensionId().getBytes(StandardCharsets.UTF_8).length;
        return 64 + dimensionBytes + record.payloadSize();
    }

    private static ReplayRecord withArchiveNanos(ReplayRecord record, long archiveNanos) {
        if (record.archiveNanos() == archiveNanos) {
            return record;
        }
        return new ReplayRecord(
                record.typeId(),
                record.priority(),
                archiveNanos,
                record.serverTick(),
                record.dimensionId(),
                record.payloadCopy()
        );
    }

    private static ReplayRecord externalizeContent(ContentAddressedStore store, ReplayRecord record) throws IOException {
        if (record.typeId() != CoreRecordType.CHUNK_BASELINE.id()) {
            return record;
        }
        byte[] payload = record.payloadCopy();
        ChunkIdentity chunk = readAvailableChunkIdentity(payload);
        if (chunk == null) {
            return record;
        }
        ContentReference reference = store.put(payload);
        byte[] fallback = chunkFallback(chunk, reference);
        return new ReplayRecord(
                record.typeId(),
                record.priority(),
                record.archiveNanos(),
                record.serverTick(),
                record.dimensionId(),
                ContentReferenceCodec.encode(reference, fallback)
        );
    }

    private static ChunkIdentity readAvailableChunkIdentity(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int schema = input.readInt();
            if ((schema != 1 && schema != 2) || !input.readBoolean()) {
                return null;
            }
            return new ChunkIdentity(input.readInt(), input.readInt());
        } catch (IOException ignored) {
            return null;
        }
    }

    private static byte[] chunkFallback(ChunkIdentity chunk, ContentReference reference) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(2);
            output.writeBoolean(false);
            output.writeInt(chunk.x());
            output.writeInt(chunk.z());
            byte[] reason = ("content_unavailable:" + reference.hexHash()).getBytes(StandardCharsets.UTF_8);
            output.writeInt(reason.length);
            output.write(reason);
        }
        return bytes.toByteArray();
    }

    private static ReplayRecord gapRecord(GapSnapshot gap, long serverTick) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(gap.startArchiveNanos());
                output.writeLong(gap.endArchiveNanos());
                output.writeLong(gap.droppedRecords());
            }
            return ReplayRecord.control(
                    CoreRecordType.RECORDING_GAP,
                    gap.startArchiveNanos(),
                    serverTick,
                    bytes.toByteArray()
            );
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory gap encoding failed", impossible);
        }
    }

    private record QueuedRecord(long sequence, ReplayRecord record, int retainedBytes) {
    }

    private record ChunkIdentity(int x, int z) {
    }

    private static final class PersistedCheckpointWriter implements AutoCloseable {
        private final Path directory;
        private final int compressionLevel;
        private final ThreadPoolExecutor executor;
        private final AtomicBoolean available = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean();

        private PersistedCheckpointWriter(Path directory, int compressionLevel, String archiveLabel) {
            this.directory = directory;
            this.compressionLevel = compressionLevel;
            this.executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    task -> {
                        Thread thread = Thread.ofPlatform()
                                .name("DreamingRecall-CheckpointWriter-" + archiveLabel)
                                .daemon(true)
                                .unstarted(task);
                        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }

        private synchronized boolean submit(SegmentMetadata throughSegment, List<ReplayRecord> records) {
            if (!isAvailable() || closed.get()) {
                return false;
            }
            Runnable task = () -> persist(throughSegment, records);
            try {
                executor.execute(task);
                return true;
            } catch (RejectedExecutionException full) {
                if (executor.isShutdown()) {
                    return false;
                }
                executor.getQueue().poll();
                try {
                    executor.execute(task);
                    return true;
                } catch (RejectedExecutionException ignored) {
                    return false;
                }
            }
        }

        private void persist(SegmentMetadata throughSegment, List<ReplayRecord> records) {
            if (!available.get()) {
                return;
            }
            try {
                SegmentCodec.commit(
                        directory,
                        throughSegment.sequence(),
                        records,
                        compressionLevel
                );
            } catch (IOException | RuntimeException checkpointFailure) {
                available.set(false);
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Could not persist replay state checkpoint; core archive recording will continue",
                        checkpointFailure
                );
            }
        }

        private boolean isAvailable() {
            return available.get();
        }

        private void disable() {
            available.set(false);
            executor.getQueue().clear();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private record GapSnapshot(long boundarySequence, long startArchiveNanos, long endArchiveNanos, long droppedRecords) {
    }

    private static final class GapAccumulator {
        private boolean pending;
        private long boundarySequence;
        private long startArchiveNanos;
        private long endArchiveNanos;
        private long droppedRecords;

        synchronized void record(long boundary, long archiveNanos) {
            if (!pending) {
                pending = true;
                boundarySequence = boundary;
                startArchiveNanos = archiveNanos;
                endArchiveNanos = archiveNanos;
                droppedRecords = 1;
                return;
            }
            boundarySequence = Math.max(boundarySequence, boundary);
            startArchiveNanos = Math.min(startArchiveNanos, archiveNanos);
            endArchiveNanos = Math.max(endArchiveNanos, archiveNanos);
            droppedRecords++;
        }

        synchronized Optional<GapSnapshot> drainReady(long processedSequence) {
            if (!pending || processedSequence < boundarySequence) {
                return Optional.empty();
            }
            GapSnapshot snapshot = new GapSnapshot(
                    boundarySequence,
                    startArchiveNanos,
                    endArchiveNanos,
                    droppedRecords
            );
            pending = false;
            return Optional.of(snapshot);
        }
    }
}
