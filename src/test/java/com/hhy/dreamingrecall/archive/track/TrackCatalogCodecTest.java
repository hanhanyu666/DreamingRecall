package com.hhy.dreamingrecall.archive.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackCatalogCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsStandardCatalogAndResolvesMostSpecificFamily() throws Exception {
        TrackCatalog expected = TrackCatalog.standard();
        TrackCatalogCodec.write(temporaryDirectory, expected);

        TrackCatalog decoded = TrackCatalogCodec.read(temporaryDirectory);

        assertEquals(expected, decoded);
        assertEquals(
                TrackKind.PLAYER_CLIENT,
                decoded.resolve("player/client/00000000-0000-0000-0000-000000000001").orElseThrow().kind()
        );
        assertTrue(decoded.resolve("unknown/track").isEmpty());
    }

    @Test
    void rejectsUnsafeTrackNamesAndDuplicateFamilies() {
        assertThrows(IllegalArgumentException.class, () -> TrackNames.requireTrackId("../outside"));
        assertThrows(IllegalArgumentException.class, () -> TrackNames.requireTrackId("Player/Uppercase"));
        TrackDescriptor descriptor = new TrackDescriptor("shared/", TrackKind.SHARED_WORLD, true, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrackCatalog(TrackCatalog.CURRENT_SCHEMA_VERSION, java.util.List.of(descriptor, descriptor))
        );
    }
}
