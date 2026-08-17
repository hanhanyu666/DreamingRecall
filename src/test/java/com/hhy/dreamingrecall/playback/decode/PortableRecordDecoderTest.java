package com.hhy.dreamingrecall.playback.decode;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableRecordDecoderTest {
    private final PortableRecordDecoder decoder = new PortableRecordDecoder();

    @Test
    void decodesChunkPaletteLightAndIsolatedBlockEntityPlaceholder() throws Exception {
        byte[] payload = encode(output -> {
            output.writeInt(2);
            output.writeBoolean(true);
            output.writeInt(12);
            output.writeInt(-4);
            output.writeInt(1);
            output.writeInt(5);
            output.writeInt(1);
            writeString(output, "example:machine");
            output.writeInt(1);
            writeString(output, "facing");
            writeString(output, "north");
            output.writeInt(1);
            output.writeLong(0x1234L);
            output.writeInt(1);
            writeString(output, "minecraft:plains");
            output.writeInt(0);
            output.writeBoolean(false);
            output.writeBoolean(true);
            output.writeInt(2);
            output.write(new byte[]{4, 7});
            output.writeInt(2);
            output.writeLong(123L);
            writeString(output, "example:working");
            output.writeBoolean(true);
            output.writeInt(3);
            output.write(new byte[]{1, 2, 3});
            output.writeLong(456L);
            writeString(output, "example:broken");
            output.writeBoolean(false);
            writeString(output, "java.lang.IllegalStateException");
        });

        DecodedPayload.ChunkBaseline chunk = (DecodedPayload.ChunkBaseline) decoder.decode(
                ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 0, 0, "example:dimension", payload)
        );

        assertTrue(chunk.available());
        assertEquals(12, chunk.chunkX());
        assertEquals(-4, chunk.chunkZ());
        assertEquals("example:machine", chunk.sections().getFirst().blockPalette().getFirst().blockId());
        assertEquals("north", chunk.sections().getFirst().blockPalette().getFirst().properties().get("facing"));
        assertFalse(chunk.sections().getFirst().skyLight().present());
        assertArrayEquals(new byte[]{4, 7}, chunk.sections().getFirst().blockLight().values());
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.blockEntities().getFirst().nbt());
        assertFalse(chunk.blockEntities().getLast().available());
        assertEquals("java.lang.IllegalStateException", chunk.blockEntities().getLast().unavailableReason());
    }

    @Test
    void versionTwoEntityPlaceholderHasAnUnambiguousDiscriminator() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(2);
            output.writeBoolean(true);
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
            writeString(output, "missing:camera_drone");
            output.writeBoolean(false);
            writeTransform(output);
            writeString(output, "missing playback codec");
        });

        DecodedPayload.EntityState entity = (DecodedPayload.EntityState) decoder.decode(
                ReplayRecord.core(CoreRecordType.ENTITY_SPAWN, 10, 1, "minecraft:overworld", payload)
        );

        assertTrue(entity.spawn());
        assertEquals(uuid, entity.uuid());
        assertTrue(entity.details().isEmpty());
        assertEquals("missing playback codec", entity.unavailableReason().orElseThrow());
    }

    @Test
    void readsTransitionalSchemaOneChunksWithEnvelopedBlockEntities() throws Exception {
        byte[] payload = encode(output -> {
            output.writeInt(1);
            output.writeBoolean(true);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(1);
            output.writeLong(123L);
            writeString(output, "example:machine");
            output.writeBoolean(true);
            output.writeInt(2);
            output.write(new byte[]{9, 4});
        });

        DecodedPayload.ChunkBaseline chunk = (DecodedPayload.ChunkBaseline) decoder.decode(
                ReplayRecord.core(CoreRecordType.CHUNK_BASELINE, 0, 0, "minecraft:overworld", payload)
        );

        assertArrayEquals(new byte[]{9, 4}, chunk.blockEntities().getFirst().nbt());
    }

    @Test
    void legacyEntityPlaceholderRemainsReadable() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(1);
            output.writeBoolean(false);
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
            writeString(output, "missing:legacy_entity");
            writeTransform(output);
            writeString(output, "legacy failure");
        });

        DecodedPayload.EntityState entity = (DecodedPayload.EntityState) decoder.decode(
                ReplayRecord.core(CoreRecordType.ENTITY_STATE, 10, 1, "minecraft:overworld", payload)
        );

        assertEquals(uuid, entity.uuid());
        assertEquals("legacy failure", entity.unavailableReason().orElseThrow());
    }

    @Test
    void rejectsOversizedLengthsBeforeAllocation() throws Exception {
        byte[] payload = encode(output -> {
            output.writeInt(1);
            output.writeInt(Integer.MAX_VALUE);
        });
        ReplayRecord record = ReplayRecord.core(
                CoreRecordType.CHAT_DELIVERY,
                0,
                0,
                "",
                payload
        );

        assertThrows(IOException.class, () -> decoder.decode(record));
    }

    @Test
    void decodesHighPrecisionClientCameraSample() throws Exception {
        UUID playerId = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(1);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeLong(1234L);
            output.writeDouble(1.25);
            output.writeDouble(65.5);
            output.writeDouble(-4.75);
            output.writeFloat(91.0F);
            output.writeFloat(-12.0F);
            output.writeFloat(3.0F);
            output.writeFloat(80.0F);
        });

        DecodedPayload.CameraSample sample = (DecodedPayload.CameraSample) decoder.decode(
                new ReplayRecord(
                        CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                        com.hhy.dreamingrecall.archive.RecordPriority.ENHANCEMENT,
                        10,
                        1,
                        "minecraft:overworld",
                        payload
                )
        );

        assertEquals(playerId, sample.playerId());
        assertEquals(65.5, sample.y());
        assertEquals(80.0F, sample.fov());
        assertTrue(sample.playerSample().isEmpty());
    }

    @Test
    void decodesVersionTwoPlayerAnimationAndClientVisualTrack() throws Exception {
        UUID playerId = UUID.randomUUID();
        byte[] playerPayload = encode(output -> {
            output.writeInt(2);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            writeString(output, "ReplayPlayer");
            writeTransform(output);
            output.writeDouble(1.0);
            output.writeDouble(3.62);
            output.writeDouble(3.0);
            output.writeFloat(92.0F);
            output.writeFloat(88.0F);
            output.writeFloat(20.0F);
            output.writeFloat(2.0F);
            output.writeInt(18);
            output.writeInt(2);
            writeString(output, "survival");
            output.writeInt(0);
            writeAnimation(output);
        });
        DecodedPayload.PlayerState player = (DecodedPayload.PlayerState) decoder.decode(
                ReplayRecord.core(CoreRecordType.PLAYER_STATE, 10, 1, "minecraft:overworld", playerPayload)
        );

        byte[] cameraPayload = encode(output -> {
            output.writeInt(2);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeLong(1234L);
            output.writeDouble(1.0);
            output.writeDouble(3.62);
            output.writeDouble(3.0);
            output.writeFloat(92.0F);
            output.writeFloat(10.0F);
            output.writeFloat(0.0F);
            output.writeFloat(80.0F);
            writeTransform(output);
            output.writeFloat(92.0F);
            output.writeFloat(88.0F);
            writeAnimation(output);
        });
        DecodedPayload.CameraSample camera = (DecodedPayload.CameraSample) decoder.decode(
                ReplayRecord.core(CoreRecordType.CLIENT_CAMERA_SAMPLE, 11, 1, "minecraft:overworld", cameraPayload)
        );

        byte[] visualPayload = encode(output -> {
            output.writeInt(1);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeLong(5678L);
            writeTransform(output);
            output.writeFloat(92.0F);
            output.writeFloat(88.0F);
            writeAnimation(output);
        });
        DecodedPayload.PlayerVisualSample visual = (DecodedPayload.PlayerVisualSample) decoder.decode(
                ReplayRecord.core(
                        CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE,
                        12,
                        1,
                        "minecraft:overworld",
                        visualPayload
                )
        );

        assertEquals(0.75F, player.animation().orElseThrow().attackProgress());
        assertTrue(player.animation().orElseThrow().usingItem());
        assertEquals("OFF_HAND", player.animation().orElseThrow().usedItemHand());
        assertEquals(12.5F, camera.playerSample().orElseThrow().animation().walkPosition());
        assertEquals(9, camera.playerSample().orElseThrow().animation().fallFlyingTicks());
        assertEquals(playerId, visual.playerId());
        assertEquals(12.5F, visual.playerSample().animation().walkPosition());
    }

    @Test
    void decodesVersionThreeLivingVisualState() throws Exception {
        UUID entityId = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(3);
            output.writeBoolean(false);
            output.writeLong(entityId.getMostSignificantBits());
            output.writeLong(entityId.getLeastSignificantBits());
            writeString(output, "minecraft:zombie");
            output.writeBoolean(true);
            writeTransform(output);
            output.writeFloat(35.0F);
            output.writeFloat(0.6F);
            output.writeFloat(1.95F);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeBoolean(true);
            output.writeInt(137);
            output.writeBoolean(false);
            output.writeBoolean(true);
            output.writeFloat(0.0F);
            output.writeFloat(36.0F);
            output.writeFloat(30.0F);
            output.writeInt(0);
            output.writeInt(7);
            output.writeInt(12);
            output.writeFloat(42.5F);
            output.writeFloat(0.45F);
            output.writeFloat(0.75F);
            output.writeBoolean(true);
            output.writeInt(4);
        });

        DecodedPayload.EntityState entity = (DecodedPayload.EntityState) decoder.decode(
                ReplayRecord.core(CoreRecordType.ENTITY_STATE, 50, 3, "minecraft:overworld", payload)
        );
        DecodedPayload.EntityDetails details = entity.details().orElseThrow();
        DecodedPayload.LivingAnimation animation = details.living()
                .orElseThrow()
                .animation()
                .orElseThrow();

        assertTrue(details.onFire());
        assertEquals(137, details.remainingFireTicks());
        assertEquals(7, animation.hurtTime());
        assertEquals(12, animation.deathTime());
        assertEquals(42.5F, animation.walkPosition());
        assertEquals(0.45F, animation.walkSpeed());
        assertTrue(animation.swinging());
    }

    @Test
    void fullVersionTwoEntityStateRemainsReadable() throws Exception {
        UUID entityId = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(2);
            output.writeBoolean(false);
            output.writeLong(entityId.getMostSignificantBits());
            output.writeLong(entityId.getLeastSignificantBits());
            writeString(output, "minecraft:zombie");
            output.writeBoolean(true);
            writeTransform(output);
            output.writeFloat(35.0F);
            output.writeFloat(0.6F);
            output.writeFloat(1.95F);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeBoolean(true);
            output.writeFloat(14.0F);
            output.writeFloat(36.0F);
            output.writeFloat(30.0F);
            output.writeInt(0);
        });

        DecodedPayload.EntityDetails details = ((DecodedPayload.EntityState) decoder.decode(
                ReplayRecord.core(CoreRecordType.ENTITY_STATE, 50, 3, "minecraft:overworld", payload)
        )).details().orElseThrow();

        assertFalse(details.onFire());
        assertEquals(0, details.remainingFireTicks());
        assertTrue(details.living().orElseThrow().animation().isEmpty());
    }

    @Test
    void decodesEntityEffect() throws Exception {
        UUID entityId = UUID.randomUUID();
        byte[] payload = encode(output -> {
            output.writeInt(1);
            output.writeLong(entityId.getMostSignificantBits());
            output.writeLong(entityId.getLeastSignificantBits());
            writeString(output, "critical_hit");
        });

        DecodedPayload.EntityEffect effect = (DecodedPayload.EntityEffect) decoder.decode(
                ReplayRecord.core(CoreRecordType.ENTITY_EFFECT, 75, 4, "minecraft:overworld", payload)
        );

        assertEquals(entityId, effect.entityId());
        assertEquals("critical_hit", effect.effect());
    }

    private static void writeAnimation(DataOutputStream output) throws IOException {
        output.writeFloat(12.5F);
        output.writeFloat(0.6F);
        output.writeFloat(0.75F);
        output.writeBoolean(true);
        output.writeInt(4);
        writeString(output, "MAIN_HAND");
        output.writeBoolean(true);
        writeString(output, "OFF_HAND");
        output.writeInt(15);
        output.writeFloat(0.4F);
        output.writeInt(9);
    }

    private static void writeTransform(DataOutputStream output) throws IOException {
        output.writeDouble(1.0);
        output.writeDouble(2.0);
        output.writeDouble(3.0);
        output.writeFloat(90.0F);
        output.writeFloat(10.0F);
        output.writeDouble(0.1);
        output.writeDouble(0.2);
        output.writeDouble(0.3);
        writeString(output, "standing");
        output.writeBoolean(true);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.write(output);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
