package com.hhy.dreamingrecall.client.playback;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ReplayChunkBuilder {
    private static final int BLOCKS_PER_SECTION = 4096;
    private static final int BIOMES_PER_SECTION = 64;

    private ReplayChunkBuilder() {
    }

    static ChunkBuildResult install(
            ClientLevel level,
            ReplayWorldSnapshot.ChunkKey key,
            ReplayWorldSnapshot.ChunkSnapshot snapshot
    ) {
        DecodedPayload.ChunkBaseline baseline = snapshot.baseline().orElse(null);
        Map<Integer, DecodedPayload.ChunkSection> recordedSections = new HashMap<>();
        if (baseline != null && baseline.available()) {
            baseline.sections().forEach(section -> recordedSections.put(section.sectionY(), section));
        }

        int degradedBlocks = 0;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int index = 0; index < level.getSectionsCount(); index++) {
                int sectionY = level.getSectionYFromSectionIndex(index);
                DecodedPayload.ChunkSection recorded = recordedSections.get(sectionY);
                SectionResult built = recorded == null
                        ? emptySection(level)
                        : buildSection(level, recorded);
                degradedBlocks += built.degradedBlocks();
                built.section().write(buffer);
            }

            LevelChunk chunk = level.getChunkSource().replaceWithPacketData(
                    key.x(),
                    key.z(),
                    buffer,
                    new CompoundTag(),
                    consumer -> {
                    }
            );
            if (chunk == null) {
                return new ChunkBuildResult(false, degradedBlocks, 0);
            }

            if (baseline != null && !baseline.available()) {
                installUnavailableMarker(level, key);
            }
            snapshot.blockOverrides().forEach((packed, state) -> level.setBlock(
                    net.minecraft.core.BlockPos.of(packed),
                    ReplayBlockStateResolver.resolve(state).state(),
                    19
            ));

            int blockEntityFailures = installBlockEntities(level, chunk, snapshot.blockEntities().values());
            installLight(level, key, baseline, snapshot.lightOverrides());
            return new ChunkBuildResult(true, degradedBlocks, blockEntityFailures);
        } catch (RuntimeException failure) {
            return new ChunkBuildResult(false, degradedBlocks, 1);
        } finally {
            buffer.release();
        }
    }

    private static SectionResult emptySection(ClientLevel level) {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES
        );
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES
        );
        return new SectionResult(new LevelChunkSection(states, biomes), 0);
    }

    private static SectionResult buildSection(ClientLevel level, DecodedPayload.ChunkSection recorded) {
        List<ReplayBlockStateResolver.ResolvedBlockState> palette = recorded.blockPalette().stream()
                .map(ReplayBlockStateResolver::resolve)
                .toList();
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES
        );
        int degradedBlocks = 0;
        if (!palette.isEmpty()) {
            int[] values = unpack(recorded.blockStorage().values(), BLOCKS_PER_SECTION, palette.size(), true);
            for (int index = 0; index < values.length; index++) {
                int paletteIndex = values[index];
                ReplayBlockStateResolver.ResolvedBlockState resolved = paletteIndex >= 0 && paletteIndex < palette.size()
                        ? palette.get(paletteIndex)
                        : new ReplayBlockStateResolver.ResolvedBlockState(
                                Blocks.MAGENTA_CONCRETE.defaultBlockState(),
                                true,
                                "invalid-palette-index"
                        );
                if (resolved.degraded()) {
                    degradedBlocks++;
                }
                if (!resolved.state().isAir()) {
                    int x = index & 15;
                    int z = index >> 4 & 15;
                    int y = index >> 8 & 15;
                    states.getAndSetUnchecked(x, y, z, resolved.state());
                }
            }
        }

        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder.Reference<Biome> plains = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        List<Holder<Biome>> biomePalette = recorded.biomePalette().stream().map(id -> {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                return plains;
            }
            Holder<Biome> found = biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, location)).orElse(null);
            return found == null ? plains : found;
        }).toList();
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                biomeRegistry.asHolderIdMap(),
                plains,
                PalettedContainer.Strategy.SECTION_BIOMES
        );
        if (!biomePalette.isEmpty()) {
            int[] values = unpack(recorded.biomeStorage().values(), BIOMES_PER_SECTION, biomePalette.size(), false);
            for (int index = 0; index < values.length; index++) {
                int paletteIndex = values[index];
                Holder<Biome> biome = paletteIndex >= 0 && paletteIndex < biomePalette.size()
                        ? biomePalette.get(paletteIndex)
                        : plains;
                int x = index & 3;
                int z = index >> 2 & 3;
                int y = index >> 4 & 3;
                biomes.getAndSetUnchecked(x, y, z, biome);
            }
        }
        return new SectionResult(new LevelChunkSection(states, biomes), degradedBlocks);
    }

    private static int[] unpack(long[] raw, int size, int paletteSize, boolean blocks) {
        int[] values = new int[size];
        if (paletteSize <= 1) {
            if (raw.length != 0) {
                throw new IllegalArgumentException("Single-value palette has packed data");
            }
            return values;
        }
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (blocks) {
            bits = Math.max(4, bits);
        }
        new SimpleBitStorage(bits, size, raw).unpack(values);
        return values;
    }

    private static int installBlockEntities(
            ClientLevel level,
            LevelChunk chunk,
            Iterable<DecodedPayload.BlockEntityState> blockEntities
    ) {
        int failures = 0;
        for (DecodedPayload.BlockEntityState state : blockEntities) {
            if (!state.available()) {
                failures++;
                installBlockEntityPlaceholder(level, state.packedPosition());
                continue;
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(state.nbt()))) {
                CompoundTag tag = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                chunk.setBlockEntityNbt(tag);
                if (chunk.getBlockEntity(
                        net.minecraft.core.BlockPos.of(state.packedPosition()),
                        LevelChunk.EntityCreationType.IMMEDIATE
                ) == null) {
                    throw new IllegalStateException("Local block entity type is unavailable");
                }
            } catch (Exception | LinkageError failure) {
                failures++;
                installBlockEntityPlaceholder(level, state.packedPosition());
            }
        }
        return failures;
    }

    private static void installBlockEntityPlaceholder(ClientLevel level, long packedPosition) {
        level.setBlock(
                net.minecraft.core.BlockPos.of(packedPosition),
                Blocks.MAGENTA_GLAZED_TERRACOTTA.defaultBlockState(),
                19
        );
    }

    private static void installLight(
            ClientLevel level,
            ReplayWorldSnapshot.ChunkKey key,
            DecodedPayload.ChunkBaseline baseline,
            Map<Integer, DecodedPayload.SectionLight> overrides
    ) {
        Map<Integer, DecodedPayload.SectionLight> sections = new HashMap<>();
        if (baseline != null && baseline.available()) {
            baseline.sections().forEach(section -> sections.put(
                    section.sectionY(),
                    new DecodedPayload.SectionLight(section.sectionY(), section.skyLight(), section.blockLight())
            ));
        }
        sections.putAll(overrides);

        LevelLightEngine engine = level.getChunkSource().getLightEngine();
        ChunkPos chunkPos = new ChunkPos(key.x(), key.z());
        engine.setLightEnabled(chunkPos, true);
        sections.forEach((sectionY, light) -> {
            SectionPos sectionPos = SectionPos.of(key.x(), sectionY, key.z());
            queueLight(engine, LightLayer.SKY, sectionPos, light.sky());
            queueLight(engine, LightLayer.BLOCK, sectionPos, light.block());
            level.setSectionDirtyWithNeighbors(key.x(), sectionY, key.z());
        });
    }

    private static void queueLight(
            LevelLightEngine engine,
            LightLayer layer,
            SectionPos sectionPos,
            DecodedPayload.LightData light
    ) {
        if (light.present() && light.values().length == 2048) {
            engine.queueSectionData(layer, sectionPos, new DataLayer(light.values()));
        }
    }

    private static void installUnavailableMarker(ClientLevel level, ReplayWorldSnapshot.ChunkKey key) {
        int y = Math.max(level.getMinBuildHeight() + 1, Math.min(64, level.getMaxBuildHeight() - 2));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockState state = ((x + z) & 1) == 0
                        ? Blocks.MAGENTA_CONCRETE.defaultBlockState()
                        : Blocks.BLACK_CONCRETE.defaultBlockState();
                level.setBlock(new net.minecraft.core.BlockPos(key.x() * 16 + x, y, key.z() * 16 + z), state, 19);
            }
        }
    }

    record ChunkBuildResult(boolean installed, int degradedBlocks, int blockEntityFailures) {
    }

    private record SectionResult(LevelChunkSection section, int degradedBlocks) {
    }
}
