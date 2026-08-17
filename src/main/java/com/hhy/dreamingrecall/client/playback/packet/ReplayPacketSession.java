package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.mixin.MinecraftAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class ReplayPacketSession implements AutoCloseable {
    private static final int MAX_NAMESPACE_FAILURE_LOGS = 3;

    private final Minecraft minecraft;
    private final ReplayProtocolBootstrap.Result protocol;
    private final Map<String, Integer> namespaceFailures = new HashMap<>();
    private PacketReplayViewController view;
    private boolean closed;

    ReplayPacketSession(Minecraft minecraft, PacketReplayIndex index) throws IOException {
        this.minecraft = minecraft;
        if (minecraft.level != null || minecraft.player != null) {
            throw new IllegalStateException("Disconnect from the current world before opening a replay");
        }
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        try {
            protocol = ReplayProtocolBootstrap.build(minecraft, index.bootstrapFrames());
        } catch (IOException | RuntimeException | Error failure) {
            ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
            throw failure;
        }
    }

    void apply(PacketEnvelope envelope) throws IOException {
        if (closed || envelope.phase() != ProtocolPhase.PLAY) {
            return;
        }
        // A replay has no live configuration handshake. Mod payloads are handled by opt-in replay
        // extensions instead of entering NeoForge's live network negotiation path.
        if (!envelope.namespace().equals("minecraft")) {
            return;
        }
        Packet<?> packet;
        try {
            packet = decode(protocol.gameProtocol(), envelope.packetBytes());
        } catch (IOException failure) {
            if (!envelope.namespace().equals("minecraft")) {
                isolate(envelope, failure);
                return;
            }
            throw failure;
        }
        if (shouldSkip(packet)) {
            return;
        }
        try {
            ReplayPacketDispatchContext.run(() -> handle(packet, protocol.listener()));
        } catch (Throwable failure) {
            if (!envelope.namespace().equals("minecraft")) {
                isolate(envelope, failure);
                return;
            }
            throw new IOException("Could not apply replay packet " + envelope.packetTypeId(), failure);
        }
    }

    PacketReplayViewController view() {
        return view;
    }

    void finishBatch() {
        refreshView();
    }

    private void refreshView() {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (view == null) {
            view = new PacketReplayViewController(minecraft);
        } else {
            view.refreshAfterPackets();
        }
    }

    private void isolate(PacketEnvelope envelope, Throwable failure) {
        int count = namespaceFailures.merge(envelope.namespace(), 1, Integer::sum);
        if (count <= MAX_NAMESPACE_FAILURE_LOGS) {
            DreamingRecall.LOGGER.warn(
                    "Isolated replay packet namespace {} after packet {} failed",
                    envelope.namespace(),
                    envelope.packetTypeId(),
                    failure
            );
        }
    }

    private static boolean shouldSkip(Packet<?> packet) {
        return packet instanceof BundleDelimiterPacket<?>
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundResourcePackPopPacket
                || packet instanceof ClientboundResourcePackPushPacket
                || packet instanceof ClientboundStoreCookiePacket
                || packet instanceof ClientboundTransferPacket
                || packet instanceof ClientboundCookieRequestPacket
                || packet instanceof ClientboundStartConfigurationPacket;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<ClientGamePacketListener> protocol, byte[] bytes) throws IOException {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            Packet<?> packet = (Packet<?>) ((ProtocolInfo) protocol).codec().decode(buffer);
            if (buffer.isReadable()) {
                throw new IOException("Replay packet contains " + buffer.readableBytes() + " trailing bytes");
            }
            return packet;
        } catch (RuntimeException failure) {
            throw new IOException("Could not decode play replay packet", failure);
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handle(Packet packet, ClientGamePacketListener listener) {
        packet.handle(listener);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (view != null) {
            view.close();
            view = null;
        }
        ClientLevel replayLevel = minecraft.level;
        protocol.listener().close();
        if (replayLevel != null) {
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(replayLevel));
        }
        minecraft.setCameraEntity(null);
        minecraft.level = null;
        minecraft.player = null;
        minecraft.cameraEntity = null;
        minecraft.gameMode = null;
        ((MinecraftAccessor) minecraft).dreamingrecall$updateLevelInEngines(null);
        minecraft.gameRenderer.resetData();
        protocol.connection().close();
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }
}
