package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;

import java.util.List;
import java.util.Set;

public record PacketReplayIndex(
        long durationNanos,
        long firstPlayNanos,
        long worldStartNanos,
        long packetCount,
        List<PacketEnvelope> bootstrapFrames,
        Set<String> tracks
) {
    public PacketReplayIndex {
        if (durationNanos < 0
                || firstPlayNanos < 0
                || firstPlayNanos > durationNanos
                || worldStartNanos < -1
                || worldStartNanos > durationNanos
                || (worldStartNanos >= 0 && worldStartNanos < firstPlayNanos)
                || packetCount < 0) {
            throw new IllegalArgumentException("Invalid packet replay index bounds");
        }
        bootstrapFrames = List.copyOf(bootstrapFrames);
        tracks = Set.copyOf(tracks);
    }

    public boolean playable() {
        return packetCount > 0 && worldStartNanos >= 0;
    }
}
