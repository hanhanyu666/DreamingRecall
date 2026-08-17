package com.hhy.dreamingrecall.network;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.archive.track.TrackNames;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClientPacketBatchPayload(
        UUID recordingId,
        long sequence,
        boolean discontinuity,
        List<Frame> frames
) implements CustomPacketPayload {
    public static final int MAX_FRAMES = 128;
    public static final int MAX_FRAME_BYTES = 800 * 1024;
    public static final int MAX_BATCH_BYTES = 900 * 1024;
    private static final int MAX_PACKET_ID_CHARS = 256;
    private static final int MAX_NAMESPACE_CHARS = 128;
    private static final int MAX_DIMENSION_CHARS = 256;

    public static final Type<ClientPacketBatchPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DreamingRecall.MOD_ID,
            "client_packet_batch"
    ));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientPacketBatchPayload> STREAM_CODEC = StreamCodec.of(
            ClientPacketBatchPayload::encode,
            ClientPacketBatchPayload::decode
    );

    public ClientPacketBatchPayload {
        Objects.requireNonNull(recordingId, "recordingId");
        if (sequence < 0) {
            throw new IllegalArgumentException("Packet batch sequence must be non-negative");
        }
        frames = List.copyOf(frames);
        validateBatch(frames);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, ClientPacketBatchPayload payload) {
        buffer.writeUUID(payload.recordingId);
        buffer.writeVarLong(payload.sequence);
        buffer.writeBoolean(payload.discontinuity);
        buffer.writeVarInt(payload.frames.size());
        for (Frame frame : payload.frames) {
            buffer.writeLong(frame.clientNanos);
            buffer.writeByte(frame.phase.ordinal());
            buffer.writeUtf(frame.packetTypeId, MAX_PACKET_ID_CHARS);
            buffer.writeUtf(frame.namespace, MAX_NAMESPACE_CHARS);
            buffer.writeUtf(frame.dimensionId, MAX_DIMENSION_CHARS);
            byte[] bytes = frame.packetBytes;
            buffer.writeVarInt(bytes.length);
            buffer.writeBytes(bytes);
        }
    }

    private static ClientPacketBatchPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID recordingId = buffer.readUUID();
        long sequence = buffer.readVarLong();
        boolean discontinuity = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_FRAMES) {
            throw new DecoderException("Invalid DreamingRecall packet batch count " + count);
        }
        ArrayList<Frame> frames = new ArrayList<>(count);
        int retainedBytes = 0;
        for (int index = 0; index < count; index++) {
            long clientNanos = buffer.readLong();
            int phaseId = buffer.readUnsignedByte();
            ProtocolPhase[] phases = ProtocolPhase.values();
            if (phaseId >= phases.length) {
                throw new DecoderException("Invalid DreamingRecall protocol phase " + phaseId);
            }
            String packetTypeId = buffer.readUtf(MAX_PACKET_ID_CHARS);
            String namespace = buffer.readUtf(MAX_NAMESPACE_CHARS);
            String dimensionId = buffer.readUtf(MAX_DIMENSION_CHARS);
            int length = buffer.readVarInt();
            if (length < 0 || length > MAX_FRAME_BYTES || retainedBytes + length > MAX_BATCH_BYTES) {
                throw new DecoderException("Invalid DreamingRecall packet frame length " + length);
            }
            if (buffer.readableBytes() < length) {
                throw new DecoderException("Truncated DreamingRecall packet frame");
            }
            byte[] packetBytes = new byte[length];
            buffer.readBytes(packetBytes);
            retainedBytes += length;
            frames.add(new Frame(
                    clientNanos,
                    phases[phaseId],
                    packetTypeId,
                    namespace,
                    dimensionId,
                    packetBytes
            ));
        }
        return new ClientPacketBatchPayload(recordingId, sequence, discontinuity, frames);
    }

    public static void handle(ClientPacketBatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                DreamingRecallServer.INSTANCE.clientPacketBatch(player, payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void validateBatch(List<Frame> frames) {
        if (frames.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("Too many frames in packet batch");
        }
        int bytes = 0;
        for (Frame frame : frames) {
            bytes = Math.addExact(bytes, frame.packetBytes.length);
            if (bytes > MAX_BATCH_BYTES) {
                throw new IllegalArgumentException("Packet batch exceeds byte limit");
            }
        }
    }

    public int retainedBytes() {
        int bytes = 64;
        for (Frame frame : frames) {
            bytes = Math.addExact(bytes, frame.estimatedWireBytes());
        }
        return bytes;
    }

    public record Frame(
            long clientNanos,
            ProtocolPhase phase,
            String packetTypeId,
            String namespace,
            String dimensionId,
            byte[] packetBytes
    ) {
        public Frame {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(packetTypeId, "packetTypeId");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(packetBytes, "packetBytes");
            if (ResourceLocation.tryParse(packetTypeId) == null) {
                throw new IllegalArgumentException("Invalid packet type id");
            }
            TrackNames.requireNamespace(namespace);
            if (!dimensionId.isEmpty() && ResourceLocation.tryParse(dimensionId) == null) {
                throw new IllegalArgumentException("Invalid packet dimension id");
            }
            if (packetTypeId.length() > MAX_PACKET_ID_CHARS
                    || namespace.length() > MAX_NAMESPACE_CHARS
                    || dimensionId.length() > MAX_DIMENSION_CHARS
                    || packetTypeId.getBytes(StandardCharsets.UTF_8).length > MAX_PACKET_ID_CHARS * 3
                    || namespace.getBytes(StandardCharsets.UTF_8).length > MAX_NAMESPACE_CHARS * 3
                    || dimensionId.getBytes(StandardCharsets.UTF_8).length > MAX_DIMENSION_CHARS * 3
                    || packetBytes.length > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("Packet frame exceeds upload limits");
            }
            packetBytes = packetBytes.clone();
        }

        @Override
        public byte[] packetBytes() {
            return packetBytes.clone();
        }

        public int estimatedWireBytes() {
            return 48
                    + packetTypeId.getBytes(StandardCharsets.UTF_8).length
                    + namespace.getBytes(StandardCharsets.UTF_8).length
                    + dimensionId.getBytes(StandardCharsets.UTF_8).length
                    + packetBytes.length;
        }
    }
}
