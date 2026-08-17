package com.hhy.dreamingrecall.archive.track;

import com.hhy.dreamingrecall.archive.ArchiveFormat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TrackNames {
    public static final String CONFIGURATION = "configuration/session";
    public static final String SHARED_WORLD = "shared/world";
    public static final String CONFIGURATION_PREFIX = "configuration/";
    public static final String SHARED_WORLD_PREFIX = "shared/";
    public static final String PLAYER_SERVER_PREFIX = "player/server/";
    public static final String PLAYER_CLIENT_PREFIX = "player/client/";
    public static final String VISIBILITY_PREFIX = "visibility/";
    public static final String EXTENSION_PREFIX = "extension/";
    public static final String PORTABLE_PREFIX = "portable/";

    private TrackNames() {
    }

    public static String playerServer(UUID playerId) {
        return PLAYER_SERVER_PREFIX + Objects.requireNonNull(playerId, "playerId");
    }

    public static String playerClient(UUID playerId) {
        return PLAYER_CLIENT_PREFIX + Objects.requireNonNull(playerId, "playerId");
    }

    public static Optional<UUID> playerClientId(String trackId) {
        Objects.requireNonNull(trackId, "trackId");
        if (!trackId.startsWith(PLAYER_CLIENT_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(trackId.substring(PLAYER_CLIENT_PREFIX.length())));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static String visibility(UUID playerId) {
        return VISIBILITY_PREFIX + Objects.requireNonNull(playerId, "playerId");
    }

    public static String extension(String namespace) {
        String normalized = requireNamespace(namespace);
        return EXTENSION_PREFIX + normalized;
    }

    public static String requireTrackId(String value) {
        Objects.requireNonNull(value, "value");
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (value.isBlank() || bytes > ArchiveFormat.MAX_TRACK_ID_BYTES || value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid replay track id");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '_' || character == '-' || character == '/';
            if (!accepted) {
                throw new IllegalArgumentException("Invalid replay track id character: " + character);
            }
        }
        return value;
    }

    public static String requirePrefix(String value) {
        String prefix = requireTrackId(value);
        if (!prefix.endsWith("/")) {
            throw new IllegalArgumentException("Track family prefix must end in '/'");
        }
        return prefix;
    }

    public static String requireNamespace(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > ArchiveFormat.MAX_NAMESPACE_BYTES) {
            throw new IllegalArgumentException("Invalid namespace");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '_' || character == '-';
            if (!accepted) {
                throw new IllegalArgumentException("Invalid namespace character: " + character);
            }
        }
        return value;
    }
}
