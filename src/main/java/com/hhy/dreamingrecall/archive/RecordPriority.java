package com.hhy.dreamingrecall.archive;

import java.io.IOException;

public enum RecordPriority {
    CONTROL(0),
    CORE(1),
    ENHANCEMENT(2);

    private final int id;

    RecordPriority(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RecordPriority fromId(int id) throws IOException {
        for (RecordPriority priority : values()) {
            if (priority.id == id) {
                return priority;
            }
        }
        throw new IOException("Unknown record priority " + id);
    }
}
