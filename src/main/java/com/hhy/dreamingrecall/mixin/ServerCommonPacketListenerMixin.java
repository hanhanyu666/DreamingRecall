package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.capture.CaptureBridge;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerMixin {
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD")
    )
    private void dreamingrecall$captureDeliveredChat(
            Packet<?> packet,
            PacketSendListener listener,
            CallbackInfo callback
    ) {
        if ((Object) this instanceof ServerGamePacketListenerImpl gameListener) {
            CaptureBridge.outgoingPacket(gameListener.player, packet);
        }
    }
}
