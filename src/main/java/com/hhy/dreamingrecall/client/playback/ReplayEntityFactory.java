package com.hhy.dreamingrecall.client.playback;

import com.hhy.dreamingrecall.mixin.EntityAccessor;
import com.hhy.dreamingrecall.mixin.LivingEntityAccessor;
import com.hhy.dreamingrecall.mixin.WalkAnimationStateAccessor;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;

public final class ReplayEntityFactory {
    private ReplayEntityFactory() {
    }

    static Entity createEntity(ClientLevel level, DecodedPayload.EntityState state) {
        ResourceLocation id = ResourceLocation.tryParse(state.typeId());
        Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        Entity entity = null;
        if (type.isPresent()) {
            try {
                entity = type.get().create(level);
            } catch (RuntimeException | LinkageError ignored) {
                entity = null;
            }
        }
        if (entity == null) {
            entity = placeholder(level, state.typeId(), state.unavailableReason().orElse("missing local entity type"));
        }
        entity.setUUID(state.uuid());
        updateEntity(entity, state);
        return entity;
    }

    static RemotePlayer createPlayer(ClientLevel level, DecodedPayload.PlayerState state) {
        RemotePlayer player = new RemotePlayer(level, new GameProfile(state.uuid(), state.name()));
        updatePlayer(player, state);
        return player;
    }

    static void updateEntity(Entity entity, DecodedPayload.EntityState state) {
        applyTransform(entity, state.transform());
        state.details().ifPresent(details -> {
            entity.setYHeadRot(details.headYaw());
            entity.setInvisible(details.invisible());
            entity.setGlowingTag(details.glowing());
            entity.setNoGravity(details.noGravity());
            entity.setRemainingFireTicks(details.remainingFireTicks());
            ((EntityAccessor) entity).dreamingrecall$setSharedFlag(0, details.onFire());
            details.customNameJson().ifPresent(json -> {
                Component name = parseComponent(entity, json);
                if (name != null) {
                    entity.setCustomName(name);
                }
            });
            if (entity instanceof LivingEntity living) {
                details.living().ifPresent(value -> {
                    living.setHealth(Math.max(0.0F, value.health()));
                    living.setYHeadRot(value.headYaw());
                    living.yBodyRot = value.bodyYaw();
                    applyEquipment(living, value.equipment());
                    value.animation().ifPresent(animation -> applyLivingAnimationForRender(
                            living,
                            animation,
                            Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
                    ));
                });
            }
        });
        syncLivingInterpolationState(entity);
    }

    static void updatePlayer(RemotePlayer player, DecodedPayload.PlayerState state) {
        updatePlayer(player, state, null);
    }

    static void updatePlayer(
            RemotePlayer player,
            DecodedPayload.PlayerState state,
            DecodedPayload.PlayerState previousState
    ) {
        applyTransform(player, state.transform());
        player.setYHeadRot(state.headYaw());
        player.yBodyRot = state.bodyYaw();
        syncLivingInterpolationState(player);
        if (previousState == null || Float.compare(previousState.health(), state.health()) != 0) {
            player.setHealth(Math.max(0.0F, state.health()));
        }
        if (previousState == null || Float.compare(previousState.absorption(), state.absorption()) != 0) {
            player.setAbsorptionAmount(Math.max(0.0F, state.absorption()));
        }
        if (previousState == null || !previousState.equipment().equals(state.equipment())) {
            applyEquipment(player, state.equipment());
        }
        state.animation().ifPresent(animation -> applyPlayerAnimation(
                player,
                state.transform().pose(),
                animation,
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
        ));
    }

    public static void updateClientPlayer(
            AbstractClientPlayer player,
            DecodedPayload.ClientPlayerSample sample
    ) {
        applyTransform(player, sample.transform());
        player.setYHeadRot(sample.headYaw());
        player.yBodyRot = sample.bodyYaw();
        syncLivingInterpolationState(player);
        applyPlayerAnimation(
                player,
                sample.transform().pose(),
                sample.animation(),
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
        );
    }

    static void preparePlayerAnimationForRender(
            RemotePlayer player,
            DecodedPayload.PlayerState state,
            float partialTick
    ) {
        state.animation().ifPresent(animation -> applyWalkAnimation(player, animation, partialTick));
    }

