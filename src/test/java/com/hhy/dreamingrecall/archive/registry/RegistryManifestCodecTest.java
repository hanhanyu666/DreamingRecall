package com.hhy.dreamingrecall.archive.registry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryManifestCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fingerprintIsDeterministicAndManifestRoundTrips() throws Exception {
        RegistryManifest first = RegistryManifest.create(
                "1.21.1",
                "21.1.228",
                List.of(new ModIdentity("example", "2"), new ModIdentity("minecraft", "1.21.1")),
                Map.of(
                        "minecraft:entity_type", List.of("example:thing", "minecraft:zombie"),
                        "minecraft:block", List.of("minecraft:stone")
                )
        );
        RegistryManifest reordered = RegistryManifest.create(
                "1.21.1",
                "21.1.228",
                List.of(new ModIdentity("minecraft", "1.21.1"), new ModIdentity("example", "2")),
                Map.of(
                        "minecraft:block", List.of("minecraft:stone"),
                        "minecraft:entity_type", List.of("minecraft:zombie", "example:thing")
                )
        );
        assertEquals(first.fingerprint(), reordered.fingerprint());

        RegistryManifestCodec.write(temporaryDirectory, first);
        assertEquals(first, RegistryManifestCodec.read(temporaryDirectory));
    }

    @Test
    void detectsTamperingAndReportsDegradedCompatibility() throws Exception {
        RegistryManifest recorded = RegistryManifest.create(
                "1.21.1",
                "21.1.228",
                List.of(new ModIdentity("example", "2")),
                Map.of("minecraft:entity_type", List.of("example:thing", "minecraft:zombie"))
        );
        RegistryManifest local = RegistryManifest.create(
                "1.21.1",
                "21.1.228",
                List.of(),
                Map.of("minecraft:entity_type", List.of("minecraft:zombie"))
        );
        RegistryCompatibility.Report report = RegistryCompatibility.compare(recorded, local);
        assertEquals(RegistryCompatibility.Status.DEGRADED, report.status());
        assertEquals(List.of("example"), report.missingMods());
        assertEquals(List.of("minecraft:entity_type/example:thing"), report.missingRegistryEntries());

        RegistryManifestCodec.write(temporaryDirectory, recorded);
        Path file = temporaryDirectory.resolve(RegistryManifestCodec.FILE_NAME);
        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("minecraftVersion", "1.21.2");
        Files.writeString(file, json.toString());
        assertThrows(java.io.IOException.class, () -> RegistryManifestCodec.read(temporaryDirectory));
    }
}
