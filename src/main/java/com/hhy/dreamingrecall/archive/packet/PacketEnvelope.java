package com.hhy.dreamingrecall.archive.packet;

import com.hhy.dreamingrecall.archive.track.TrackNames;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PacketEnvelope {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final String trackId;
    private final ProtocolPhase phase;
    private final String packetTypeId;
    private final String namespace;
    private final PacketScope scope;
    private final String dimensionId;
    private final UUID subjectId;
    private final ChunkPosition chunk;
    private final String registryFingerprint;
    private final byte[] packetBytes;

    public PacketEnvelope(
            int schemaVersion,
            String trackId,
            ProtocolPhase phase,
            String packetTypeId,
            String namespace,
            PacketScope scope,
            String dimensionId,
            UUID subjectId,
            ChunkPosition chunk,
            String registryFingerprint,
            byte[] packetBytes
    ) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported packet envelope schema " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.trackId = TrackNames.requireTrackId(trackId);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.packetTypeId = requireIdentifier(packetTypeId, "packetTypeId");
        this.namespace = namespace.isEmpty() ? "minecraft" : TrackNames.requireNamespace(namespace);
        this.scope = Objects.requireNonNull(scope, "scope");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.subjectId = subjectId;
        this.chunk = chunk;
        this.registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
        this.packetBytes = Objects.requireNonNull(packetBytes, "packetBytes").clone();
        if (scope == PacketScope.CHUNK && chunk == null) {
            throw new IllegalArgumentException("Chunk-scoped packets require chunk coordinates");
        }
        if (scope == PacketScope.PLAYER_PRIVATE && subjectId == null) {
            throw new IllegalArgumentException("Private player packets require a subject");
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String trackId() {
        return trackId;
    }

    public ProtocolPhase phase() {
        return phase;
    }

    public String packetTypeId() {
        return packetTypeId;
    }

    public String namespace() {
        return namespace;
    }

    public PacketScope scope() {
        return scope;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public Optional<UUID> subjectId() {
        return Optional.ofNullable(subjectId);
    }

    public Optional<ChunkPosition> chunk() {
        return Optional.ofNullable(chunk);
    }

    public String registryFingerprint() {
        return registryFingerprint;
    }

    public byte[] packetBytes() {
        return packetBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PacketEnvelope that)) {
            return false;
        }
        return schemaVersion == that.schemaVersion
                && trackId.equals(that.trackId)
                && phase == that.phase
                && packetTypeId.equals(that.packetTypeId)
                && namespace.equals(that.namespace)
                && scope == that.scope
                && dimensionId.equals(that.dimensionId)
                && Objects.equals(subjectId, that.subjectId)
                && Objects.equals(chunk, that.chunk)
                && registryFingerprint.equals(that.registryFingerprint)
                && Arrays.equals(packetBytes, that.packetBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(
                schemaVersion,
                trackId,
                phase,
                packetTypeId,
                namespace,
                scope,
                dimensionId,
                subjectId,
                chunk,
                registryFingerprint
        ) + Arrays.hashCode(packetBytes);
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.contains(":")) {
            throw new IllegalArgumentException(label + " must be a namespaced identifier");
        }
        return value;
    }

    public record ChunkPosition(int x, int z) {
    }
}
