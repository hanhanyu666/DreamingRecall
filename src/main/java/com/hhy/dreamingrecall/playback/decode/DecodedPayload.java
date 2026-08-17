package com.hhy.dreamingrecall.playback.decode;

import com.hhy.dreamingrecall.api.extension.ExtensionFrame;
import com.hhy.dreamingrecall.archive.CoreRecordType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public sealed interface DecodedPayload {
    record Empty(CoreRecordType type) implements DecodedPayload {
        public Empty {
            Objects.requireNonNull(type, "type");
        }
    }

    record SessionStart(String archiveId) implements DecodedPayload {
        public SessionStart {
            Objects.requireNonNull(archiveId, "archiveId");
        }
    }

    record BaselineMarker(boolean begin, String reason) implements DecodedPayload {
        public BaselineMarker {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record RecordingGap(long startArchiveNanos, long endArchiveNanos, long droppedRecords)
            implements DecodedPayload {
    }

    record DimensionState(
            long gameTime,
            long dayTime,
            float rainLevel,
            float thunderLevel,
            int difficultyId,
            double borderCenterX,
            double borderCenterZ,
            double borderSize
    ) implements DecodedPayload {
    }

    record ChunkBaseline(
            boolean available,
            int chunkX,
            int chunkZ,
            List<ChunkSection> sections,
            List<BlockEntityState> blockEntities,
            String unavailableReason
    ) implements DecodedPayload {
        public ChunkBaseline {
            sections = List.copyOf(sections);
            blockEntities = List.copyOf(blockEntities);
            Objects.requireNonNull(unavailableReason, "unavailableReason");
        }
    }

    record ChunkCoordinates(int chunkX, int chunkZ) implements DecodedPayload {
    }

    record BlockChange(long packedPosition, BlockState state) implements DecodedPayload {
        public BlockChange {
            Objects.requireNonNull(state, "state");
        }
    }

    record BlockEntityUpdate(BlockEntityState blockEntity) implements DecodedPayload {
        public BlockEntityUpdate {
            Objects.requireNonNull(blockEntity, "blockEntity");
        }
    }

    record BlockEntityRemove(long packedPosition) implements DecodedPayload {
    }

    record ChunkLight(int chunkX, int chunkZ, List<SectionLight> sections) implements DecodedPayload {
        public ChunkLight {
            sections = List.copyOf(sections);
        }
    }

    record EntityState(
            boolean spawn,
            UUID uuid,
            String typeId,
            Transform transform,
            Optional<EntityDetails> details,
            Optional<String> unavailableReason
    ) implements DecodedPayload {
        public EntityState {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(details, "details");
            Objects.requireNonNull(unavailableReason, "unavailableReason");
        }
    }

    record EntityRemove(UUID uuid) implements DecodedPayload {
        public EntityRemove {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record EntityEffect(UUID entityId, String effect) implements DecodedPayload {
        public EntityEffect {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(effect, "effect");
        }
    }

    record PlayerState(
            UUID uuid,
            String name,
            Transform transform,
            double eyeX,
            double eyeY,
            double eyeZ,
            float headYaw,
            float bodyYaw,
            float health,
            float absorption,
            int foodLevel,
            int selectedSlot,
            String gameMode,
            List<EquipmentEntry> equipment,
            Optional<PlayerAnimation> animation
    ) implements DecodedPayload {
        public PlayerState(
                UUID uuid,
                String name,
                Transform transform,
                double eyeX,
                double eyeY,
                double eyeZ,
                float headYaw,
                float bodyYaw,
                float health,
                float absorption,
                int foodLevel,
                int selectedSlot,
                String gameMode,
                List<EquipmentEntry> equipment
        ) {
            this(
                    uuid,
                    name,
                    transform,
                    eyeX,
                    eyeY,
                    eyeZ,
                    headYaw,
                    bodyYaw,
                    health,
                    absorption,
                    foodLevel,
                    selectedSlot,
                    gameMode,
                    equipment,
                    Optional.empty()
            );
        }

        public PlayerState {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(gameMode, "gameMode");
            equipment = List.copyOf(equipment);
            Objects.requireNonNull(animation, "animation");
        }
    }

    record CameraSample(
            UUID playerId,
            long clientNanos,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float roll,
            float fov,
            Optional<ClientPlayerSample> playerSample
    ) implements DecodedPayload {
        public CameraSample(
                UUID playerId,
                long clientNanos,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                float roll,
                float fov
        ) {
            this(playerId, clientNanos, x, y, z, yaw, pitch, roll, fov, Optional.empty());
        }

        public CameraSample {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerSample, "playerSample");
        }
    }

    record ClientPlayerSample(
            Transform transform,
            float headYaw,
            float bodyYaw,
            PlayerAnimation animation
    ) {
        public ClientPlayerSample {
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(animation, "animation");
        }
    }

    record PlayerVisualSample(
            UUID playerId,
            long clientNanos,
            ClientPlayerSample playerSample
    ) implements DecodedPayload {
        public PlayerVisualSample {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerSample, "playerSample");
        }
    }

    record PlayerAnimation(
            float walkPosition,
            float walkSpeed,
            float attackProgress,
            boolean swinging,
            int swingTime,
            String swingingArm,
            boolean usingItem,
            String usedItemHand,
            int useItemRemainingTicks,
            float swimAmount,
            int fallFlyingTicks
    ) {
        public PlayerAnimation {
            Objects.requireNonNull(swingingArm, "swingingArm");
            Objects.requireNonNull(usedItemHand, "usedItemHand");
        }
    }

    record ChatDelivery(String kind, String renderedJson, List<UUID> recipients) implements DecodedPayload {
        public ChatDelivery {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(renderedJson, "renderedJson");
            recipients = List.copyOf(recipients);
        }
    }

    record GameSound(
            String soundId,
            String source,
            double x,
            double y,
            double z,
            float volume,
            float pitch
    ) implements DecodedPayload {
        public GameSound {
            Objects.requireNonNull(soundId, "soundId");
            Objects.requireNonNull(source, "source");
        }
    }

    record Extension(ExtensionFrame frame) implements DecodedPayload {
        public Extension {
            Objects.requireNonNull(frame, "frame");
        }
    }

    record Unknown(int typeId, byte[] payload) implements DecodedPayload {
        public Unknown {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    record Transform(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double velocityX,
            double velocityY,
            double velocityZ,
            String pose,
            boolean onGround
    ) {
        public Transform {
            Objects.requireNonNull(pose, "pose");
        }
    }

    record BlockState(String blockId, Map<String, String> properties) {
        public BlockState {
            Objects.requireNonNull(blockId, "blockId");
            properties = Map.copyOf(properties);
        }
    }

    record PackedStorage(long[] values) {
        public PackedStorage {
            values = values.clone();
        }

        @Override
        public long[] values() {
            return values.clone();
        }
    }

    record LightData(boolean present, byte[] values) {
        public LightData {
            values = values.clone();
        }

        @Override
        public byte[] values() {
            return values.clone();
        }
    }

    record SectionLight(int sectionY, LightData sky, LightData block) {
        public SectionLight {
            Objects.requireNonNull(sky, "sky");
            Objects.requireNonNull(block, "block");
        }
    }

    record ChunkSection(
            int sectionY,
            List<BlockState> blockPalette,
            PackedStorage blockStorage,
            List<String> biomePalette,
            PackedStorage biomeStorage,
            LightData skyLight,
            LightData blockLight
    ) {
        public ChunkSection {
            blockPalette = List.copyOf(blockPalette);
            Objects.requireNonNull(blockStorage, "blockStorage");
            biomePalette = List.copyOf(biomePalette);
            Objects.requireNonNull(biomeStorage, "biomeStorage");
            Objects.requireNonNull(skyLight, "skyLight");
            Objects.requireNonNull(blockLight, "blockLight");
        }
    }

    record BlockEntityState(
            long packedPosition,
            String typeId,
            boolean available,
            byte[] nbt,
            String unavailableReason
    ) {
        public BlockEntityState {
            Objects.requireNonNull(typeId, "typeId");
            nbt = nbt.clone();
            Objects.requireNonNull(unavailableReason, "unavailableReason");
        }

        @Override
        public byte[] nbt() {
            return nbt.clone();
        }
    }

    record ItemStack(
            String itemId,
            int count,
            int damage,
            boolean foil,
            Optional<String> customNameJson
    ) {
        public ItemStack {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(customNameJson, "customNameJson");
        }
    }

    record EquipmentEntry(String slot, ItemStack stack) {
        public EquipmentEntry {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(stack, "stack");
        }
    }

    record LivingDetails(
            float health,
            float headYaw,
            float bodyYaw,
            List<EquipmentEntry> equipment,
            Optional<LivingAnimation> animation
    ) {
        public LivingDetails(float health, float headYaw, float bodyYaw, List<EquipmentEntry> equipment) {
            this(health, headYaw, bodyYaw, equipment, Optional.empty());
        }

        public LivingDetails {
            equipment = List.copyOf(equipment);
            Objects.requireNonNull(animation, "animation");
        }
    }

    record LivingAnimation(
            int hurtTime,
            int deathTime,
            float walkPosition,
            float walkSpeed,
            float attackProgress,
            boolean swinging,
            int swingTime
    ) {
    }

    record EntityDetails(
            float headYaw,
            float width,
            float height,
            boolean invisible,
            boolean glowing,
            boolean noGravity,
            boolean onFire,
            int remainingFireTicks,
            Optional<String> customNameJson,
            Optional<LivingDetails> living
    ) {
        public EntityDetails(
                float headYaw,
                float width,
                float height,
                boolean invisible,
                boolean glowing,
                boolean noGravity,
                Optional<String> customNameJson,
                Optional<LivingDetails> living
        ) {
            this(
                    headYaw,
                    width,
                    height,
                    invisible,
                    glowing,
                    noGravity,
                    false,
                    0,
                    customNameJson,
                    living
            );
        }

        public EntityDetails {
            Objects.requireNonNull(customNameJson, "customNameJson");
            Objects.requireNonNull(living, "living");
        }
    }
}
