package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.archive.ArchiveIndex;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.PacketScope;
import com.hhy.dreamingrecall.archive.packet.PacketTrackStatusCodec;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.archive.track.TrackNames;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketReplayIndexerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID SECOND_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000043");

    @Test
    void indexesBootstrapBeforePlayAndKeepsTrackMetadata() throws Exception {
        SegmentMetadata first = segment(0, 0, 119);
        SegmentMetadata second = segment(1, 120, 400);
        FakeSource source = new FakeSource(
                List.of(first, second),
                List.of(
                        record(0, ProtocolPhase.LOGIN, "minecraft:game_profile", TrackNames.CONFIGURATION),
                        record(40, ProtocolPhase.CONFIGURATION, "minecraft:registry_data", TrackNames.CONFIGURATION),
                        record(120, ProtocolPhase.PLAY, "minecraft:bundle_delimiter", TrackNames.playerClient(PLAYER)),
                        record(140, ProtocolPhase.PLAY, "minecraft:login", TrackNames.playerClient(PLAYER)),
                        record(400, ProtocolPhase.PLAY, "minecraft:level_chunk_with_light", TrackNames.SHARED_WORLD)
                )
        );

        PacketReplayIndex index = PacketReplayIndexer.scan(source, new ReadCancellation());

        assertEquals(400, index.durationNanos());
        assertEquals(120, index.firstPlayNanos());
        assertEquals(140, index.worldStartNanos());
        assertEquals(5, index.packetCount());
        assertEquals(2, index.bootstrapFrames().size());
        assertEquals(
                Set.of(TrackNames.CONFIGURATION, TrackNames.playerClient(PLAYER), TrackNames.SHARED_WORLD),
                index.tracks()
        );
    }

    @Test
    void requiresAWorldStartPacketToBePlayable() throws Exception {
        SegmentMetadata segment = segment(0, 0, 100);
        FakeSource source = new FakeSource(
                List.of(segment),
                List.of(record(
                        100,
                        ProtocolPhase.PLAY,
                        "minecraft:bundle_delimiter",
                        TrackNames.playerClient(PLAYER)
                ))
        );

        PacketReplayIndex index = PacketReplayIndexer.scan(source, new ReadCancellation());

        assertEquals(-1, index.worldStartNanos());
        org.junit.jupiter.api.Assertions.assertFalse(index.playable());
    }

    @Test
    void cancellationStopsAnIndexScanBeforeReadingTheArchive() {
        ReadCancellation cancellation = new ReadCancellation();
        cancellation.cancel();
        FakeSource source = new FakeSource(List.of(segment(0, 0, 10)), List.of());

        assertThrows(CancellationException.class, () -> PacketReplayIndexer.scan(source, cancellation));
    }

    @Test
    void keepsPlayerBootstrapsSeparateAndExcludesAnIncompleteTrack() throws Exception {
        SegmentMetadata segment = segment(0, 0, 300);
        FakeSource source = new FakeSource(
                List.of(segment),
                List.of(
                        record(0, ProtocolPhase.LOGIN, "minecraft:game_profile", TrackNames.playerClient(PLAYER)),
                        record(10, ProtocolPhase.CONFIGURATION, "minecraft:registry_data", TrackNames.playerClient(PLAYER)),
                        record(50, ProtocolPhase.PLAY, "minecraft:login", TrackNames.playerClient(PLAYER)),
                        record(100, ProtocolPhase.LOGIN, "minecraft:game_profile", TrackNames.playerClient(SECOND_PLAYER)),
                        record(110, ProtocolPhase.CONFIGURATION, "minecraft:registry_data", TrackNames.playerClient(SECOND_PLAYER)),
                        record(150, ProtocolPhase.PLAY, "minecraft:login", TrackNames.playerClient(SECOND_PLAYER)),
                        new ReplayRecord(
                                CoreRecordType.TRACK_CHECKPOINT.id(),
                                com.hhy.dreamingrecall.archive.RecordPriority.CONTROL,
                                200,
                                4,
                                "",
                                PacketTrackStatusCodec.encodeIncomplete(SECOND_PLAYER)
                        )
                )
        );

        PacketReplayIndex index = PacketReplayIndexer.scan(source, new ReadCancellation());

        assertEquals(Set.of(PLAYER), index.playablePlayers());
        assertEquals(2, index.playerTracks().get(PLAYER).bootstrapFrames().size());
        assertEquals(2, index.playerTracks().get(SECOND_PLAYER).bootstrapFrames().size());
        org.junit.jupiter.api.Assertions.assertFalse(index.playerTracks().get(SECOND_PLAYER).complete());
    }

    private static SegmentMetadata segment(long sequence, long start, long end) {
        return new SegmentMetadata(Path.of("segment-" + sequence), sequence, start, end, 2, 0, 0, 0);
    }

    private static ReplayRecord record(long time, ProtocolPhase phase, String packetId, String track)
            throws Exception {
        PacketEnvelope envelope = new PacketEnvelope(
                PacketEnvelope.CURRENT_SCHEMA_VERSION,
                track,
                phase,
                packetId,
                "minecraft",
                phase == ProtocolPhase.PLAY ? PacketScope.CLIENT_LOCAL : PacketScope.SESSION,
                phase == ProtocolPhase.PLAY ? "minecraft:overworld" : "",
                phase == ProtocolPhase.PLAY ? PLAYER : null,
                null,
                "",
                new byte[]{1, 2}
        );
        return ReplayRecord.core(
                CoreRecordType.PACKET_FRAME,
                time,
                time / 50,
                envelope.dimensionId(),
                PacketEnvelopeCodec.encode(envelope)
        );
    }

    private static final class FakeSource implements ArchiveDataSource {
        private final ArchiveIndex index;
        private final List<ReplayRecord> records;

        private FakeSource(List<SegmentMetadata> segments, List<ReplayRecord> records) {
            this.index = new ArchiveIndex(segments);
            this.records = records;
        }

        @Override
        public com.hhy.dreamingrecall.archive.ArchiveManifest manifest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ArchiveIndex index() {
            return index;
        }

        @Override
        public CompletableFuture<SegmentReadResult> readSegment(
                SegmentMetadata segment,
                ReadCancellation cancellation
        ) {
            return readRawSegment(segment, cancellation);
        }

        @Override
        public CompletableFuture<SegmentReadResult> readRawSegment(
                SegmentMetadata segment,
                ReadCancellation cancellation
        ) {
            cancellation.throwIfCancelled();
            List<ReplayRecord> selected = records.stream()
                    .filter(record -> record.archiveNanos() >= segment.startArchiveNanos()
                            && record.archiveNanos() <= segment.endArchiveNanos())
                    .toList();
            return CompletableFuture.completedFuture(new SegmentReadResult(segment, selected));
        }

        @Override
        public void close() {
        }
    }
}
