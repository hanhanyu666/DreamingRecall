package com.hhy.dreamingrecall.client.playback.packet;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import javax.annotation.Nullable;

final class ReplayConnection extends Connection implements AutoCloseable {
    private final EmbeddedChannel embeddedChannel;
    private volatile ProtocolInfo<?> inboundProtocol;
    private volatile PacketListener packetListener;
    private volatile DisconnectionDetails disconnectionDetails;
    private volatile boolean closed;

    ReplayConnection() {
        super(PacketFlow.CLIENTBOUND);
        embeddedChannel = new EmbeddedChannel(this);
    }

    void install(ProtocolInfo<?> protocol, PacketListener listener) {
        inboundProtocol = protocol;
        packetListener = listener;
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        install(protocol, listener);
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        // Replay is receive-only.
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (listener != null) {
            listener.onSuccess();
        }
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        if (listener != null) {
            listener.onSuccess();
        }
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return !closed && embeddedChannel.isOpen();
    }

    @Override
    public void disconnect(Component message) {
        disconnect(new DisconnectionDetails(message));
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        disconnectionDetails = details;
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public PacketListener getPacketListener() {
        return packetListener;
    }

    @Override
    public DisconnectionDetails getDisconnectionDetails() {
        return disconnectionDetails;
    }

    @Override
    public ProtocolInfo<?> getInboundProtocol() {
        if (inboundProtocol == null) {
            throw new IllegalStateException("Replay inbound protocol has not been installed");
        }
        return inboundProtocol;
    }

    @Override
    public void close() {
        closed = true;
        packetListener = null;
        inboundProtocol = null;
        embeddedChannel.finishAndReleaseAll();
    }
}
