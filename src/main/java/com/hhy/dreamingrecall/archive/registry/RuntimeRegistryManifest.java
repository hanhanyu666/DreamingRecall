package com.hhy.dreamingrecall.archive.registry;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeRegistryManifest {
    private RuntimeRegistryManifest() {
    }

    public static RegistryManifest capture(RegistryAccess registryAccess) {
        List<ModIdentity> mods = ModList.get().getMods().stream()
                .map(info -> new ModIdentity(info.getModId(), info.getVersion().toString()))
                .toList();
        String neoForgeVersion = mods.stream()
                .filter(mod -> mod.id().equals("neoforge"))
                .map(ModIdentity::version)
                .findFirst()
                .orElse("unknown");
        Map<String, List<String>> registries = new LinkedHashMap<>();
        registryAccess.registries().forEach(entry -> {
            ArrayList<String> values = new ArrayList<>();
            entry.value().keySet().forEach(id -> values.add(id.toString()));
            registries.put(entry.key().location().toString(), values);
        });
        return RegistryManifest.create(
                SharedConstants.getCurrentVersion().getName(),
                neoForgeVersion,
                mods,
                registries
        );
    }
}
