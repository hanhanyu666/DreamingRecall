package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.PacketTrackStatusCodec;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.archive.track.TrackNames;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class PacketReplayIndexer {
    private PacketReplayIndexer() {
    }

    public static PacketReplayIndex scan(ArchiveDataSource source, ReadCancellation cancellation) throws IOException {
        ArrayList<PacketEnvelope> sharedBootstrap = new ArrayList<>();
        LinkedHashMap<UUID, TrackBuilder> playerTracks = new LinkedHashMap<>();
        LinkedHashSet<String> tracks = new LinkedHashSet<>();
        HashSet<UUID> incomplete = new HashSet<>();
        long packetCount = 0;

        for (var segment : source.index().segments()) {
            cancellation.throwIfCancelled();
            var read = source.readRawSegment(segment, cancellation).join();
            for (var record : read.records()) {
                cancellation.throwIfCancelled();
                if (record.typeId() == CoreRecordType.TRACK_CHECKPOINT.id()) {
                    try {
                        PacketTrackStatusCodec.Status status = PacketTrackStatusCodec.decode(record.payloadCopy());
                        if (!status.complete()) {
                            incomplete.add(status.playerId());
                        }
                    } catch (IOException ignored) {
                        // Other checkpoint schemas are independent of packet-track health.
                    }
                    continue;
                }
                if (record.typeId() != CoreRecordType.PACKET_FRAME.id()) {
                    continue;
                }
                PacketEnvelope envelope = PacketEnvelopeCodec.decode(record.payloadCopy());
                packetCount++;
                tracks.add(envelope.trackId());
                java.util.Optional<UUID> playerId = TrackNames.playerClientId(envelope.trackId());
                if (playerId.isPresent()) {
                    playerTracks.computeIfAbsent(
                            playerId.get(),
                            ignored -> new TrackBuilder(playerId.get(), envelope.trackId())
                    ).accept(record.archiveNanos(), envelope);
                } else if (envelope.trackId().equals(TrackNames.CONFIGURATION)
                        && envelope.phase() != ProtocolPhase.PLAY) {
                    sharedBootstrap.add(envelope);
                }
            }
        }

        long duration = source.index().segments().isEmpty()
                ? 0
                : source.index().segments().getLast().endArchiveNanos();
        LinkedHashMap<UUID, PacketReplayIndex.PlayerTrack> built = new LinkedHashMap<>();
        playerTracks.forEach((playerId, builder) -> built.put(
                playerId,
                builder.build(sharedBootstrap, !incomplete.contains(playerId))
        ));
        return new PacketReplayIndex(duration, packetCount, built, tracks);
    }

    private static final class TrackBuilder {
        private final UUID playerId;
        private final String trackId;
        private final ArrayList<PacketEnvelope> bootstrap = new ArrayList<>();
        private long packetCount;
        private long firstPlayNanos = Long.MAX_VALUE;
        private long worldStartNanos = Long.MAX_VALUE;

        private TrackBuilder(UUID playerId, String trackId) {
            this.playerId = playerId;
            this.trackId = trackId;
        }

        private void accept(long archiveNanos, PacketEnvelope envelope) {
            packetCount++;
            if (envelope.phase() == ProtocolPhase.PLAY) {
                firstPlayNanos = Math.min(firstPlayNanos, archiveNanos);
                if (envelope.packetTypeId().equals("minecraft:login")) {
                    worldStartNanos = Math.min(worldStartNanos, archiveNanos);
                }
            } else {
                bootstrap.add(envelope);
            }
        }

        private PacketReplayIndex.PlayerTrack build(List<PacketEnvelope> sharedBootstrap, boolean complete) {
            return new PacketReplayIndex.PlayerTrack(
                    playerId,
                    trackId,
                    firstPlayNanos == Long.MAX_VALUE ? 0 : firstPlayNanos,
                    worldStartNanos == Long.MAX_VALUE ? -1 : worldStartNanos,
                    packetCount,
                    bootstrap.isEmpty() ? sharedBootstrap : bootstrap,
                    complete
            );
        }
    }
}
