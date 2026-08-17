package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents live AI/physics from mutating snapshot-driven replay entities. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$skipReplayEntityTick(CallbackInfo callback) {
        if (ReplayWorldController.isReplayLevel((ClientLevel) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$keepReplayTimeAuthoritative(CallbackInfo callback) {
        if (ReplayWorldController.isReplayLevel((ClientLevel) (Object) this)) {
            callback.cancel();
        }
    }
}
