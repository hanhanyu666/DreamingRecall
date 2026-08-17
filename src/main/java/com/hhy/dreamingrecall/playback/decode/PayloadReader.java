package com.hhy.dreamingrecall.playback.decode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class PayloadReader {
    private final ByteArrayInputStream bytes;
    private final DataInputStream input;

    PayloadReader(byte[] payload) {
        this.bytes = new ByteArrayInputStream(payload);
        this.input = new DataInputStream(bytes);
    }

    int readSchema(String payloadName, int... supported) throws IOException {
        int schema = readInt();
        for (int candidate : supported) {
            if (schema == candidate) {
                return schema;
            }
        }
        throw new IOException("Unsupported " + payloadName + " schema " + schema);
    }

    boolean readBoolean() throws IOException {
        return input.readBoolean();
    }

    int readInt() throws IOException {
        return input.readInt();
    }

    long readLong() throws IOException {
        return input.readLong();
    }

    float readFloat() throws IOException {
        return input.readFloat();
    }

    double readDouble() throws IOException {
        return input.readDouble();
    }

    UUID readUuid() throws IOException {
        return new UUID(readLong(), readLong());
    }

    String readString(String field, int maximumBytes) throws IOException {
        int length = readLength(field, maximumBytes);
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException("Truncated " + field);
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    byte[] readBytes(String field, int maximumBytes) throws IOException {
        int length = readLength(field, maximumBytes);
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Truncated " + field);
        }
        return value;
    }

    int readCount(String field, int maximum) throws IOException {
        int count = readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid " + field + " count " + count);
        }
        return count;
    }

    int remaining() {
        return bytes.available();
    }

    byte[] readRemainingBytes() throws IOException {
        return input.readNBytes(remaining());
    }

    void requireEnd(String payloadName) throws IOException {
        if (remaining() != 0) {
            throw new IOException(payloadName + " has " + remaining() + " trailing bytes");
        }
    }

    private int readLength(String field, int maximumBytes) throws IOException {
        int length = readInt();
        if (length < 0 || length > maximumBytes || length > remaining()) {
            throw new IOException("Invalid " + field + " length " + length);
        }
        return length;
    }
}
