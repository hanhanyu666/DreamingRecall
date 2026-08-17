package com.hhy.dreamingrecall.client.playback;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;

final class ReplayBlockStateResolver {
    private ReplayBlockStateResolver() {
    }

    static ResolvedBlockState resolve(DecodedPayload.BlockState portable) {
        ResourceLocation id = ResourceLocation.tryParse(portable.blockId());
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            return new ResolvedBlockState(Blocks.MAGENTA_CONCRETE.defaultBlockState(), true, portable.blockId());
        }

        BlockState resolved = block.get().defaultBlockState();
        boolean degraded = false;
        for (Map.Entry<String, String> entry : portable.properties().entrySet()) {
            Property<?> property = block.get().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                degraded = true;
                continue;
            }
            Optional<?> value = property.getValue(entry.getValue());
            if (value.isEmpty()) {
                degraded = true;
                continue;
            }
            resolved = setValue(resolved, property, value.get());
        }
        return new ResolvedBlockState(resolved, degraded, portable.blockId());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setValue(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    record ResolvedBlockState(BlockState state, boolean degraded, String originalId) {
    }
}
