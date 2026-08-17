package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.decode.PortableRecordDecoder;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReplayForwardCursor implements AutoCloseable {
    private final ArchiveDataSource source;
    private final List<SegmentMetadata> segments;
    private final ReplayStateAccumulator accumulator;
    private final PortableRecordDecoder decoder = new PortableRecordDecoder();
    private final Map<Integer, SegmentReadResult> segmentCache = new LinkedHashMap<>();
    private final Map<UUID, ReplayPlaybackFrame.TimedPlayerState> currentPlayers = new HashMap<>();
    private final Map<UUID, ReplayPlaybackFrame.TimedCameraSample> currentCameraSamples = new HashMap<>();
    private final Map<UUID, ReplayPlaybackFrame.TimedPlayerVisualSample> currentPlayerVisualSamples = new HashMap<>();
    private final ReadCancellation cancellation = new ReadCancellation();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("DreamingRecall-ForwardCursor-", 0).factory()
    );

    private int segmentIndex;
    private int recordIndex;
    private volatile long positionNanos;
    private ReplayWorldSnapshot cachedSnapshot;

    public ReplayForwardCursor(ArchiveDataSource source, ReplayWorldSnapshot initialState) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(initialState, "initialState");
        this.segments = source.index().segments();
        this.accumulator = new ReplayStateAccumulator(initialState);
        this.positionNanos = initialState.archiveNanos();
        this.cachedSnapshot = initialState;
        seedMotionState(initialState);
        while (segmentIndex < segments.size()
                && segments.get(segmentIndex).endArchiveNanos() <= positionNanos) {
            segmentIndex++;
        }
    }

    public synchronized CompletableFuture<ReplayWorldSnapshot> advanceTo(long targetArchiveNanos) {
        if (targetArchiveNanos < positionNanos) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Forward cursor cannot move backwards")
            );
        }
        return CompletableFuture.supplyAsync(() -> advanceBlocking(targetArchiveNanos), executor);
    }

    public synchronized CompletableFuture<ReplayPlaybackFrame> advanceFrameTo(
            long targetArchiveNanos,
            long lookaheadNanos
    ) {
        if (targetArchiveNanos < positionNanos) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Forward cursor cannot move backwards")
            );
        }
        if (lookaheadNanos < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("lookaheadNanos must be non-negative")
            );
        }
        return CompletableFuture.supplyAsync(
                () -> advanceFrameBlocking(targetArchiveNanos, lookaheadNanos),
                executor
        );
    }

    public synchronized long positionNanos() {
        return positionNanos;
    }

    @Override
    public void close() {
        cancellation.cancel();
        executor.shutdownNow();
    }

    private ReplayWorldSnapshot advanceBlocking(long targetArchiveNanos) {
        advanceFrameBlocking(targetArchiveNanos, 0);
        cachedSnapshot = accumulator.snapshotAt(targetArchiveNanos);
        return cachedSnapshot;
    }

    private ReplayPlaybackFrame advanceFrameBlocking(long targetArchiveNanos, long lookaheadNanos) {
        cancellation.throwIfCancelled();
        if (targetArchiveNanos < positionNanos) {
            throw new IllegalArgumentException("Forward cursor cannot move backwards");
        }
        boolean worldStateChanged = false;
        while (segmentIndex < segments.size()) {
            SegmentMetadata metadata = segments.get(segmentIndex);
            if (metadata.startArchiveNanos() > targetArchiveNanos) {
                break;
            }
            List<ReplayRecord> records = segment(segmentIndex).records();
            while (recordIndex < records.size()) {
                cancellation.throwIfCancelled();
                ReplayRecord record = records.get(recordIndex);
                if (record.archiveNanos() <= positionNanos) {
                    recordIndex++;
                    continue;
                }
                if (record.archiveNanos() > targetArchiveNanos) {
                    positionNanos = targetArchiveNanos;
                    ReplayWorldSnapshot targetSnapshot = snapshotAt(targetArchiveNanos, worldStateChanged);
                    return frameWithLookahead(targetSnapshot, lookaheadNanos, worldStateChanged);
                }
                accumulator.apply(record);
                worldStateChanged |= trackAppliedRecord(record);
                recordIndex++;
            }
            segmentCache.remove(segmentIndex);
            recordIndex = 0;
            segmentIndex++;
        }
        positionNanos = targetArchiveNanos;
        ReplayWorldSnapshot targetSnapshot = snapshotAt(targetArchiveNanos, worldStateChanged);
        return frameWithLookahead(targetSnapshot, lookaheadNanos, worldStateChanged);
    }

    private ReplayPlaybackFrame frameWithLookahead(
            ReplayWorldSnapshot targetSnapshot,
            long lookaheadNanos,
            boolean worldStateChanged
    ) {
        Map<UUID, ReplayPlaybackFrame.TimedPlayerState> nextPlayers = new HashMap<>();
        Map<UUID, ReplayPlaybackFrame.TimedCameraSample> nextCameras = new HashMap<>();
        Map<UUID, ReplayPlaybackFrame.TimedPlayerVisualSample> nextPlayerVisuals = new HashMap<>();
        Set<UUID> blockedPlayers = new HashSet<>();
        Set<UUID> blockedCameras = new HashSet<>();
        Set<UUID> blockedPlayerVisuals = new HashSet<>();
        long lookaheadEnd = positionNanos > Long.MAX_VALUE - lookaheadNanos
                ? Long.MAX_VALUE
                : positionNanos + lookaheadNanos;
        int scanSegment = segmentIndex;
        int scanRecord = recordIndex;
        boolean continuityBroken = false;

        while (!continuityBroken && scanSegment < segments.size()) {
            SegmentMetadata metadata = segments.get(scanSegment);
            if (metadata.startArchiveNanos() > lookaheadEnd) {
                break;
            }
            List<ReplayRecord> records = segment(scanSegment).records();
            while (scanRecord < records.size()) {
                cancellation.throwIfCancelled();
                ReplayRecord record = records.get(scanRecord++);
                if (record.archiveNanos() <= positionNanos) {
                    continue;
                }
                if (record.archiveNanos() > lookaheadEnd) {
                    continuityBroken = true;
                    break;
                }
                CoreRecordType type = CoreRecordType.fromId(record.typeId()).orElse(null);
                if (type == CoreRecordType.BASELINE_BEGIN || type == CoreRecordType.RECORDING_GAP) {
                    continuityBroken = true;
                    break;
                }
                if (type != CoreRecordType.PLAYER_STATE
                        && type != CoreRecordType.CLIENT_CAMERA_SAMPLE
                        && type != CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE
                        && type != CoreRecordType.ENTITY_REMOVE) {
                    continue;
                }
                try {
                    DecodedPayload payload = decoder.decode(record);
                    if (payload instanceof DecodedPayload.PlayerState player
                            && !blockedPlayers.contains(player.uuid())) {
                        nextPlayers.putIfAbsent(player.uuid(), new ReplayPlaybackFrame.TimedPlayerState(
                                record.archiveNanos(), record.dimensionId(), player
                        ));
                    } else if (payload instanceof DecodedPayload.CameraSample camera
                            && !blockedCameras.contains(camera.playerId())) {
                        nextCameras.putIfAbsent(camera.playerId(), new ReplayPlaybackFrame.TimedCameraSample(
                                record.archiveNanos(), record.dimensionId(), camera
                        ));
                    } else if (payload instanceof DecodedPayload.PlayerVisualSample visual
                            && !blockedPlayerVisuals.contains(visual.playerId())) {
                        nextPlayerVisuals.putIfAbsent(
                                visual.playerId(),
                                new ReplayPlaybackFrame.TimedPlayerVisualSample(
                                        record.archiveNanos(), record.dimensionId(), visual
                                )
                        );
                    } else if (payload instanceof DecodedPayload.EntityRemove removal) {
                        if (!nextPlayers.containsKey(removal.uuid())) {
                            blockedPlayers.add(removal.uuid());
                        }
                        if (!nextCameras.containsKey(removal.uuid())) {
                            blockedCameras.add(removal.uuid());
                        }
                        if (!nextPlayerVisuals.containsKey(removal.uuid())) {
                            blockedPlayerVisuals.add(removal.uuid());
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // The accumulator will report malformed records when they become current.
                }
            }
            if (scanRecord >= records.size()) {
                scanSegment++;
                scanRecord = 0;
            }
        }
        trimSegmentCache();
        return new ReplayPlaybackFrame(
                targetSnapshot,
                currentPlayers,
                nextPlayers,
                currentCameraSamples,
                nextCameras,
                currentPlayerVisualSamples,
                nextPlayerVisuals,
                worldStateChanged
        );
    }

    private boolean trackAppliedRecord(ReplayRecord record) {
        CoreRecordType type = CoreRecordType.fromId(record.typeId()).orElse(null);
        if (type == null) {
            return true;
        }
        if (type == CoreRecordType.BASELINE_BEGIN || type == CoreRecordType.RECORDING_GAP) {
            currentPlayers.clear();
            currentCameraSamples.clear();
            currentPlayerVisualSamples.clear();
            return true;
        }
        if (type != CoreRecordType.PLAYER_STATE
                && type != CoreRecordType.CLIENT_CAMERA_SAMPLE
                && type != CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE
                && type != CoreRecordType.ENTITY_REMOVE) {
            return type != CoreRecordType.SESSION_START
                    && type != CoreRecordType.SESSION_END
                    && type != CoreRecordType.SERVER_TICK
                    && type != CoreRecordType.EXTENSION_PAYLOAD;
        }
        try {
            DecodedPayload payload = decoder.decode(record);
            if (payload instanceof DecodedPayload.PlayerState player) {
                ReplayPlaybackFrame.TimedPlayerState previous = currentPlayers.get(player.uuid());
                currentPlayers.put(player.uuid(), new ReplayPlaybackFrame.TimedPlayerState(
                        record.archiveNanos(), record.dimensionId(), player
                ));
                return previous == null
                        || !previous.dimensionId().equals(record.dimensionId())
                        || playerMetadataChanged(previous.state(), player);
            }
            if (payload instanceof DecodedPayload.CameraSample camera) {
                currentCameraSamples.put(camera.playerId(), new ReplayPlaybackFrame.TimedCameraSample(
                        record.archiveNanos(), record.dimensionId(), camera
                ));
                return false;
            }
            if (payload instanceof DecodedPayload.PlayerVisualSample visual) {
                currentPlayerVisualSamples.put(
                        visual.playerId(),
                        new ReplayPlaybackFrame.TimedPlayerVisualSample(
                                record.archiveNanos(), record.dimensionId(), visual
                        )
                );
                return false;
            }
            if (payload instanceof DecodedPayload.EntityRemove removal) {
                currentPlayers.remove(removal.uuid());
                currentCameraSamples.remove(removal.uuid());
                currentPlayerVisualSamples.remove(removal.uuid());
            }
        } catch (IOException | RuntimeException ignored) {
            return true;
        }
        return true;
    }

    private static boolean playerMetadataChanged(
            DecodedPayload.PlayerState previous,
            DecodedPayload.PlayerState current
    ) {
        return !previous.uuid().equals(current.uuid())
                || !previous.name().equals(current.name())
                || Float.compare(previous.health(), current.health()) != 0
                || Float.compare(previous.absorption(), current.absorption()) != 0
                || previous.foodLevel() != current.foodLevel()
                || previous.selectedSlot() != current.selectedSlot()
                || !previous.gameMode().equals(current.gameMode())
                || !previous.equipment().equals(current.equipment());
    }

    private ReplayWorldSnapshot snapshotAt(long targetArchiveNanos, boolean worldStateChanged) {
        if (worldStateChanged) {
            cachedSnapshot = accumulator.snapshotAt(targetArchiveNanos);
            return cachedSnapshot;
        }
        if (cachedSnapshot.archiveNanos() == targetArchiveNanos) {
            return cachedSnapshot;
        }
        return new ReplayWorldSnapshot(
                targetArchiveNanos,
                cachedSnapshot.serverTick(),
                cachedSnapshot.baselineComplete(),
                cachedSnapshot.dimensions(),
                cachedSnapshot.recentChat(),
                cachedSnapshot.recentSounds(),
                cachedSnapshot.recentEntityEffects(),
                cachedSnapshot.gaps(),
                cachedSnapshot.diagnostics()
        );
    }

    private SegmentReadResult segment(int index) {
        SegmentReadResult cached = segmentCache.get(index);
        if (cached != null) {
            return cached;
        }
        SegmentReadResult loaded = source.readSegment(segments.get(index), cancellation).join();
        segmentCache.put(index, loaded);
        return loaded;
    }

    private void trimSegmentCache() {
        segmentCache.keySet().removeIf(index -> index < segmentIndex);
        while (segmentCache.size() > 4) {
            Integer furthest = segmentCache.keySet().stream().max(Integer::compareTo).orElse(null);
            if (furthest == null || furthest == segmentIndex) {
                break;
            }
            segmentCache.remove(furthest);
        }
    }

    private void seedMotionState(ReplayWorldSnapshot initialState) {
        initialState.dimensions().forEach((dimensionId, dimension) -> {
            dimension.players().forEach((uuid, player) -> currentPlayers.put(
                    uuid,
                    new ReplayPlaybackFrame.TimedPlayerState(initialState.archiveNanos(), dimensionId, player)
            ));
            dimension.cameraSamples().forEach((uuid, camera) -> currentCameraSamples.put(
                    uuid,
                    new ReplayPlaybackFrame.TimedCameraSample(initialState.archiveNanos(), dimensionId, camera)
            ));
            dimension.playerVisualSamples().forEach((uuid, visual) -> currentPlayerVisualSamples.put(
                    uuid,
                    new ReplayPlaybackFrame.TimedPlayerVisualSample(
                            initialState.archiveNanos(), dimensionId, visual
                    )
            ));
        });
    }
}
