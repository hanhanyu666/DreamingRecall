package com.hhy.dreamingrecall.client.playback.packet;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class ReplayProtocolBootstrap {
    private ReplayProtocolBootstrap() {
    }

    static Result build(Minecraft minecraft, List<com.hhy.dreamingrecall.archive.packet.PacketEnvelope> frames)
            throws IOException {
        GameProfile profile = new GameProfile(
                minecraft.getUser().getProfileId(),
                minecraft.getUser().getName()
        );
        RegistryDataCollector registries = new RegistryDataCollector();
        FeatureFlagSet enabledFeatures = FeatureFlags.DEFAULT_FLAGS;
        ConnectionType connectionType = ConnectionType.OTHER;

        for (var frame : frames) {
            Packet<?> packet;
            if (frame.phase() == com.hhy.dreamingrecall.archive.packet.ProtocolPhase.LOGIN) {
                packet = decode(LoginProtocols.CLIENTBOUND, frame.packetBytes());
                if (packet instanceof ClientboundGameProfilePacket gameProfile) {
                    profile = gameProfile.gameProfile();
                }
                continue;
            }
            if (frame.phase() != com.hhy.dreamingrecall.archive.packet.ProtocolPhase.CONFIGURATION) {
                continue;
            }
            packet = decode(ConfigurationProtocols.CLIENTBOUND, frame.packetBytes());
            if (packet instanceof ClientboundRegistryDataPacket registryData) {
                registries.appendContents(registryData.registry(), registryData.entries());
            } else if (packet instanceof ClientboundUpdateTagsPacket tags) {
                registries.appendTags(tags.getTags());
            } else if (packet instanceof ClientboundUpdateEnabledFeaturesPacket features) {
                enabledFeatures = FeatureFlags.REGISTRY.fromNames(features.features());
            }
        }

        RegistryAccess.Frozen received = ClientRegistryLayer.createRegistryAccess().compositeAccess();
        RegistryAccess.Frozen gameRegistries;
        try (MultiPackResourceManager dataResources = new MultiPackResourceManager(
                PackType.SERVER_DATA,
                minecraft.getResourcePackRepository().openAllSelected()
        )) {
            gameRegistries = registries.collectGameRegistries(dataResources, received, false);
        }
        ReplayConnection connection = new ReplayConnection();
        WorldSessionTelemetryManager telemetry = new WorldSessionTelemetryManager(
                TelemetryEventSender.DISABLED,
                false,
                null,
                null
        );
        CommonListenerCookie cookie = new CommonListenerCookie(
                profile,
                telemetry,
                gameRegistries,
                enabledFeatures,
                "DreamingRecall",
                null,
                null,
                Map.of(),
                null,
                false,
                Map.of(),
                ServerLinks.EMPTY,
                connectionType
        );
        ClientPacketListener listener = new ClientPacketListener(minecraft, connection, cookie);
        ProtocolInfo<ClientGamePacketListener> gameProtocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(gameRegistries, connectionType)
        );
        connection.install(gameProtocol, listener);
        return new Result(connection, listener, gameProtocol, gameRegistries, connectionType);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, byte[] bytes) throws IOException {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            Packet<?> packet = (Packet<?>) ((ProtocolInfo) protocol).codec().decode(buffer);
            if (buffer.isReadable()) {
                throw new IOException("Replay packet contains " + buffer.readableBytes() + " trailing bytes");
            }
            return packet;
        } catch (RuntimeException failure) {
            throw new IOException("Could not decode " + protocol.id().id() + " replay packet", failure);
        } finally {
            buffer.release();
        }
    }

    record Result(
            ReplayConnection connection,
            ClientPacketListener listener,
            ProtocolInfo<ClientGamePacketListener> gameProtocol,
            RegistryAccess.Frozen registries,
            ConnectionType connectionType
    ) {
    }
}
