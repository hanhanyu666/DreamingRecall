package com.hhy.dreamingrecall.archive;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class ReplayRecord {
    private final int typeId;
    private final RecordPriority priority;
    private final long archiveNanos;
    private final long serverTick;
    private final String dimensionId;
    private final byte[] payload;

    public ReplayRecord(
            int typeId,
            RecordPriority priority,
            long archiveNanos,
            long serverTick,
            String dimensionId,
            byte[] payload
    ) {
        if (typeId < 0) {
            throw new IllegalArgumentException("typeId must be non-negative");
        }
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
        this.typeId = typeId;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.archiveNanos = archiveNanos;
        this.serverTick = serverTick;
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public static ReplayRecord core(
            CoreRecordType type,
            long archiveNanos,
            long serverTick,
            String dimensionId,
            byte[] payload
    ) {
        return new ReplayRecord(type.id(), RecordPriority.CORE, archiveNanos, serverTick, dimensionId, payload);
    }

    public static ReplayRecord control(
            CoreRecordType type,
            long archiveNanos,
            long serverTick,
            byte[] payload
    ) {
        return new ReplayRecord(type.id(), RecordPriority.CONTROL, archiveNanos, serverTick, "", payload);
    }

    public int typeId() {
        return typeId;
    }

    public RecordPriority priority() {
        return priority;
    }

    public long archiveNanos() {
        return archiveNanos;
    }

    public long serverTick() {
        return serverTick;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int payloadSize() {
        return payload.length;
    }

    public byte[] payloadCopy() {
        return payload.clone();
    }

    void writePayload(DataOutput output) throws IOException {
        output.write(payload);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReplayRecord that)) {
            return false;
        }
        return typeId == that.typeId
                && archiveNanos == that.archiveNanos
                && serverTick == that.serverTick
                && priority == that.priority
                && dimensionId.equals(that.dimensionId)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(typeId, priority, archiveNanos, serverTick, dimensionId);
        return 31 * result + Arrays.hashCode(payload);
    }
}
