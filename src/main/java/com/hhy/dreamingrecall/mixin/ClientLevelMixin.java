package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.ReplayClock;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayViewController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Prevents live AI/physics from mutating snapshot-driven replay entities. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Unique
    private final Set<Integer> dreamingrecall$quarantinedReplayTicks = new HashSet<>();

    @Inject(method = "tickEntities", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$skipReplayEntityTick(CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (ReplayWorldController.isReplayLevel(level)) {
            callback.cancel();
        } else if (PacketReplayViewController.isReplayLevel(level)
                && !ReplayClock.allowEntityTick(level)) {
            callback.cancel();
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void dreamingrecall$keepReplayTimeAuthoritative(CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (ReplayWorldController.isReplayLevel(level)
                || PacketReplayViewController.isReplayLevel(level)) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "lambda$tickEntities$4",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;guardEntityTick(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V"
            )
    )
    private <T extends Entity> void dreamingrecall$guardPacketReplayEntityTick(
            ClientLevel level,
            Consumer<T> ticker,
            T entity
    ) {
        if (!PacketReplayViewController.isReplayLevel(level)) {
            level.guardEntityTick(ticker, entity);
            return;
        }
        if (dreamingrecall$quarantinedReplayTicks.contains(entity.getId())) {
            return;
        }
        try {
            ticker.accept(entity);
        } catch (Throwable failure) {
            dreamingrecall$quarantinedReplayTicks.add(entity.getId());
            DreamingRecall.LOGGER.error(
                    "Quarantined replay entity tick for {} ({})",
                    entity.getType(),
                    entity.getUUID(),
                    failure
            );
        }
    }
}
