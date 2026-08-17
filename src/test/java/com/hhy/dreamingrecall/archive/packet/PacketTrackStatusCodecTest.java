package com.hhy.dreamingrecall.archive.packet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketTrackStatusCodecTest {
    @Test
    void roundTripsAnIncompletePlayerTrack() throws Exception {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        PacketTrackStatusCodec.Status decoded = PacketTrackStatusCodec.decode(
                PacketTrackStatusCodec.encodeIncomplete(playerId)
        );

        assertEquals(playerId, decoded.playerId());
        assertFalse(decoded.complete());
    }

    @Test
    void rejectsForeignAndTrailingPayloads() {
        assertThrows(IOException.class, () -> PacketTrackStatusCodec.decode(new byte[]{1, 2, 3, 4}));
        byte[] valid = PacketTrackStatusCodec.encodeIncomplete(UUID.randomUUID());
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> PacketTrackStatusCodec.decode(trailing));
    }
}
