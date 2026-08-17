package com.hhy.dreamingrecall.server;

import com.hhy.dreamingrecall.DreamingRecall;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = DreamingRecall.MOD_ID)
public final class DreamingRecallEvents {
    private DreamingRecallEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        DreamingRecallServer.INSTANCE.serverStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DreamingRecallServer.INSTANCE.serverTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        DreamingRecallServer.INSTANCE.serverStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DreamingRecallServer.INSTANCE.serverStopped(event.getServer());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            DreamingRecallServer.INSTANCE.chunkLoaded(level, chunk);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            DreamingRecallServer.INSTANCE.chunkUnloaded(level, chunk);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            DreamingRecallServer.INSTANCE.entityJoined(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            DreamingRecallServer.INSTANCE.entityLeft(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        if (sound != null) {
            DreamingRecallServer.INSTANCE.soundPlayed(
                    level,
                    sound,
                    event.getSource(),
                    event.getPosition(),
                    event.getNewVolume(),
                    event.getNewPitch()
            );
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSoundAtEntity(PlayLevelSoundEvent.AtEntity event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        Entity entity = event.getEntity();
        if (sound != null) {
            DreamingRecallServer.INSTANCE.soundPlayed(
                    level,
                    sound,
                    event.getSource(),
                    entity.position(),
                    event.getNewVolume(),
                    event.getNewPitch()
            );
        }
    }
}