    static void applyLivingAnimationForRender(
            LivingEntity living,
            DecodedPayload.LivingAnimation animation,
            float partialTick
    ) {
        living.hurtTime = Math.max(0, animation.hurtTime());
        living.hurtDuration = Math.max(living.hurtTime, 10);
        living.deathTime = Math.max(0, animation.deathTime());
        float attackProgress = Math.max(0.0F, Math.min(1.0F, animation.attackProgress()));
        living.oAttackAnim = attackProgress;
        living.attackAnim = attackProgress;
        living.swinging = animation.swinging();
        living.swingTime = Math.max(0, animation.swingTime());
        applyWalkAnimation(
                living,
                animation.walkPosition(),
                animation.walkSpeed(),
                partialTick
        );
    }

    static void applyFallbackLivingAnimation(
            LivingEntity living,
            float walkPosition,
            float walkSpeed,
            int hurtTime,
            int deathTime,
            float partialTick
    ) {
        living.hurtTime = Math.max(0, hurtTime);
        living.hurtDuration = Math.max(living.hurtTime, 10);
        living.deathTime = Math.max(0, deathTime);
        applyWalkAnimation(living, walkPosition, walkSpeed, partialTick);
    }

    static void updateFirstPersonProxy(
            LocalPlayer proxy,
            RemotePlayer target,
            DecodedPayload.PlayerState state
    ) {
        proxy.getInventory().selected = Math.max(0, Math.min(8, state.selectedSlot()));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack desired = target.getItemBySlot(slot);
            if (!ItemStack.matches(proxy.getItemBySlot(slot), desired)) {
                proxy.setItemSlot(slot, desired.copy());
            }
        }
        proxy.setPos(target.position());
        proxy.setYRot(target.getYRot());
        proxy.setXRot(target.getXRot());
        proxy.setYHeadRot(target.getYHeadRot());
        proxy.yBodyRot = target.yBodyRot;
        proxy.xBob = target.getXRot();
        proxy.xBobO = target.getXRot();
        proxy.yBob = target.getYRot();
        proxy.yBobO = target.getYRot();
        proxy.setPose(target.getPose());
        proxy.setDeltaMovement(target.getDeltaMovement());
        proxy.setOnGround(target.onGround());
        proxy.setOldPosAndRot();
        proxy.setHealth(Math.max(0.0F, state.health()));
        proxy.setAbsorptionAmount(Math.max(0.0F, state.absorption()));
        proxy.setInvisible(true);
        state.animation().ifPresent(animation -> applyPlayerAnimation(
                proxy,
                state.transform().pose(),
                animation,
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
        ));
    }

    private static void applyTransform(Entity entity, DecodedPayload.Transform transform) {
        entity.setPos(transform.x(), transform.y(), transform.z());
        entity.setYRot(transform.yaw());
        entity.setXRot(transform.pitch());
        entity.setOldPosAndRot();
        entity.setDeltaMovement(new Vec3(transform.velocityX(), transform.velocityY(), transform.velocityZ()));
        entity.setOnGround(transform.onGround());
        try {
            entity.setPose(Pose.valueOf(transform.pose().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            entity.setPose(Pose.STANDING);
        }
    }

    private static void syncLivingInterpolationState(Entity entity) {
        if (entity instanceof LivingEntity living) {
            living.yHeadRotO = living.yHeadRot;
            living.yBodyRotO = living.yBodyRot;
        }
    }

    private static void applyPlayerAnimation(
            AbstractClientPlayer player,
            String pose,
            DecodedPayload.PlayerAnimation animation,
            float partialTick
    ) {
        applyWalkAnimation(player, animation, partialTick);

        float attackProgress = Math.max(0.0F, Math.min(1.0F, animation.attackProgress()));
        player.oAttackAnim = attackProgress;
        player.attackAnim = attackProgress;
        player.swinging = animation.swinging();
        player.swingTime = Math.max(0, animation.swingTime());
        player.swingingArm = interactionHand(animation.swingingArm());

        InteractionHand usedHand = interactionHand(animation.usedItemHand());
        ItemStack usedItem = player.getItemInHand(usedHand);
        boolean usingItem = animation.usingItem() && !usedItem.isEmpty();
        LivingEntityAccessor living = (LivingEntityAccessor) player;
        living.dreamingrecall$setLivingEntityFlag(2, usedHand == InteractionHand.OFF_HAND);
        living.dreamingrecall$setLivingEntityFlag(1, usingItem);
        living.dreamingrecall$setUseItem(usingItem ? usedItem : ItemStack.EMPTY);
        living.dreamingrecall$setUseItemRemaining(
                usingItem ? Math.max(0, animation.useItemRemainingTicks()) : 0
        );
        float swimAmount = Math.max(0.0F, Math.min(1.0F, animation.swimAmount()));
        living.dreamingrecall$setSwimAmountOld(swimAmount);
        living.dreamingrecall$setSwimAmount(swimAmount);
        living.dreamingrecall$setFallFlyTicks(Math.max(0, animation.fallFlyingTicks()));
        boolean fallFlying = animation.fallFlyingTicks() > 0
                || pose.equalsIgnoreCase("fall_flying");
        ((EntityAccessor) player).dreamingrecall$setSharedFlag(7, fallFlying);
    }

    private static void applyWalkAnimation(
            AbstractClientPlayer player,
            DecodedPayload.PlayerAnimation animation,
            float partialTick
    ) {
        applyWalkAnimation(
                player,
                animation.walkPosition(),
                animation.walkSpeed(),
                partialTick
        );
    }

    private static void applyWalkAnimation(
            LivingEntity living,
            float recordedPosition,
            float recordedSpeed,
            float partialTick
    ) {
        float walkSpeed = Math.max(0.0F, recordedSpeed);
        WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) living.walkAnimation;
        walk.dreamingrecall$setSpeedOld(walkSpeed);
        walk.dreamingrecall$setSpeed(walkSpeed);
        walk.dreamingrecall$setPosition(
                recordedPosition + walkSpeed * (1.0F - partialTick)
        );
    }

    private static InteractionHand interactionHand(String value) {
        try {
            return InteractionHand.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return InteractionHand.MAIN_HAND;
        }
    }

    private static void applyEquipment(LivingEntity living, Iterable<DecodedPayload.EquipmentEntry> equipment) {
        for (DecodedPayload.EquipmentEntry entry : equipment) {
            try {
                living.setItemSlot(EquipmentSlot.byName(entry.slot()), item(entry.stack()));
            } catch (IllegalArgumentException | LinkageError ignored) {
                // A removed custom slot or item only degrades that equipment entry.
            }
        }
    }

    private static ItemStack item(DecodedPayload.ItemStack portable) {
        ResourceLocation id = ResourceLocation.tryParse(portable.itemId());
        Item local = id == null
                ? net.minecraft.world.item.Items.BARRIER
                : BuiltInRegistries.ITEM.getOptional(id).orElse(net.minecraft.world.item.Items.BARRIER);
        ItemStack stack = new ItemStack(local, Math.max(0, portable.count()));
        if (stack.isDamageableItem()) {
            stack.setDamageValue(Math.max(0, Math.min(portable.damage(), stack.getMaxDamage())));
        }
        portable.customNameJson().ifPresent(json -> {
            try {
                Component name = Component.Serializer.fromJson(json, ReplayRegistryAccess.get());
                if (name != null) {
                    stack.set(DataComponents.CUSTOM_NAME, name);
                }
            } catch (RuntimeException ignored) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(portable.itemId()));
            }
        });
        if (local == net.minecraft.world.item.Items.BARRIER) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Missing: " + portable.itemId()));
        }
        return stack;
    }

    private static ArmorStand placeholder(ClientLevel level, String typeId, String reason) {
        ArmorStand marker = new ArmorStand(level, 0.0, 0.0, 0.0);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setCustomName(Component.literal("Missing: " + typeId + " (" + reason + ")"));
        marker.setCustomNameVisible(true);
        return marker;
    }

    private static Component parseComponent(Entity entity, String json) {
        try {
            return Component.Serializer.fromJson(json, entity.registryAccess());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
