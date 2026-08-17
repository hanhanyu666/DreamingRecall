package com.hhy.dreamingrecall.archive.packet;

import com.hhy.dreamingrecall.archive.ArchiveFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PacketEnvelopeCodec {
    private static final int MAGIC = 0x4452504B; // DRPK
    private static final int FLAG_SUBJECT = 1;
    private static final int FLAG_CHUNK = 2;

    private PacketEnvelopeCodec() {
    }

    public static byte[] encode(PacketEnvelope envelope) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeShort(envelope.schemaVersion());
            output.writeByte(envelope.phase().id());
            output.writeByte(envelope.scope().id());
            int flags = envelope.subjectId().isPresent() ? FLAG_SUBJECT : 0;
            flags |= envelope.chunk().isPresent() ? FLAG_CHUNK : 0;
            output.writeByte(flags);
            writeString(output, envelope.trackId(), ArchiveFormat.MAX_TRACK_ID_BYTES, "track id");
            writeString(output, envelope.packetTypeId(), ArchiveFormat.MAX_PACKET_TYPE_ID_BYTES, "packet type id");
            writeString(output, envelope.namespace(), ArchiveFormat.MAX_NAMESPACE_BYTES, "namespace");
            writeString(output, envelope.dimensionId(), ArchiveFormat.MAX_DIMENSION_ID_BYTES, "dimension id");
            writeString(
                    output,
                    envelope.registryFingerprint(),
                    ArchiveFormat.MAX_REGISTRY_FINGERPRINT_BYTES,
                    "registry fingerprint"
            );
            if (envelope.subjectId().isPresent()) {
                UUID subject = envelope.subjectId().orElseThrow();
                output.writeLong(subject.getMostSignificantBits());
                output.writeLong(subject.getLeastSignificantBits());
            }
            if (envelope.chunk().isPresent()) {
                PacketEnvelope.ChunkPosition chunk = envelope.chunk().orElseThrow();
                output.writeInt(chunk.x());
                output.writeInt(chunk.z());
            }
            byte[] packet = envelope.packetBytes();
            if (packet.length > ArchiveFormat.MAX_RECORD_BYTES) {
                throw new IOException("Packet payload exceeds format limit");
            }
            output.writeInt(packet.length);
            output.write(packet);
        }
        return bytes.toByteArray();
    }

    public static PacketEnvelope decode(byte[] encoded) throws IOException {
        if (encoded.length > ArchiveFormat.MAX_RECORD_BYTES) {
            throw new IOException("Packet envelope exceeds format limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a DreamingRecall packet envelope");
            }
            int schema = input.readUnsignedShort();
            ProtocolPhase phase;
            PacketScope scope;
            try {
                phase = ProtocolPhase.fromId(input.readUnsignedByte());
                scope = PacketScope.fromId(input.readUnsignedByte());
            } catch (IllegalArgumentException failure) {
                throw new IOException(failure.getMessage(), failure);
            }
            int flags = input.readUnsignedByte();
            if ((flags & ~(FLAG_SUBJECT | FLAG_CHUNK)) != 0) {
                throw new IOException("Unknown packet envelope flags " + flags);
            }
            String track = readString(input, ArchiveFormat.MAX_TRACK_ID_BYTES, "track id");
            String packetType = readString(input, ArchiveFormat.MAX_PACKET_TYPE_ID_BYTES, "packet type id");
            String namespace = readString(input, ArchiveFormat.MAX_NAMESPACE_BYTES, "namespace");
            String dimension = readString(input, ArchiveFormat.MAX_DIMENSION_ID_BYTES, "dimension id");
            String registry = readString(
                    input,
                    ArchiveFormat.MAX_REGISTRY_FINGERPRINT_BYTES,
                    "registry fingerprint"
            );
            UUID subject = (flags & FLAG_SUBJECT) == 0 ? null : new UUID(input.readLong(), input.readLong());
            PacketEnvelope.ChunkPosition chunk = (flags & FLAG_CHUNK) == 0
                    ? null
                    : new PacketEnvelope.ChunkPosition(input.readInt(), input.readInt());
            int packetLength = readLength(input, ArchiveFormat.MAX_RECORD_BYTES, "packet payload");
            byte[] packet = input.readNBytes(packetLength);
            if (packet.length != packetLength) {
                throw new EOFException("Truncated packet payload");
            }
            if (input.read() != -1) {
                throw new IOException("Packet envelope contains trailing bytes");
            }
            try {
                return new PacketEnvelope(
                        schema,
                        track,
                        phase,
                        packetType,
                        namespace,
                        scope,
                        dimension,
                        subject,
                        chunk,
                        registry,
                        packet
                );
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid packet envelope", failure);
            }
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximum, String label) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) {
            throw new IOException(label + " exceeds format limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximum, String label) throws IOException {
        int length = readLength(input, maximum, label);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Truncated " + label);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("Invalid UTF-8 in " + label, failure);
        }
    }

    private static int readLength(DataInputStream input, int maximum, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Invalid " + label + " length " + length);
        }
        return length;
    }
}
