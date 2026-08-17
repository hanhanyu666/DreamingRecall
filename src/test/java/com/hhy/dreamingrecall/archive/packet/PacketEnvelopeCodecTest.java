package com.hhy.dreamingrecall.archive.packet;

import com.hhy.dreamingrecall.archive.track.TrackNames;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketEnvelopeCodecTest {
    @Test
    void roundTripsAChunkPacketAndDefensivelyCopiesBytes() throws Exception {
        byte[] packet = {1, 2, 3, 4};
        UUID subject = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PacketEnvelope expected = new PacketEnvelope(
                PacketEnvelope.CURRENT_SCHEMA_VERSION,
                TrackNames.SHARED_WORLD,
                ProtocolPhase.PLAY,
                "minecraft:level_chunk_with_light",
                "minecraft",
                PacketScope.CHUNK,
                "minecraft:overworld",
                subject,
                new PacketEnvelope.ChunkPosition(12, -5),
                "abc123",
                packet
        );
        packet[0] = 99;

        PacketEnvelope decoded = PacketEnvelopeCodec.decode(PacketEnvelopeCodec.encode(expected));

        assertEquals(expected, decoded);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.packetBytes());
        byte[] exposed = decoded.packetBytes();
        exposed[0] = 42;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.packetBytes());
    }

    @Test
    void rejectsTruncationTrailingBytesAndInvalidScopeMetadata() throws Exception {
        PacketEnvelope envelope = new PacketEnvelope(
                1,
                TrackNames.CONFIGURATION,
                ProtocolPhase.CONFIGURATION,
                "minecraft:registry_data",
                "minecraft",
                PacketScope.SESSION,
                "",
                null,
                null,
                "",
                new byte[]{7, 8}
        );
        byte[] encoded = PacketEnvelopeCodec.encode(envelope);

        assertThrows(IOException.class, () -> PacketEnvelopeCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> PacketEnvelopeCodec.decode(trailing));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PacketEnvelope(
                        1,
                        TrackNames.SHARED_WORLD,
                        ProtocolPhase.PLAY,
                        "minecraft:block_update",
                        "minecraft",
                        PacketScope.CHUNK,
                        "minecraft:overworld",
                        null,
                        null,
                        "",
                        new byte[0]
                )
        );
    }
}
