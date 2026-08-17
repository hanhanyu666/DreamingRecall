package com.hhy.dreamingrecall.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("useItem")
    void dreamingrecall$setUseItem(ItemStack stack);

    @Accessor("useItemRemaining")
    void dreamingrecall$setUseItemRemaining(int ticks);

    @Accessor("fallFlyTicks")
    void dreamingrecall$setFallFlyTicks(int ticks);

    @Accessor("swimAmount")
    void dreamingrecall$setSwimAmount(float amount);

    @Accessor("swimAmountO")
    void dreamingrecall$setSwimAmountOld(float amount);

    @Invoker("setLivingEntityFlag")
    void dreamingrecall$setLivingEntityFlag(int flag, boolean value);
}
