package com.hhy.dreamingrecall.client.playback;

import net.minecraft.world.entity.LivingEntity;

/** Keeps every rotation field used by Minecraft's different camera entities in sync. */
public final class ReplayCameraEntityState {
    private ReplayCameraEntityState() {
    }

    public static void applyRotation(LivingEntity cameraEntity, float yaw, float pitch) {
        cameraEntity.setYRot(yaw);
        cameraEntity.setXRot(pitch);
        cameraEntity.setYHeadRot(yaw);
        cameraEntity.yHeadRotO = yaw;
        cameraEntity.yBodyRot = yaw;
        cameraEntity.yBodyRotO = yaw;
        cameraEntity.setOldPosAndRot();
    }
}
