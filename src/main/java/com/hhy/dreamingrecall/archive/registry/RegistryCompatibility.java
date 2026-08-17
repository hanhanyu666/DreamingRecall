package com.hhy.dreamingrecall.archive.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class RegistryCompatibility {
    private RegistryCompatibility() {
    }

    public static Report compare(RegistryManifest recorded, RegistryManifest local) {
        if (!recorded.minecraftVersion().equals(local.minecraftVersion())) {
            return new Report(Status.INCOMPATIBLE_MINECRAFT, List.of(), List.of(), List.of());
        }
        if (recorded.fingerprint().equals(local.fingerprint())) {
            return new Report(Status.EXACT, List.of(), List.of(), List.of());
        }

        Map<String, String> localMods = new HashMap<>();
        local.mods().forEach(mod -> localMods.put(mod.id(), mod.version()));
        ArrayList<String> missingMods = new ArrayList<>();
        ArrayList<String> changedMods = new ArrayList<>();
        for (ModIdentity mod : recorded.mods()) {
            String version = localMods.get(mod.id());
            if (version == null) {
                missingMods.add(mod.id());
            } else if (!version.equals(mod.version())) {
                changedMods.add(mod.id());
            }
        }

        ArrayList<String> missingEntries = new ArrayList<>();
        recorded.registries().forEach((registry, entries) -> {
            HashSet<String> available = new HashSet<>(local.registries().getOrDefault(registry, List.of()));
            for (String entry : entries) {
                if (!available.contains(entry)) {
                    missingEntries.add(registry + "/" + entry);
                }
            }
        });
        Status status = missingEntries.isEmpty() && missingMods.isEmpty()
                ? Status.COMPATIBLE_WITH_DIFFERENCES
                : Status.DEGRADED;
        return new Report(status, missingMods, changedMods, missingEntries);
    }

    public enum Status {
        EXACT,
        COMPATIBLE_WITH_DIFFERENCES,
        DEGRADED,
        INCOMPATIBLE_MINECRAFT
    }

    public record Report(
            Status status,
            List<String> missingMods,
            List<String> changedMods,
            List<String> missingRegistryEntries
    ) {
        public Report {
            missingMods = List.copyOf(missingMods);
            changedMods = List.copyOf(changedMods);
            missingRegistryEntries = List.copyOf(missingRegistryEntries);
        }
    }
}
