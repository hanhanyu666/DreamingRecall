package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.capture.CaptureBridge;
import com.hhy.dreamingrecall.client.recording.ClientRecordingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void dreamingrecall$captureEntityEffect(ClientboundAnimatePacket packet, CallbackInfo callback) {
        String effect = CaptureBridge.entityEffectName(packet.getAction()).orElse(null);
        if (effect == null) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(packet.getId());
        if (entity != null) {
            ClientRecordingManager.INSTANCE.entityEffect(level, entity.getUUID(), effect);
        }
    }
}
