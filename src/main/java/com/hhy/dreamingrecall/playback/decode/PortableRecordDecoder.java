package com.hhy.dreamingrecall.playback.decode;

import com.hhy.dreamingrecall.api.extension.ExtensionFrameCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PortableRecordDecoder {
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_SECTIONS = 4096;
    private static final int MAX_BLOCK_PALETTE = 4096;
    private static final int MAX_BIOME_PALETTE = 4096;
    private static final int MAX_PROPERTIES = 1024;
    private static final int MAX_PACKED_LONGS = 4 * 1024 * 1024;
    private static final int MAX_LIGHT_BYTES = 4096;
    private static final int MAX_BLOCK_ENTITIES = 1_000_000;
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EQUIPMENT = 128;
    private static final int MAX_RECIPIENTS = 1_000_000;

    public DecodedPayload decode(ReplayRecord record) throws IOException {
        Optional<CoreRecordType> knownType = CoreRecordType.fromId(record.typeId());
        if (knownType.isEmpty()) {
            return new DecodedPayload.Unknown(record.typeId(), record.payloadCopy());
        }
        CoreRecordType type = knownType.get();
        byte[] payload = record.payloadCopy();
        return switch (type) {
            case SESSION_START -> new DecodedPayload.SessionStart(new String(payload, StandardCharsets.UTF_8));
            case SESSION_END, SERVER_TICK, BASELINE_END -> decodeEmpty(type, payload);
            case BASELINE_BEGIN -> new DecodedPayload.BaselineMarker(
                    true,
                    new String(payload, StandardCharsets.UTF_8)
            );
            case RECORDING_GAP -> decodeGap(payload);
            case DIMENSION_STATE -> decodeDimensionState(payload);
            case CHUNK_BASELINE -> decodeChunkBaseline(payload);
            case CHUNK_OBSERVATION_END -> decodeChunkCoordinates(payload);
            case BLOCK_CHANGE -> decodeBlockChange(payload);
            case BLOCK_ENTITY_STATE -> decodeBlockEntityUpdate(payload);
            case CHUNK_LIGHT -> decodeChunkLight(payload);
            case BLOCK_ENTITY_REMOVE -> decodeBlockEntityRemove(payload);
            case ENTITY_SPAWN, ENTITY_STATE -> decodeEntityState(payload);
            case ENTITY_REMOVE -> decodeEntityRemove(payload);
            case ENTITY_EFFECT -> decodeEntityEffect(payload);
            case PLAYER_STATE -> decodePlayerState(payload);
            case CLIENT_CAMERA_SAMPLE -> decodeCameraSample(payload);
            case CLIENT_PLAYER_VISUAL_SAMPLE -> decodePlayerVisualSample(payload);
            case CHAT_DELIVERY -> decodeChat(payload);
            case GAME_SOUND -> decodeSound(payload);
            case PACKET_FRAME, PLAYER_TELEMETRY, VISIBILITY_INTERVAL, TRACK_CHECKPOINT ->
                    new DecodedPayload.Unknown(type.id(), payload);
            case EXTENSION_PAYLOAD -> new DecodedPayload.Extension(ExtensionFrameCodec.decode(payload));
        };
    }

    private static DecodedPayload decodeEmpty(CoreRecordType type, byte[] payload) throws IOException {
        if (payload.length != 0) {
            throw new IOException(type + " payload is not empty");
        }
        return type == CoreRecordType.BASELINE_END
                ? new DecodedPayload.BaselineMarker(false, "")
                : new DecodedPayload.Empty(type);
    }

    private static DecodedPayload decodeGap(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        long start = input.readLong();
        long end = input.readLong();
        long dropped = input.readLong();
        input.requireEnd("recording gap");
        if (start < 0 || end < start || dropped <= 0) {
            throw new IOException("Invalid recording gap bounds");
        }
        return new DecodedPayload.RecordingGap(start, end, dropped);
    }

    private static DecodedPayload decodeDimensionState(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("dimension state", 1);
        DecodedPayload.DimensionState state = new DecodedPayload.DimensionState(
                input.readLong(),
                input.readLong(),
                input.readFloat(),
                input.readFloat(),
                input.readInt(),
                input.readDouble(),
                input.readDouble(),
                input.readDouble()
        );
        input.requireEnd("dimension state");
        return state;
    }

    private static DecodedPayload decodeChunkBaseline(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        int schema = input.readSchema("chunk baseline", 1, 2);
        boolean available = input.readBoolean();
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        if (!available) {
            String reason = input.readString("chunk placeholder reason", MAX_STRING_BYTES);
            input.requireEnd("chunk baseline placeholder");
            return new DecodedPayload.ChunkBaseline(false, chunkX, chunkZ, List.of(), List.of(), reason);
        }

        int sectionCount = input.readCount("chunk section", MAX_SECTIONS);
        ArrayList<DecodedPayload.ChunkSection> sections = new ArrayList<>(sectionCount);
        for (int index = 0; index < sectionCount; index++) {
            int sectionY = input.readInt();
            int blockPaletteCount = input.readCount("block palette", MAX_BLOCK_PALETTE);
            ArrayList<DecodedPayload.BlockState> blockPalette = new ArrayList<>(blockPaletteCount);
            for (int state = 0; state < blockPaletteCount; state++) {
                blockPalette.add(readBlockState(input));
            }
            DecodedPayload.PackedStorage blockStorage = readPackedStorage(input, "block storage");
            int biomePaletteCount = input.readCount("biome palette", MAX_BIOME_PALETTE);
            ArrayList<String> biomePalette = new ArrayList<>(biomePaletteCount);
            for (int biome = 0; biome < biomePaletteCount; biome++) {
                biomePalette.add(input.readString("biome id", MAX_STRING_BYTES));
            }
            DecodedPayload.PackedStorage biomeStorage = readPackedStorage(input, "biome storage");
            DecodedPayload.LightData sky = readLight(input, "sky light");
            DecodedPayload.LightData block = readLight(input, "block light");
            sections.add(new DecodedPayload.ChunkSection(
                    sectionY,
                    blockPalette,
                    blockStorage,
                    biomePalette,
                    biomeStorage,
                    sky,
                    block
            ));
        }

        int blockEntityCount = input.readCount("block entity", MAX_BLOCK_ENTITIES);
        List<DecodedPayload.BlockEntityState> blockEntities;
        if (schema == 1) {
            blockEntities = readLegacyOrEnvelopedBlockEntities(input.readRemainingBytes(), blockEntityCount);
        } else {
            blockEntities = readBlockEntities(input, blockEntityCount, true);
            input.requireEnd("chunk baseline");
        }
        return new DecodedPayload.ChunkBaseline(true, chunkX, chunkZ, sections, blockEntities, "");
    }

    private static DecodedPayload decodeChunkCoordinates(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("chunk coordinates", 1);
        DecodedPayload.ChunkCoordinates coordinates = new DecodedPayload.ChunkCoordinates(
                input.readInt(),
                input.readInt()
        );
        input.requireEnd("chunk coordinates");
        return coordinates;
    }

    private static DecodedPayload decodeBlockChange(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("block change", 1);
        DecodedPayload.BlockChange change = new DecodedPayload.BlockChange(
                input.readLong(),
                readBlockState(input)
        );
        input.requireEnd("block change");
        return change;
    }

    private static DecodedPayload decodeBlockEntityUpdate(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        int schema = input.readSchema("block entity state", 1, 2);
        DecodedPayload.BlockEntityUpdate update = new DecodedPayload.BlockEntityUpdate(
                readBlockEntity(input, schema >= 2)
        );
        input.requireEnd("block entity state");
        return update;
    }

    private static DecodedPayload decodeBlockEntityRemove(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("block entity removal", 1);
        DecodedPayload.BlockEntityRemove remove = new DecodedPayload.BlockEntityRemove(input.readLong());
        input.requireEnd("block entity removal");
        return remove;
    }

    private static DecodedPayload decodeChunkLight(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("chunk light", 1);
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int count = input.readCount("light section", MAX_SECTIONS);
        ArrayList<DecodedPayload.SectionLight> sections = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            sections.add(new DecodedPayload.SectionLight(
                    input.readInt(),
                    readLight(input, "sky light"),
                    readLight(input, "block light")
            ));
        }
        input.requireEnd("chunk light");
        return new DecodedPayload.ChunkLight(chunkX, chunkZ, sections);
    }

    private static DecodedPayload decodeEntityState(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        int schema = input.readSchema("entity state", 1, 2, 3);
        boolean spawn = input.readBoolean();
        UUID uuid = input.readUuid();
        String typeId = input.readString("entity type id", MAX_STRING_BYTES);
        if (schema >= 2) {
            boolean available = input.readBoolean();
            DecodedPayload.Transform transform = readTransform(input);
            if (!available) {
                String reason = input.readString("entity placeholder reason", MAX_STRING_BYTES);
                input.requireEnd("entity placeholder");
                return new DecodedPayload.EntityState(
                        spawn,
                        uuid,
                        typeId,
                        transform,
                        Optional.empty(),
                        Optional.of(reason)
                );
            }
            DecodedPayload.EntityDetails details = readEntityDetails(input, schema);
            input.requireEnd("entity state");
            return new DecodedPayload.EntityState(
                    spawn,
                    uuid,
                    typeId,
                    transform,
                    Optional.of(details),
                    Optional.empty()
            );
        }
        return decodeLegacyEntityState(payload, spawn, uuid, typeId);
    }

    private static DecodedPayload decodeLegacyEntityState(
            byte[] payload,
            boolean spawn,
            UUID uuid,
            String typeId
    ) throws IOException {
        PayloadReader full = new PayloadReader(payload);
        full.readSchema("entity state", 1);
        full.readBoolean();
        full.readUuid();
        full.readString("entity type id", MAX_STRING_BYTES);
        DecodedPayload.Transform transform = readTransform(full);
        try {
            DecodedPayload.EntityDetails details = readEntityDetails(full, 1);
            full.requireEnd("legacy entity state");
            return new DecodedPayload.EntityState(
                    spawn,
                    uuid,
                    typeId,
                    transform,
                    Optional.of(details),
                    Optional.empty()
            );
        } catch (IOException normalFailure) {
            PayloadReader placeholder = new PayloadReader(payload);
            placeholder.readSchema("entity placeholder", 1);
            placeholder.readBoolean();
            placeholder.readUuid();
            placeholder.readString("entity type id", MAX_STRING_BYTES);
            DecodedPayload.Transform fallbackTransform = readTransform(placeholder);
            try {
                String reason = placeholder.readString("entity placeholder reason", MAX_STRING_BYTES);
                placeholder.requireEnd("legacy entity placeholder");
                return new DecodedPayload.EntityState(
                        spawn,
                        uuid,
                        typeId,
                        fallbackTransform,
                        Optional.empty(),
                        Optional.of(reason)
                );
            } catch (IOException placeholderFailure) {
                normalFailure.addSuppressed(placeholderFailure);
                throw normalFailure;
            }
        }
    }

    private static DecodedPayload decodeEntityRemove(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("entity remove", 1);
        DecodedPayload.EntityRemove remove = new DecodedPayload.EntityRemove(input.readUuid());
        input.requireEnd("entity remove");
        return remove;
    }

    private static DecodedPayload decodeEntityEffect(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("entity effect", 1);
        DecodedPayload.EntityEffect effect = new DecodedPayload.EntityEffect(
                input.readUuid(),
                input.readString("entity effect type", 128)
        );
        input.requireEnd("entity effect");
        return effect;
    }

    private static DecodedPayload decodePlayerState(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        int schema = input.readSchema("player state", 1, 2);
        UUID uuid = input.readUuid();
        String name = input.readString("player name", MAX_STRING_BYTES);
        DecodedPayload.Transform transform = readTransform(input);
        double eyeX = input.readDouble();
        double eyeY = input.readDouble();
        double eyeZ = input.readDouble();
        float headYaw = input.readFloat();
        float bodyYaw = input.readFloat();
        float health = input.readFloat();
        float absorption = input.readFloat();
        int foodLevel = input.readInt();
        int selectedSlot = input.readInt();
        String gameMode = input.readString("game mode", MAX_STRING_BYTES);
        List<DecodedPayload.EquipmentEntry> equipment = readEquipment(input);
        Optional<DecodedPayload.PlayerAnimation> animation = schema >= 2
                ? Optional.of(readPlayerAnimation(input))
                : Optional.empty();
        DecodedPayload.PlayerState player = new DecodedPayload.PlayerState(
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
                animation
        );
        input.requireEnd("player state");
        return player;
    }

    private static DecodedPayload.PlayerAnimation readPlayerAnimation(PayloadReader input) throws IOException {
        return new DecodedPayload.PlayerAnimation(
                input.readFloat(),
                input.readFloat(),
                input.readFloat(),
                input.readBoolean(),
                input.readInt(),
                input.readString("swinging hand", 32),
                input.readBoolean(),
                input.readString("used item hand", 32),
                input.readInt(),
                input.readFloat(),
                input.readInt()
        );
    }

    private static DecodedPayload decodeCameraSample(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        int schema = input.readSchema("client camera sample", 1, 2);
        UUID playerId = input.readUuid();
        long clientNanos = input.readLong();
        double x = input.readDouble();
        double y = input.readDouble();
        double z = input.readDouble();
        float yaw = input.readFloat();
        float pitch = input.readFloat();
        float roll = input.readFloat();
        float fov = input.readFloat();
        Optional<DecodedPayload.ClientPlayerSample> playerSample = schema >= 2
                ? Optional.of(readClientPlayerSample(input))
                : Optional.empty();
        DecodedPayload.CameraSample sample = new DecodedPayload.CameraSample(
                playerId,
                clientNanos,
                x,
                y,
                z,
                yaw,
                pitch,
                roll,
                fov,
                playerSample
        );
        input.requireEnd("client camera sample");
        if (!Double.isFinite(sample.x()) || !Double.isFinite(sample.y()) || !Double.isFinite(sample.z())
                || !Float.isFinite(sample.yaw()) || !Float.isFinite(sample.pitch())
                || !Float.isFinite(sample.roll()) || !Float.isFinite(sample.fov())
                || sample.fov() <= 0.0F || sample.fov() >= 180.0F) {
            throw new IOException("Invalid client camera sample");
        }
        return sample;
    }

    private static DecodedPayload decodePlayerVisualSample(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("client player visual sample", 1);
        DecodedPayload.PlayerVisualSample sample = new DecodedPayload.PlayerVisualSample(
                input.readUuid(),
                input.readLong(),
                readClientPlayerSample(input)
        );
        input.requireEnd("client player visual sample");
        return sample;
    }

    private static DecodedPayload.ClientPlayerSample readClientPlayerSample(PayloadReader input) throws IOException {
        return new DecodedPayload.ClientPlayerSample(
                readTransform(input),
                input.readFloat(),
                input.readFloat(),
                readPlayerAnimation(input)
        );
    }

    private static DecodedPayload decodeChat(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("chat delivery", 1);
        String kind = input.readString("chat kind", MAX_STRING_BYTES);
        String json = input.readString("chat JSON", MAX_STRING_BYTES);
        int recipientCount = input.readCount("chat recipient", MAX_RECIPIENTS);
        ArrayList<UUID> recipients = new ArrayList<>(recipientCount);
        for (int index = 0; index < recipientCount; index++) {
            recipients.add(input.readUuid());
        }
        input.requireEnd("chat delivery");
        return new DecodedPayload.ChatDelivery(kind, json, recipients);
    }

    private static DecodedPayload decodeSound(byte[] payload) throws IOException {
        PayloadReader input = new PayloadReader(payload);
        input.readSchema("game sound", 1);
        DecodedPayload.GameSound sound = new DecodedPayload.GameSound(
                input.readString("sound id", MAX_STRING_BYTES),
                input.readString("sound source", MAX_STRING_BYTES),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readFloat(),
                input.readFloat()
        );
        input.requireEnd("game sound");
        return sound;
    }

    private static DecodedPayload.Transform readTransform(PayloadReader input) throws IOException {
        return new DecodedPayload.Transform(
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readFloat(),
                input.readFloat(),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readString("entity pose", MAX_STRING_BYTES),
                input.readBoolean()
        );
    }

    private static DecodedPayload.EntityDetails readEntityDetails(PayloadReader input, int schema) throws IOException {
        float headYaw = input.readFloat();
        float width = input.readFloat();
        float height = input.readFloat();
        boolean invisible = input.readBoolean();
        boolean glowing = input.readBoolean();
        boolean noGravity = input.readBoolean();
        boolean onFire = schema >= 3 && input.readBoolean();
        int remainingFireTicks = schema >= 3 ? input.readInt() : 0;
        Optional<String> customName = input.readBoolean()
                ? Optional.of(input.readString("entity custom name", MAX_STRING_BYTES))
                : Optional.empty();
        Optional<DecodedPayload.LivingDetails> living;
        if (input.readBoolean()) {
            float health = input.readFloat();
            float livingHeadYaw = input.readFloat();
            float bodyYaw = input.readFloat();
            List<DecodedPayload.EquipmentEntry> equipment = readEquipment(input);
            Optional<DecodedPayload.LivingAnimation> animation = schema >= 3
                    ? Optional.of(new DecodedPayload.LivingAnimation(
                            input.readInt(),
                            input.readInt(),
                            input.readFloat(),
                            input.readFloat(),
                            input.readFloat(),
                            input.readBoolean(),
                            input.readInt()
                    ))
                    : Optional.empty();
            living = Optional.of(new DecodedPayload.LivingDetails(
                    health,
                    livingHeadYaw,
                    bodyYaw,
                    equipment,
                    animation
            ));
        } else {
            living = Optional.empty();
        }
        return new DecodedPayload.EntityDetails(
                headYaw,
                width,
                height,
                invisible,
                glowing,
                noGravity,
                onFire,
                remainingFireTicks,
                customName,
                living
        );
    }

    private static List<DecodedPayload.EquipmentEntry> readEquipment(PayloadReader input) throws IOException {
        int count = input.readCount("equipment", MAX_EQUIPMENT);
        ArrayList<DecodedPayload.EquipmentEntry> equipment = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String slot = input.readString("equipment slot", MAX_STRING_BYTES);
            String itemId = input.readString("item id", MAX_STRING_BYTES);
            int itemCount = input.readInt();
            int damage = input.readInt();
            boolean foil = input.readBoolean();
            Optional<String> customName = input.readBoolean()
                    ? Optional.of(input.readString("item custom name", MAX_STRING_BYTES))
                    : Optional.empty();
            equipment.add(new DecodedPayload.EquipmentEntry(
                    slot,
                    new DecodedPayload.ItemStack(itemId, itemCount, damage, foil, customName)
            ));
        }
        return List.copyOf(equipment);
    }

    private static DecodedPayload.BlockState readBlockState(PayloadReader input) throws IOException {
        String blockId = input.readString("block id", MAX_STRING_BYTES);
        int propertyCount = input.readCount("block property", MAX_PROPERTIES);
        LinkedHashMap<String, String> properties = new LinkedHashMap<>(propertyCount);
        for (int index = 0; index < propertyCount; index++) {
            String name = input.readString("block property name", MAX_STRING_BYTES);
            String value = input.readString("block property value", MAX_STRING_BYTES);
            if (properties.putIfAbsent(name, value) != null) {
                throw new IOException("Duplicate block property " + name);
            }
        }
        return new DecodedPayload.BlockState(blockId, properties);
    }

    private static DecodedPayload.PackedStorage readPackedStorage(PayloadReader input, String field) throws IOException {
        int count = input.readCount(field, MAX_PACKED_LONGS);
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            values[index] = input.readLong();
        }
        return new DecodedPayload.PackedStorage(values);
    }

    private static DecodedPayload.LightData readLight(PayloadReader input, String field) throws IOException {
        if (!input.readBoolean()) {
            return new DecodedPayload.LightData(false, new byte[0]);
        }
        return new DecodedPayload.LightData(true, input.readBytes(field, MAX_LIGHT_BYTES));
    }

    private static DecodedPayload.BlockEntityState readBlockEntity(PayloadReader input, boolean enveloped)
            throws IOException {
        long position = input.readLong();
        String typeId = input.readString("block entity type id", MAX_STRING_BYTES);
        if (!enveloped) {
            return new DecodedPayload.BlockEntityState(
                    position,
                    typeId,
                    true,
                    input.readBytes("block entity NBT", MAX_NBT_BYTES),
                    ""
            );
        }
        boolean available = input.readBoolean();
        return available
                ? new DecodedPayload.BlockEntityState(
                        position,
                        typeId,
                        true,
                        input.readBytes("block entity NBT", MAX_NBT_BYTES),
                        ""
                )
                : new DecodedPayload.BlockEntityState(
                        position,
                        typeId,
                        false,
                        new byte[0],
                        input.readString("block entity placeholder reason", MAX_STRING_BYTES)
                );
    }

    private static List<DecodedPayload.BlockEntityState> readLegacyOrEnvelopedBlockEntities(
            byte[] payload,
            int count
    ) throws IOException {
        try {
            PayloadReader legacy = new PayloadReader(payload);
            List<DecodedPayload.BlockEntityState> decoded = readBlockEntities(legacy, count, false);
            legacy.requireEnd("legacy chunk block entities");
            return decoded;
        } catch (IOException legacyFailure) {
            PayloadReader enveloped = new PayloadReader(payload);
            try {
                List<DecodedPayload.BlockEntityState> decoded = readBlockEntities(enveloped, count, true);
                enveloped.requireEnd("enveloped chunk block entities");
                return decoded;
            } catch (IOException envelopedFailure) {
                legacyFailure.addSuppressed(envelopedFailure);
                throw legacyFailure;
            }
        }
    }

    private static List<DecodedPayload.BlockEntityState> readBlockEntities(
            PayloadReader input,
            int count,
            boolean enveloped
    ) throws IOException {
        ArrayList<DecodedPayload.BlockEntityState> blockEntities = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            blockEntities.add(readBlockEntity(input, enveloped));
        }
        return List.copyOf(blockEntities);
    }
}
