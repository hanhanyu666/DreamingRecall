package com.hhy.dreamingrecall.archive.track;

import java.util.Objects;

public record TrackDescriptor(
        String prefix,
        TrackKind kind,
        boolean requiredForCorePlayback,
        boolean portable
) {
    public TrackDescriptor {
        prefix = TrackNames.requirePrefix(prefix);
        Objects.requireNonNull(kind, "kind");
    }
}
