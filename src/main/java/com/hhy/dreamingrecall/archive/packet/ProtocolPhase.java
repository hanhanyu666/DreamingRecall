package com.hhy.dreamingrecall.archive.packet;

public enum ProtocolPhase {
    LOGIN(0),
    CONFIGURATION(1),
    PLAY(2);

    private final int id;

    ProtocolPhase(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }

    static ProtocolPhase fromId(int id) {
        for (ProtocolPhase phase : values()) {
            if (phase.id == id) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown protocol phase " + id);
    }
}
