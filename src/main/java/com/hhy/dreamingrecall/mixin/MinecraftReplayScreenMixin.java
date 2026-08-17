package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.client.playback.packet.ReplayPacketDispatchContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftReplayScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$keepDirectorScreen(Screen screen, CallbackInfo callback) {
        if (ReplayPacketDispatchContext.suppressScreenChanges()) {
            callback.cancel();
        }
    }

    @Inject(method = "updateScreenAndTick", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$skipTransitionScreen(Screen screen, CallbackInfo callback) {
        if (ReplayPacketDispatchContext.suppressScreenChanges()) {
            callback.cancel();
        }
    }
}
