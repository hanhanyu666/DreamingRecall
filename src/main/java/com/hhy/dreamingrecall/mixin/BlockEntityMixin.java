package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.capture.CaptureBridge;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class BlockEntityMixin {
    @Inject(method = "setChanged", at = @At("TAIL"))
    private void dreamingrecall$markBlockEntityDirty(CallbackInfo callback) {
        CaptureBridge.blockEntityChanged((BlockEntity) (Object) this);
    }
}
