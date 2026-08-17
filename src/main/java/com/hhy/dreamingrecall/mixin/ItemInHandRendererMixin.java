package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayViewController;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$showReplayHands(
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffer,
            LocalPlayer player,
            int combinedLight,
            CallbackInfo callback
    ) {
        if (PacketReplayViewController.shouldSuppressFirstPersonHand()) {
            callback.cancel();
            return;
        }
        ReplayWorldController.setReplayHandRenderPhase(player, true);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void dreamingrecall$hideReplayHands(
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffer,
            LocalPlayer player,
            int combinedLight,
            CallbackInfo callback
    ) {
        ReplayWorldController.setReplayHandRenderPhase(player, false);
    }
}
