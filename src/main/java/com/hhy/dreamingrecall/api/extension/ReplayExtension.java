package com.hhy.dreamingrecall.api.extension;

import net.minecraft.resources.ResourceLocation;

public interface ReplayExtension {
    ResourceLocation id();

    int schemaVersion();

    default int maximumPayloadBytes() {
        return 1024 * 1024;
    }

    default boolean canRead(int recordedSchemaVersion) {
        return recordedSchemaVersion == schemaVersion();
    }
}
