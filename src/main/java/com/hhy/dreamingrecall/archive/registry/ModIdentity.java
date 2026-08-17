package com.hhy.dreamingrecall.archive.registry;

import com.hhy.dreamingrecall.archive.track.TrackNames;

import java.util.Objects;

public record ModIdentity(String id, String version) {
    public ModIdentity {
        id = TrackNames.requireNamespace(id);
        Objects.requireNonNull(version, "version");
        if (version.isBlank() || version.length() > 1024) {
            throw new IllegalArgumentException("Invalid mod version");
        }
    }
}
