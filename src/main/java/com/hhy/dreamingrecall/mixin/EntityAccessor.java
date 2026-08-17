package com.hhy.dreamingrecall.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Invoker("setSharedFlag")
    void dreamingrecall$setSharedFlag(int flag, boolean value);
}
