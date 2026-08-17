package com.hhy.dreamingrecall.archive;

import java.util.Arrays;
import java.util.Optional;

public enum CoreRecordType {
    SESSION_START(1),
    SESSION_END(2),
    SERVER_TICK(3),
    BASELINE_BEGIN(10),
    BASELINE_END(11),
    RECORDING_GAP(12),
    DIMENSION_STATE(20),
    CHUNK_BASELINE(30),
    CHUNK_OBSERVATION_END(31),
    BLOCK_CHANGE(32),
    BLOCK_ENTITY_STATE(33),
    CHUNK_LIGHT(34),
    BLOCK_ENTITY_REMOVE(35),
    ENTITY_SPAWN(40),
    ENTITY_STATE(41),
    ENTITY_REMOVE(42),
    ENTITY_EFFECT(43),
    PLAYER_STATE(50),
    CLIENT_CAMERA_SAMPLE(51),
    CLIENT_PLAYER_VISUAL_SAMPLE(52),
    CHAT_DELIVERY(60),
    GAME_SOUND(70),
    EXTENSION_PAYLOAD(1000);

    private final int id;

    CoreRecordType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static Optional<CoreRecordType> fromId(int id) {
        return Arrays.stream(values()).filter(type -> type.id == id).findFirst();
    }
}
