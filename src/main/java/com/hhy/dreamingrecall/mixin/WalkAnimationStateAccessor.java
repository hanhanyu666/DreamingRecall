package com.hhy.dreamingrecall.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("speedOld")
    void dreamingrecall$setSpeedOld(float speed);

    @Accessor("speed")
    void dreamingrecall$setSpeed(float speed);

    @Accessor("position")
    void dreamingrecall$setPosition(float position);
}
