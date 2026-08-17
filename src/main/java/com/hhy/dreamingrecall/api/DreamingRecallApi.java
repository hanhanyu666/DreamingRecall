package com.hhy.dreamingrecall.api;

import com.hhy.dreamingrecall.api.extension.ExtensionSubmissionResult;
import com.hhy.dreamingrecall.api.extension.ReplayExtension;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DreamingRecallApi {
    private static final Map<ResourceLocation, ReplayExtension> EXTENSIONS = new ConcurrentHashMap<>();

    private DreamingRecallApi() {
    }

    public static void registerExtension(ReplayExtension extension) {
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(extension.id(), "extension.id");
        if (extension.schemaVersion() < 1) {
            throw new IllegalArgumentException("Extension schemaVersion must be positive");
        }
        if (extension.maximumPayloadBytes() < 1 || extension.maximumPayloadBytes() > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Extension maximumPayloadBytes is outside the supported range");
        }
        ReplayExtension previous = EXTENSIONS.putIfAbsent(extension.id(), extension);
        if (previous != null) {
            throw new IllegalStateException("DreamingRecall extension is already registered: " + extension.id());
        }
    }

    public static Optional<ReplayExtension> extension(ResourceLocation id) {
        return Optional.ofNullable(EXTENSIONS.get(id));
    }

    public static ExtensionSubmissionResult submit(
            MinecraftServer server,
            ResourceLocation extensionId,
            String channel,
            String scope,
            byte[] payload
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(payload, "payload");
        ReplayExtension extension = EXTENSIONS.get(extensionId);
        if (extension == null) {
            return ExtensionSubmissionResult.UNKNOWN_EXTENSION;
        }
        if (channel.isBlank() || channel.length() > 256) {
            return ExtensionSubmissionResult.INVALID_CHANNEL;
        }
        if (payload.length > extension.maximumPayloadBytes()) {
            return ExtensionSubmissionResult.PAYLOAD_TOO_LARGE;
        }
        return DreamingRecallServer.INSTANCE.submitExtension(server, extension, channel, scope, payload);
    }
}
