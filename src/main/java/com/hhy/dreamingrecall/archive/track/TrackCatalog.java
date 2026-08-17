package com.hhy.dreamingrecall.archive.track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TrackCatalog(int schemaVersion, List<TrackDescriptor> families) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TrackCatalog {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported track catalog schema " + schemaVersion);
        }
        Objects.requireNonNull(families, "families");
        ArrayList<TrackDescriptor> copy = new ArrayList<>(families);
        HashSet<String> prefixes = new HashSet<>();
        for (TrackDescriptor descriptor : copy) {
            if (!prefixes.add(descriptor.prefix())) {
                throw new IllegalArgumentException("Duplicate track prefix " + descriptor.prefix());
            }
        }
        copy.sort(Comparator.comparingInt((TrackDescriptor value) -> value.prefix().length()).reversed());
        families = List.copyOf(copy);
    }

    public Optional<TrackDescriptor> resolve(String trackId) {
        String validated = TrackNames.requireTrackId(trackId);
        return families.stream().filter(family -> validated.startsWith(family.prefix())).findFirst();
    }

    public static TrackCatalog standard() {
        return new TrackCatalog(CURRENT_SCHEMA_VERSION, List.of(
                new TrackDescriptor(TrackNames.CONFIGURATION_PREFIX, TrackKind.CONFIGURATION, true, false),
                new TrackDescriptor(TrackNames.SHARED_WORLD_PREFIX, TrackKind.SHARED_WORLD, true, false),
                new TrackDescriptor(TrackNames.PLAYER_SERVER_PREFIX, TrackKind.PLAYER_SERVER, false, false),
                new TrackDescriptor(TrackNames.PLAYER_CLIENT_PREFIX, TrackKind.PLAYER_CLIENT, false, false),
                new TrackDescriptor(TrackNames.VISIBILITY_PREFIX, TrackKind.VISIBILITY, false, true),
                new TrackDescriptor(TrackNames.EXTENSION_PREFIX, TrackKind.EXTENSION, false, false),
                new TrackDescriptor(TrackNames.PORTABLE_PREFIX, TrackKind.PORTABLE_FALLBACK, false, true)
        ));
    }
}
