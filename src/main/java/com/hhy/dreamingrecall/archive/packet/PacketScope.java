package com.hhy.dreamingrecall.archive.packet;

public enum PacketScope {
    SESSION(0),
    DIMENSION(1),
    CHUNK(2),
    ENTITY(3),
    PLAYER_PRIVATE(4),
    CLIENT_LOCAL(5);

    private final int id;

    PacketScope(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }

    static PacketScope fromId(int id) {
        for (PacketScope scope : values()) {
            if (scope.id == id) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown packet scope " + id);
    }
}
