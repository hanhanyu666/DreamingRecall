package com.hhy.dreamingrecall.network;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

public record PlayerVisualSamplePayload(
        long clientNanos,
        CameraSamplePayload.PlayerVisual playerVisual
) implements CustomPacketPayload {
    public static final Type<PlayerVisualSamplePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DreamingRecall.MOD_ID,
            "player_visual_sample"
    ));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerVisualSamplePayload> STREAM_CODEC = StreamCodec.of(
            PlayerVisualSamplePayload::encode,
            PlayerVisualSamplePayload::decode
    );

    public PlayerVisualSamplePayload {
        Objects.requireNonNull(playerVisual, "playerVisual");
    }

    private static void encode(RegistryFriendlyByteBuf buffer, PlayerVisualSamplePayload packet) {
        buffer.writeLong(packet.clientNanos);
        CameraSamplePayload.encodePlayerVisual(buffer, packet.playerVisual);
    }

    private static PlayerVisualSamplePayload decode(RegistryFriendlyByteBuf buffer) {
        return new PlayerVisualSamplePayload(
                buffer.readLong(),
                CameraSamplePayload.decodePlayerVisual(buffer)
        );
    }

    public static void handle(PlayerVisualSamplePayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && packet.validFor(player)) {
                DreamingRecallServer.INSTANCE.clientPlayerVisualSample(player, packet);
            }
        });
    }

    private boolean validFor(ServerPlayer player) {
        if (!playerVisual.valid()) {
            return false;
        }
        double dx = playerVisual.x() - player.getX();
        double dy = playerVisual.y() - player.getY();
        double dz = playerVisual.z() - player.getZ();
        return dx * dx + dy * dy + dz * dz <= 64.0 * 64.0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
