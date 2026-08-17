package com.hhy.dreamingrecall.capture;

import com.hhy.dreamingrecall.network.CameraSamplePayload;
import com.hhy.dreamingrecall.network.PlayerVisualSamplePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MinecraftRecordEncoder {
    private static final int PAYLOAD_SCHEMA = 1;
    private static final int CHUNK_BASELINE_SCHEMA = 2;
    private static final int BLOCK_ENTITY_STATE_SCHEMA = 2;
    private static final int ENTITY_STATE_SCHEMA = 3;
    private static final int PLAYER_STATE_SCHEMA = 2;
    private static final int CLIENT_CAMERA_SAMPLE_SCHEMA = 2;
    private static final int CLIENT_PLAYER_VISUAL_SAMPLE_SCHEMA = 1;

    private MinecraftRecordEncoder() {
    }

    public static byte[] playerState(ServerPlayer player) {
        return playerState(player, player.gameMode.getGameModeForPlayer().getName());
    }

    public static byte[] playerState(Player player, String gameMode) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PLAYER_STATE_SCHEMA);
            BinaryPayloadWriter.writeUuid(output, player.getUUID());
            BinaryPayloadWriter.writeString(output, player.getGameProfile().getName());
            writeTransform(output, player);
            Vec3 eye = player.getEyePosition();
            output.writeDouble(eye.x());
            output.writeDouble(eye.y());
            output.writeDouble(eye.z());
            output.writeFloat(player.getYHeadRot());
            output.writeFloat(player.yBodyRot);
            output.writeFloat(player.getHealth());
            output.writeFloat(player.getAbsorptionAmount());
            output.writeInt(player.getFoodData().getFoodLevel());
            output.writeInt(player.getInventory().selected);
            BinaryPayloadWriter.writeString(output, gameMode);
            writeEquipment(output, player, player.registryAccess());
            writePlayerAnimation(output, player);
        });
    }

    public static byte[] clientCameraSample(
            java.util.UUID playerId,
            CameraSamplePayload sample
    ) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(CLIENT_CAMERA_SAMPLE_SCHEMA);
            BinaryPayloadWriter.writeUuid(output, playerId);
            output.writeLong(sample.clientNanos());
            output.writeDouble(sample.x());
            output.writeDouble(sample.y());
            output.writeDouble(sample.z());
            output.writeFloat(sample.yaw());
            output.writeFloat(sample.pitch());
            output.writeFloat(sample.roll());
            output.writeFloat(sample.fov());
            writeClientPlayerVisual(output, sample.playerVisual());
        });
    }

    public static byte[] clientPlayerVisualSample(
            java.util.UUID playerId,
            PlayerVisualSamplePayload sample
    ) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(CLIENT_PLAYER_VISUAL_SAMPLE_SCHEMA);
            BinaryPayloadWriter.writeUuid(output, playerId);
            output.writeLong(sample.clientNanos());
            writeClientPlayerVisual(output, sample.playerVisual());
        });
    }

    public static byte[] entityState(Entity entity, boolean spawn) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(ENTITY_STATE_SCHEMA);
            output.writeBoolean(spawn);
            BinaryPayloadWriter.writeUuid(output, entity.getUUID());
            BinaryPayloadWriter.writeString(output, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            output.writeBoolean(true);
            writeTransform(output, entity);
            output.writeFloat(entity.getYHeadRot());
            output.writeFloat(entity.getBbWidth());
            output.writeFloat(entity.getBbHeight());
            output.writeBoolean(entity.isInvisible());
            output.writeBoolean(entity.isCurrentlyGlowing());
            output.writeBoolean(entity.isNoGravity());
            output.writeBoolean(entity.isOnFire());
            output.writeInt(entity.getRemainingFireTicks());
            Component customName = entity.getCustomName();
            output.writeBoolean(customName != null);
            if (customName != null) {
                BinaryPayloadWriter.writeString(output, Component.Serializer.toJson(customName, entity.registryAccess()));
            }
            if (entity instanceof LivingEntity living) {
                output.writeBoolean(true);
                output.writeFloat(living.getHealth());
                output.writeFloat(living.getYHeadRot());
                output.writeFloat(living.yBodyRot);
                writeEquipment(output, living, living.registryAccess());
                output.writeInt(living.hurtTime);
                output.writeInt(living.deathTime);
                output.writeFloat(living.walkAnimation.position());
                output.writeFloat(living.walkAnimation.speed());
                output.writeFloat(living.getAttackAnim(1.0F));
                output.writeBoolean(living.swinging);
                output.writeInt(living.swingTime);
            } else {
                output.writeBoolean(false);
            }
        });
    }

    public static long entityFingerprint(Entity entity) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, Double.doubleToLongBits(entity.getX()));
        hash = mix(hash, Double.doubleToLongBits(entity.getY()));
        hash = mix(hash, Double.doubleToLongBits(entity.getZ()));
        hash = mix(hash, Float.floatToIntBits(entity.getYRot()));
        hash = mix(hash, Float.floatToIntBits(entity.getXRot()));
        hash = mix(hash, entity.getPose().ordinal());
        hash = mix(hash, entity.onGround() ? 1 : 0);
        hash = mix(hash, entity.isInvisible() ? 1 : 0);
        hash = mix(hash, entity.isCurrentlyGlowing() ? 1 : 0);
        hash = mix(hash, entity.isOnFire() ? 1 : 0);
        Vec3 movement = entity.getDeltaMovement();
        hash = mix(hash, Double.doubleToLongBits(movement.x()));
        hash = mix(hash, Double.doubleToLongBits(movement.y()));
        hash = mix(hash, Double.doubleToLongBits(movement.z()));
        if (entity instanceof LivingEntity living) {
            hash = mix(hash, Float.floatToIntBits(living.getHealth()));
            hash = mix(hash, Float.floatToIntBits(living.getYHeadRot()));
            hash = mix(hash, Float.floatToIntBits(living.yBodyRot));
            hash = mix(hash, living.hurtTime);
            hash = mix(hash, living.deathTime);
            hash = mix(hash, Float.floatToIntBits(living.walkAnimation.position()));
            hash = mix(hash, Float.floatToIntBits(living.walkAnimation.speed()));
            hash = mix(hash, Float.floatToIntBits(living.getAttackAnim(1.0F)));
            hash = mix(hash, living.swinging ? 1 : 0);
            hash = mix(hash, living.swingTime);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                hash = mix(hash, BuiltInRegistries.ITEM.getId(stack.getItem()));
                hash = mix(hash, stack.getCount());
                hash = mix(hash, stack.getDamageValue());
            }
        }
        return hash;
    }

    public static byte[] entityRemoved(Entity entity) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            BinaryPayloadWriter.writeUuid(output, entity.getUUID());
        });
    }

    public static byte[] entityEffect(java.util.UUID entityId, String effect) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            BinaryPayloadWriter.writeUuid(output, entityId);
            BinaryPayloadWriter.writeString(output, effect);
        });
    }

    public static byte[] dimensionState(Level level) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            output.writeLong(level.getGameTime());
            output.writeLong(level.getDayTime());
            output.writeFloat(level.getRainLevel(1.0F));
            output.writeFloat(level.getThunderLevel(1.0F));
            output.writeInt(level.getDifficulty().getId());
            output.writeDouble(level.getWorldBorder().getCenterX());
            output.writeDouble(level.getWorldBorder().getCenterZ());
            output.writeDouble(level.getWorldBorder().getSize());
        });
    }

    public static byte[] chunkBaseline(Level level, LevelChunk chunk) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(CHUNK_BASELINE_SCHEMA);
            output.writeBoolean(true);
            output.writeInt(chunk.getPos().x);
            output.writeInt(chunk.getPos().z);
            LevelChunkSection[] sections = chunk.getSections();
            output.writeInt(sections.length);
            var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            for (int index = 0; index < sections.length; index++) {
                int sectionY = chunk.getSectionYFromSectionIndex(index);
                LevelChunkSection section = sections[index];
                output.writeInt(sectionY);

                PalettedContainerRO.PackedData<BlockState> states = section.getStates().pack(
                        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                        PalettedContainer.Strategy.SECTION_STATES
                );
                output.writeInt(states.paletteEntries().size());
                for (BlockState state : states.paletteEntries()) {
                    writeBlockState(output, state);
                }
                writePackedStorage(output, states);

                PalettedContainerRO.PackedData<Holder<Biome>> biomes = section.getBiomes().pack(
                        biomeRegistry.asHolderIdMap(),
                        PalettedContainer.Strategy.SECTION_BIOMES
                );
                output.writeInt(biomes.paletteEntries().size());
                for (Holder<Biome> biome : biomes.paletteEntries()) {
                    String id = biome.unwrapKey()
                            .map(key -> key.location().toString())
                            .orElseGet(() -> String.valueOf(biomeRegistry.getKey(biome.value())));
                    BinaryPayloadWriter.writeString(output, id);
                }
                writePackedStorage(output, biomes);

                writeLightLayer(output, level, chunk, sectionY, LightLayer.SKY);
                writeLightLayer(output, level, chunk, sectionY, LightLayer.BLOCK);
            }

            List<BlockEntity> blockEntities = new ArrayList<>(chunk.getBlockEntities().values());
            output.writeInt(blockEntities.size());
            for (BlockEntity blockEntity : blockEntities) {
                writeBlockEntity(output, level, blockEntity);
            }
        });
    }

    public static byte[] blockChange(BlockPos pos, BlockState state) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            output.writeLong(pos.asLong());
            writeBlockState(output, state);
        });
    }

    public static byte[] blockEntityState(Level level, BlockEntity blockEntity) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(BLOCK_ENTITY_STATE_SCHEMA);
            writeBlockEntity(output, level, blockEntity);
        });
    }

    public static byte[] chunkLight(Level level, LevelChunk chunk) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            output.writeInt(chunk.getPos().x);
            output.writeInt(chunk.getPos().z);
            LevelChunkSection[] sections = chunk.getSections();
            output.writeInt(sections.length);
            for (int index = 0; index < sections.length; index++) {
                int sectionY = chunk.getSectionYFromSectionIndex(index);
                output.writeInt(sectionY);
                writeLightLayer(output, level, chunk, sectionY, LightLayer.SKY);
                writeLightLayer(output, level, chunk, sectionY, LightLayer.BLOCK);
            }
        });
    }

    public static byte[] chatDelivery(Collection<java.util.UUID> recipients, String renderedJson, String kind) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            BinaryPayloadWriter.writeString(output, kind);
            BinaryPayloadWriter.writeString(output, renderedJson);
            output.writeInt(recipients.size());
            for (java.util.UUID recipient : recipients) {
                BinaryPayloadWriter.writeUuid(output, recipient);
            }
        });
    }

    public static byte[] chunkPlaceholder(LevelChunk chunk, String reason) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            output.writeBoolean(false);
            output.writeInt(chunk.getPos().x);
            output.writeInt(chunk.getPos().z);
            BinaryPayloadWriter.writeString(output, reason);
        });
    }

    public static byte[] entityPlaceholder(Entity entity, boolean spawn, String reason) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(ENTITY_STATE_SCHEMA);
            output.writeBoolean(spawn);
            BinaryPayloadWriter.writeUuid(output, entity.getUUID());
            BinaryPayloadWriter.writeString(output, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            output.writeBoolean(false);
            writeTransform(output, entity);
            BinaryPayloadWriter.writeString(output, reason);
        });
    }

    public static byte[] sound(
            Holder<SoundEvent> sound,
            SoundSource source,
            Vec3 position,
            float volume,
            float pitch
    ) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(PAYLOAD_SCHEMA);
            String id = sound.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElseGet(() -> BuiltInRegistries.SOUND_EVENT.getKey(sound.value()).toString());
            BinaryPayloadWriter.writeString(output, id);
            BinaryPayloadWriter.writeString(output, source.getName());
            output.writeDouble(position.x());
            output.writeDouble(position.y());
            output.writeDouble(position.z());
            output.writeFloat(volume);
            output.writeFloat(pitch);
        });
    }

    private static void writeTransform(DataOutputStream output, Entity entity) throws IOException {
        output.writeDouble(entity.getX());
        output.writeDouble(entity.getY());
        output.writeDouble(entity.getZ());
        output.writeFloat(entity.getYRot());
        output.writeFloat(entity.getXRot());
        Vec3 movement = entity.getDeltaMovement();
        output.writeDouble(movement.x());
        output.writeDouble(movement.y());
        output.writeDouble(movement.z());
        BinaryPayloadWriter.writeString(output, entity.getPose().name().toLowerCase(Locale.ROOT));
        output.writeBoolean(entity.onGround());
    }

    private static void writePlayerAnimation(DataOutputStream output, Player player) throws IOException {
        output.writeFloat(player.walkAnimation.position());
        output.writeFloat(player.walkAnimation.speed());
        output.writeFloat(player.getAttackAnim(1.0F));
        output.writeBoolean(player.swinging);
        output.writeInt(player.swingTime);
        BinaryPayloadWriter.writeString(
                output,
                (player.swingingArm == null ? net.minecraft.world.InteractionHand.MAIN_HAND : player.swingingArm).name()
        );
        output.writeBoolean(player.isUsingItem());
        BinaryPayloadWriter.writeString(output, player.getUsedItemHand().name());
        output.writeInt(player.getUseItemRemainingTicks());
        output.writeFloat(player.getSwimAmount(1.0F));
        output.writeInt(player.getFallFlyingTicks());
    }

    private static void writePlayerAnimation(
            DataOutputStream output,
            CameraSamplePayload.PlayerVisual visual
    ) throws IOException {
        output.writeFloat(visual.walkPosition());
        output.writeFloat(visual.walkSpeed());
        output.writeFloat(visual.attackProgress());
        output.writeBoolean(visual.swinging());
        output.writeInt(visual.swingTime());
        BinaryPayloadWriter.writeString(output, visual.swingingArm());
        output.writeBoolean(visual.usingItem());
        BinaryPayloadWriter.writeString(output, visual.usedItemHand());
        output.writeInt(visual.useItemRemainingTicks());
        output.writeFloat(visual.swimAmount());
        output.writeInt(visual.fallFlyingTicks());
    }

    private static void writeClientPlayerVisual(
            DataOutputStream output,
            CameraSamplePayload.PlayerVisual visual
    ) throws IOException {
        output.writeDouble(visual.x());
        output.writeDouble(visual.y());
        output.writeDouble(visual.z());
        output.writeFloat(visual.yaw());
        output.writeFloat(visual.pitch());
        output.writeDouble(visual.velocityX());
        output.writeDouble(visual.velocityY());
        output.writeDouble(visual.velocityZ());
        BinaryPayloadWriter.writeString(output, visual.pose());
        output.writeBoolean(visual.onGround());
        output.writeFloat(visual.headYaw());
        output.writeFloat(visual.bodyYaw());
        writePlayerAnimation(output, visual);
    }

    private static void writeEquipment(
            DataOutputStream output,
            LivingEntity living,
            HolderLookup.Provider registries
    ) throws IOException {
        EquipmentSlot[] slots = EquipmentSlot.values();
        output.writeInt(slots.length);
        for (EquipmentSlot slot : slots) {
            BinaryPayloadWriter.writeString(output, slot.getName());
            writeItem(output, living.getItemBySlot(slot), registries);
        }
    }

    private static void writeItem(
            DataOutputStream output,
            ItemStack stack,
            HolderLookup.Provider registries
    ) throws IOException {
        BinaryPayloadWriter.writeString(output, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        output.writeInt(stack.getCount());
        output.writeInt(stack.getDamageValue());
        output.writeBoolean(stack.hasFoil());
        output.writeBoolean(stack.has(DataComponents.CUSTOM_NAME));
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            BinaryPayloadWriter.writeString(output, Component.Serializer.toJson(stack.getHoverName(), registries));
        }
    }

    private static void writeBlockState(DataOutputStream output, BlockState state) throws IOException {
        BinaryPayloadWriter.writeString(output, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        output.writeInt(properties.size());
        for (Property<?> property : properties) {
            BinaryPayloadWriter.writeString(output, property.getName());
            BinaryPayloadWriter.writeString(output, propertyValueName(state, property));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(BlockState state, Property property) {
        Comparable value = state.getValue(property);
        return property.getName(value);
    }

    private static void writePackedStorage(DataOutputStream output, PalettedContainerRO.PackedData<?> packed) throws IOException {
        long[] storage = packed.storage().map(stream -> stream.toArray()).orElseGet(() -> new long[0]);
        output.writeInt(storage.length);
        for (long value : storage) {
            output.writeLong(value);
        }
    }

    private static void writeLightLayer(
            DataOutputStream output,
            Level level,
            LevelChunk chunk,
            int sectionY,
            LightLayer layer
    ) throws IOException {
        DataLayer data = level.getLightEngine()
                .getLayerListener(layer)
                .getDataLayerData(SectionPos.of(chunk.getPos().x, sectionY, chunk.getPos().z));
        output.writeBoolean(data != null);
        if (data != null) {
            byte[] light = data.copy().getData();
            output.writeInt(light.length);
            output.write(light);
        }
    }

    private static void writeBlockEntity(DataOutputStream output, Level level, BlockEntity blockEntity) throws IOException {
        output.writeLong(blockEntity.getBlockPos().asLong());
        BinaryPayloadWriter.writeString(output, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
        try {
            CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
            ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
            try (DataOutputStream nbtOutput = new DataOutputStream(nbtBytes)) {
                NbtIo.write(tag, nbtOutput);
            }
            output.writeBoolean(true);
            output.writeInt(nbtBytes.size());
            nbtBytes.writeTo(output);
        } catch (Exception | LinkageError failure) {
            output.writeBoolean(false);
            BinaryPayloadWriter.writeString(output, failure.getClass().getName());
        }
    }

    private static long mix(long current, long value) {
        return (current ^ value) * 0x100000001b3L;
    }
}
