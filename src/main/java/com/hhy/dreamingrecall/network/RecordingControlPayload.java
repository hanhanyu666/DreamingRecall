package com.hhy.dreamingrecall.network;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.client.recording.ServerPacketTrackUploader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.UUID;

public record RecordingControlPayload(
        UUID recordingId,
        boolean active,
        boolean cameraTrackAllowed
) implements CustomPacketPayload {
    public static final Type<RecordingControlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DreamingRecall.MOD_ID,
            "recording_control"
    ));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecordingControlPayload> STREAM_CODEC = StreamCodec.of(
            RecordingControlPayload::encode,
            RecordingControlPayload::decode
    );

    public RecordingControlPayload {
        Objects.requireNonNull(recordingId, "recordingId");
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RecordingControlPayload payload) {
        buffer.writeUUID(payload.recordingId);
        buffer.writeBoolean(payload.active);
        buffer.writeBoolean(payload.cameraTrackAllowed);
    }

    private static RecordingControlPayload decode(RegistryFriendlyByteBuf buffer) {
        return new RecordingControlPayload(buffer.readUUID(), buffer.readBoolean(), buffer.readBoolean());
    }

    public static void handle(RecordingControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ServerPacketTrackUploader.INSTANCE.recordingControl(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
