package com.hhy.dreamingrecall.archive.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record RegistryManifest(
        int schemaVersion,
        String minecraftVersion,
        String neoForgeVersion,
        List<ModIdentity> mods,
        Map<String, List<String>> registries,
        String fingerprint
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RegistryManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported registry manifest schema " + schemaVersion);
        }
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(neoForgeVersion, "neoForgeVersion");
        Objects.requireNonNull(mods, "mods");
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(fingerprint, "fingerprint");
        mods = mods.stream().sorted(java.util.Comparator.comparing(ModIdentity::id)).toList();
        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        new TreeMap<>(registries).forEach((registry, entries) -> {
            if (registry == null || registry.isBlank() || entries == null) {
                throw new IllegalArgumentException("Invalid registry manifest entry");
            }
            ArrayList<String> values = new ArrayList<>(entries);
            values.sort(String::compareTo);
            normalized.put(registry, List.copyOf(values));
        });
        registries = Map.copyOf(normalized);
        String calculated = calculateFingerprint(minecraftVersion, neoForgeVersion, mods, registries);
        if (!fingerprint.equals(calculated)) {
            throw new IllegalArgumentException("Registry manifest fingerprint does not match its contents");
        }
    }

    public static RegistryManifest create(
            String minecraftVersion,
            String neoForgeVersion,
            List<ModIdentity> mods,
            Map<String, List<String>> registries
    ) {
        List<ModIdentity> sortedMods = mods.stream().sorted(java.util.Comparator.comparing(ModIdentity::id)).toList();
        TreeMap<String, List<String>> sortedRegistries = new TreeMap<>();
        registries.forEach((key, values) -> sortedRegistries.put(key, values.stream().sorted().toList()));
        String fingerprint = calculateFingerprint(minecraftVersion, neoForgeVersion, sortedMods, sortedRegistries);
        return new RegistryManifest(
                CURRENT_SCHEMA_VERSION,
                minecraftVersion,
                neoForgeVersion,
                sortedMods,
                sortedRegistries,
                fingerprint
        );
    }

    private static String calculateFingerprint(
            String minecraftVersion,
            String neoForgeVersion,
            List<ModIdentity> mods,
            Map<String, List<String>> registries
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, minecraftVersion);
            update(digest, neoForgeVersion);
            mods.stream().sorted(java.util.Comparator.comparing(ModIdentity::id)).forEach(mod -> {
                update(digest, mod.id());
                update(digest, mod.version());
            });
            new TreeMap<>(registries).forEach((registry, entries) -> {
                update(digest, registry);
                entries.stream().sorted().forEach(value -> update(digest, value));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
