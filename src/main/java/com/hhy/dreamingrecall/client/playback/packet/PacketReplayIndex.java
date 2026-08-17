package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record PacketReplayIndex(
        long durationNanos,
        long packetCount,
        Map<UUID, PlayerTrack> playerTracks,
        Set<String> tracks
) {
    public PacketReplayIndex {
        if (durationNanos < 0 || packetCount < 0) {
            throw new IllegalArgumentException("Invalid packet replay index bounds");
        }
        playerTracks = Collections.unmodifiableMap(new LinkedHashMap<>(playerTracks));
        tracks = Set.copyOf(tracks);
    }

    public boolean playable() {
        return playerTracks.values().stream().anyMatch(PlayerTrack::playable);
    }

    public Set<UUID> playablePlayers() {
        java.util.LinkedHashSet<UUID> players = new java.util.LinkedHashSet<>();
        playerTracks.forEach((playerId, track) -> {
            if (track.playable()) {
                players.add(playerId);
            }
        });
        return Collections.unmodifiableSet(players);
    }

    public Optional<PlayerTrack> defaultTrack() {
        return playerTracks.values().stream().filter(PlayerTrack::playable).findFirst();
    }

    public Optional<PlayerTrack> track(UUID playerId) {
        return Optional.ofNullable(playerTracks.get(playerId)).filter(PlayerTrack::playable);
    }

    public long firstPlayNanos() {
        return defaultTrack().map(PlayerTrack::firstPlayNanos).orElse(0L);
    }

    public long worldStartNanos() {
        return defaultTrack().map(PlayerTrack::worldStartNanos).orElse(-1L);
    }

    public List<PacketEnvelope> bootstrapFrames() {
        return defaultTrack().map(PlayerTrack::bootstrapFrames).orElseGet(List::of);
    }

    public record PlayerTrack(
            UUID playerId,
            String trackId,
            long firstPlayNanos,
            long worldStartNanos,
            long packetCount,
            List<PacketEnvelope> bootstrapFrames,
            boolean complete
    ) {
        public PlayerTrack {
            if (firstPlayNanos < 0
                    || worldStartNanos < -1
                    || worldStartNanos >= 0 && worldStartNanos < firstPlayNanos
                    || packetCount < 0) {
                throw new IllegalArgumentException("Invalid player packet track bounds");
            }
            bootstrapFrames = List.copyOf(bootstrapFrames);
        }

        public boolean playable() {
            return complete && packetCount > 0 && worldStartNanos >= firstPlayNanos;
        }
    }
}
