package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class PacketReplayIndexer {
    private PacketReplayIndexer() {
    }

    public static PacketReplayIndex scan(ArchiveDataSource source, ReadCancellation cancellation) throws IOException {
        ArrayList<PacketEnvelope> bootstrap = new ArrayList<>();
        LinkedHashSet<String> tracks = new LinkedHashSet<>();
        long packetCount = 0;
        long firstPlay = Long.MAX_VALUE;
        long worldStart = Long.MAX_VALUE;
        boolean playStarted = false;

        for (var segment : source.index().segments()) {
            cancellation.throwIfCancelled();
            var read = source.readRawSegment(segment, cancellation).join();
            for (var record : read.records()) {
                cancellation.throwIfCancelled();
                if (record.typeId() != CoreRecordType.PACKET_FRAME.id()) {
                    continue;
                }
                PacketEnvelope envelope = PacketEnvelopeCodec.decode(record.payloadCopy());
                packetCount++;
                tracks.add(envelope.trackId());
                if (envelope.phase() == ProtocolPhase.PLAY) {
                    playStarted = true;
                    firstPlay = Math.min(firstPlay, record.archiveNanos());
                    if (envelope.packetTypeId().equals("minecraft:login")) {
                        worldStart = Math.min(worldStart, record.archiveNanos());
                    }
                } else if (!playStarted) {
                    bootstrap.add(envelope);
                }
            }
        }

        long duration = source.index().segments().isEmpty()
                ? 0
                : source.index().segments().getLast().endArchiveNanos();
        if (firstPlay == Long.MAX_VALUE) {
            firstPlay = 0;
            packetCount = 0;
        }
        return new PacketReplayIndex(
                duration,
                firstPlay,
                worldStart == Long.MAX_VALUE ? -1 : worldStart,
                packetCount,
                bootstrap,
                tracks
        );
    }
}
