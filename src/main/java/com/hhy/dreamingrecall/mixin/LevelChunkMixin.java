package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.capture.CaptureBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void dreamingrecall$captureBlockChange(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
            CallbackInfoReturnable<BlockState> callback
    ) {
        if (callback.getReturnValue() != null) {
            CaptureBridge.blockChanged((LevelChunk) (Object) this, pos.immutable(), state);
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void dreamingrecall$captureBlockEntityRemoval(BlockPos pos, CallbackInfo callback) {
        CaptureBridge.blockEntityRemoved((LevelChunk) (Object) this, pos.immutable());
    }
}
