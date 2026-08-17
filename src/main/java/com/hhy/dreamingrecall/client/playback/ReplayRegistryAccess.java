package com.hhy.dreamingrecall.client.playback;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class ReplayRegistryAccess {
    private static volatile RegistryAccess.Frozen cached;

    private ReplayRegistryAccess() {
    }

    static RegistryAccess.Frozen get() {
        RegistryAccess.Frozen current = cached;
        if (current != null) {
            return current;
        }
        synchronized (ReplayRegistryAccess.class) {
            if (cached == null) {
                cached = build();
            }
            return cached;
        }
    }

    private static RegistryAccess.Frozen build() {
        LinkedHashMap<ResourceKey<? extends Registry<?>>, Registry<?>> registries = new LinkedHashMap<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            registries.put(registry.key(), registry);
        }

        HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
        vanilla.listRegistries().forEach(key -> registries.put(key, copyRegistry(vanilla, key)));
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> frozen = Map.copyOf(registries);
        return new RegistryAccess.Frozen() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> Optional<Registry<T>> registry(ResourceKey<? extends Registry<? extends T>> key) {
                return Optional.ofNullable((Registry<T>) frozen.get(key));
            }

            @Override
            public Stream<RegistryAccess.RegistryEntry<?>> registries() {
                return frozen.entrySet().stream().map(ReplayRegistryAccess::entry);
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry<?> copyRegistry(
            HolderLookup.Provider provider,
            ResourceKey<? extends Registry<?>> untypedKey
    ) {
        return copyRegistryTyped(provider, (ResourceKey) untypedKey);
    }

    private static <T> Registry<T> copyRegistryTyped(
            HolderLookup.Provider provider,
            ResourceKey<? extends Registry<T>> key
    ) {
        HolderLookup.RegistryLookup<T> lookup = provider.lookupOrThrow(key);
        MappedRegistry<T> registry = new MappedRegistry<>(key, Lifecycle.stable());
        lookup.listElements().forEach(holder -> registry.register(
                holder.key(),
                holder.value(),
                RegistrationInfo.BUILT_IN
        ));
        return registry.freeze();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RegistryAccess.RegistryEntry<?> entry(
            Map.Entry<ResourceKey<? extends Registry<?>>, Registry<?>> entry
    ) {
        return new RegistryAccess.RegistryEntry((ResourceKey) entry.getKey(), entry.getValue());
    }
}
