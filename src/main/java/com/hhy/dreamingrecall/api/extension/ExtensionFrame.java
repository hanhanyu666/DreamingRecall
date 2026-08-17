package com.hhy.dreamingrecall.api.extension;

import java.util.Objects;

public final class ExtensionFrame {
    private final String extensionId;
    private final int schemaVersion;
    private final String channel;
    private final String scope;
    private final byte[] payload;

    public ExtensionFrame(String extensionId, int schemaVersion, String channel, String scope, byte[] payload) {
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.schemaVersion = schemaVersion;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        if (extensionId.isBlank() || schemaVersion < 1 || channel.isBlank()) {
            throw new IllegalArgumentException("Invalid extension frame identity");
        }
    }

    public String extensionId() {
        return extensionId;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String channel() {
        return channel;
    }

    public String scope() {
        return scope;
    }

    public byte[] payloadCopy() {
        return payload.clone();
    }

    public int payloadSize() {
        return payload.length;
    }
}
