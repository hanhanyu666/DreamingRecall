package com.hhy.dreamingrecall.network;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StartRecordingRequestPayload() implements CustomPacketPayload {
    public static final StartRecordingRequestPayload INSTANCE = new StartRecordingRequestPayload();
    public static final Type<StartRecordingRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DreamingRecall.MOD_ID,
            "start_recording_request"
    ));
    public static final StreamCodec<RegistryFriendlyByteBuf, StartRecordingRequestPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
            }, buffer -> INSTANCE);

    public static void handle(StartRecordingRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            boolean singleplayerOwner = player.getServer() != null
                    && player.getServer().isSingleplayerOwner(player.getGameProfile());
            if (!singleplayerOwner && !player.hasPermissions(2)) {
                player.sendSystemMessage(Component.translatable(
                        "message.dreamingrecall.record_on_join_denied"
                ));
                return;
            }
            Component result = switch (DreamingRecallServer.INSTANCE.startManual(player.getServer())) {
                case STARTED -> Component.translatable("message.dreamingrecall.record_on_join_started");
                case ALREADY_RECORDING -> Component.translatable("message.dreamingrecall.record_on_join_already");
                case FAILED -> Component.translatable("message.dreamingrecall.record_on_join_failed");
            };
            player.sendSystemMessage(result);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
