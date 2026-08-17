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

public record CameraSamplePayload(
        long clientNanos,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float roll,
        float fov,
        PlayerVisual playerVisual
) implements CustomPacketPayload {
    public static final Type<CameraSamplePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DreamingRecall.MOD_ID,
            "camera_sample"
    ));
    public static final StreamCodec<RegistryFriendlyByteBuf, CameraSamplePayload> STREAM_CODEC = StreamCodec.of(
            CameraSamplePayload::encode,
            CameraSamplePayload::decode
    );

    private static void encode(RegistryFriendlyByteBuf buffer, CameraSamplePayload packet) {
        buffer.writeLong(packet.clientNanos);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeFloat(packet.yaw);
        buffer.writeFloat(packet.pitch);
        buffer.writeFloat(packet.roll);
        buffer.writeFloat(packet.fov);
        encodePlayerVisual(buffer, packet.playerVisual);
    }

    private static CameraSamplePayload decode(RegistryFriendlyByteBuf buffer) {
        return new CameraSamplePayload(
                buffer.readLong(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                decodePlayerVisual(buffer)
        );
    }

    static void encodePlayerVisual(RegistryFriendlyByteBuf buffer, PlayerVisual visual) {
        buffer.writeDouble(visual.x);
        buffer.writeDouble(visual.y);
        buffer.writeDouble(visual.z);
        buffer.writeFloat(visual.yaw);
        buffer.writeFloat(visual.pitch);
        buffer.writeFloat(visual.headYaw);
        buffer.writeFloat(visual.bodyYaw);
        buffer.writeDouble(visual.velocityX);
        buffer.writeDouble(visual.velocityY);
        buffer.writeDouble(visual.velocityZ);
        buffer.writeUtf(visual.pose, 64);
        buffer.writeBoolean(visual.onGround);
        buffer.writeFloat(visual.walkPosition);
        buffer.writeFloat(visual.walkSpeed);
        buffer.writeFloat(visual.attackProgress);
        buffer.writeBoolean(visual.swinging);
        buffer.writeInt(visual.swingTime);
        buffer.writeUtf(visual.swingingArm, 32);
        buffer.writeBoolean(visual.usingItem);
        buffer.writeUtf(visual.usedItemHand, 32);
        buffer.writeInt(visual.useItemRemainingTicks);
        buffer.writeFloat(visual.swimAmount);
        buffer.writeInt(visual.fallFlyingTicks);
    }

    static PlayerVisual decodePlayerVisual(RegistryFriendlyByteBuf buffer) {
        return new PlayerVisual(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readUtf(64),
                buffer.readBoolean(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readInt(),
                buffer.readUtf(32),
                buffer.readBoolean(),
                buffer.readUtf(32),
                buffer.readInt(),
                buffer.readFloat(),
                buffer.readInt()
        );
    }

    public static void handle(CameraSamplePayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && packet.validFor(player)) {
                DreamingRecallServer.INSTANCE.clientCameraSample(player, packet);
            }
        });
    }

    private boolean validFor(ServerPlayer player) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(roll) || !Float.isFinite(fov)
                || fov <= 0.0F || fov >= 180.0F
                || !playerVisual.valid()) {
            return false;
        }
        double dx = x - player.getX();
        double dy = y - player.getY();
        double dz = z - player.getZ();
        double playerDx = playerVisual.x - player.getX();
        double playerDy = playerVisual.y - player.getY();
        double playerDz = playerVisual.z - player.getZ();
        return dx * dx + dy * dy + dz * dz <= 64.0 * 64.0
                && playerDx * playerDx + playerDy * playerDy + playerDz * playerDz <= 64.0 * 64.0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record PlayerVisual(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            float bodyYaw,
            double velocityX,
            double velocityY,
            double velocityZ,
            String pose,
            boolean onGround,
            float walkPosition,
            float walkSpeed,
            float attackProgress,
            boolean swinging,
            int swingTime,
            String swingingArm,
            boolean usingItem,
            String usedItemHand,
            int useItemRemainingTicks,
            float swimAmount,
            int fallFlyingTicks
    ) {
        public PlayerVisual {
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(swingingArm, "swingingArm");
            Objects.requireNonNull(usedItemHand, "usedItemHand");
        }

        boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch)
                    && Float.isFinite(headYaw) && Float.isFinite(bodyYaw)
                    && Double.isFinite(velocityX) && Double.isFinite(velocityY) && Double.isFinite(velocityZ)
                    && Float.isFinite(walkPosition) && Float.isFinite(walkSpeed)
                    && Float.isFinite(attackProgress) && attackProgress >= 0.0F && attackProgress <= 1.0F
                    && Float.isFinite(swimAmount) && swimAmount >= 0.0F && swimAmount <= 1.0F
                    && swingTime >= -1 && swingTime <= 1_000_000
                    && useItemRemainingTicks >= 0 && useItemRemainingTicks <= 1_000_000
                    && fallFlyingTicks >= 0 && fallFlyingTicks <= 100_000_000;
        }
    }
}
