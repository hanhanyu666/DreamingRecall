package com.hhy.dreamingrecall.capture;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Optional;

public final class CaptureBridge {
    private static volatile ClientSink clientSink = ClientSink.NOOP;

    private CaptureBridge() {
    }

    public static void blockChanged(LevelChunk chunk, BlockPos pos, BlockState newState) {
        if (chunk.getLevel().isClientSide) {
            clientSink.blockChanged(chunk, pos, newState);
        } else {
            DreamingRecallServer.INSTANCE.blockChanged(chunk, pos, newState);
        }
    }

    public static void blockEntityChanged(BlockEntity blockEntity) {
        if (blockEntity.getLevel() != null && blockEntity.getLevel().isClientSide) {
            clientSink.blockEntityChanged(blockEntity);
        } else {
            DreamingRecallServer.INSTANCE.blockEntityChanged(blockEntity);
        }
    }

    public static void blockEntityRemoved(LevelChunk chunk, BlockPos pos) {
        if (chunk.getLevel().isClientSide) {
            clientSink.blockEntityRemoved(chunk, pos);
        } else {
            DreamingRecallServer.INSTANCE.blockEntityRemoved(chunk, pos);
        }
    }

    public static void setClientSink(ClientSink sink) {
        clientSink = sink == null ? ClientSink.NOOP : sink;
    }

    public interface ClientSink {
        ClientSink NOOP = new ClientSink() {
            @Override
            public void blockChanged(LevelChunk chunk, BlockPos position, BlockState state) {
            }

            @Override
            public void blockEntityChanged(BlockEntity blockEntity) {
            }

            @Override
            public void blockEntityRemoved(LevelChunk chunk, BlockPos position) {
            }
        };

        void blockChanged(LevelChunk chunk, BlockPos position, BlockState state);

        void blockEntityChanged(BlockEntity blockEntity);

        void blockEntityRemoved(LevelChunk chunk, BlockPos position);
    }

    public static void outgoingPacket(ServerPlayer recipient, Packet<?> packet) {
        try {
            if (packet instanceof ClientboundAnimatePacket animation) {
                Optional<String> effect = entityEffectName(animation.getAction());
                if (effect.isEmpty()) {
                    return;
                }
                Entity target = recipient.serverLevel().getEntity(animation.getId());
                if (target != null) {
                    DreamingRecallServer.INSTANCE.entityEffect(
                            recipient,
                            target.getUUID(),
                            effect.get(),
                            System.identityHashCode(packet)
                    );
                }
                return;
            }

            Component rendered;
            String kind;
            if (packet instanceof ClientboundSystemChatPacket system) {
                if (system.overlay()) {
                    return;
                }
                rendered = system.content();
                kind = "system";
            } else if (packet instanceof ClientboundDisguisedChatPacket disguised) {
                rendered = disguised.chatType().decorate(disguised.message());
                kind = "disguised";
            } else if (packet instanceof ClientboundPlayerChatPacket playerChat) {
                Component content;
                if (playerChat.filterMask().isEmpty()) {
                    content = playerChat.unsignedContent() != null
                            ? playerChat.unsignedContent()
                            : Component.literal(playerChat.body().content());
                } else {
                    content = playerChat.filterMask().applyWithFormatting(playerChat.body().content());
                    if (content == null) {
                        return;
                    }
                }
                rendered = playerChat.chatType().decorate(content);
                kind = "player";
            } else {
                return;
            }
            DreamingRecallServer.INSTANCE.chatDelivered(
                    recipient,
                    rendered,
                    kind,
                    System.identityHashCode(packet)
            );
        } catch (Throwable failure) {
            DreamingRecall.LOGGER.warn("Failed to capture an outgoing replay packet", failure);
        }
    }

    public static Optional<String> entityEffectName(int animationAction) {
        return switch (animationAction) {
            case ClientboundAnimatePacket.CRITICAL_HIT -> Optional.of("critical_hit");
            case ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> Optional.of("magic_critical_hit");
            default -> Optional.empty();
        };
    }
}
