package com.hhy.dreamingrecall.archive.packet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public final class PacketTrackStatusCodec {
    private static final int MAGIC = 0x44525453; // DRTS
    private static final int SCHEMA = 1;
    private static final int INCOMPLETE = 1;

    private PacketTrackStatusCodec() {
    }

    public static byte[] encodeIncomplete(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(SCHEMA);
                output.writeByte(INCOMPLETE);
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory packet track status encoding failed", impossible);
        }
    }

    public static Status decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a DreamingRecall packet track status");
            }
            int schema = input.readUnsignedByte();
            int state = input.readUnsignedByte();
            if (schema != SCHEMA || state != INCOMPLETE) {
                throw new IOException("Unsupported packet track status");
            }
            Status result = new Status(new UUID(input.readLong(), input.readLong()), false);
            if (input.read() != -1) {
                throw new IOException("Packet track status contains trailing bytes");
            }
            return result;
        }
    }

    public record Status(UUID playerId, boolean complete) {
        public Status {
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
