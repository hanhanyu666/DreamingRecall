package com.hhy.dreamingrecall.api.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ExtensionFrameCodec {
    private static final int SCHEMA = 1;
    private static final int MAX_ID_BYTES = 1024;
    private static final int MAX_CHANNEL_BYTES = 1024;
    private static final int MAX_SCOPE_BYTES = 4096;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private ExtensionFrameCodec() {
    }

    public static byte[] encode(ExtensionFrame frame) throws IOException {
        if (frame.payloadSize() > MAX_PAYLOAD_BYTES) {
            throw new IOException("Extension frame exceeds the archive format limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(SCHEMA);
            writeString(output, frame.extensionId(), MAX_ID_BYTES);
            output.writeInt(frame.schemaVersion());
            writeString(output, frame.channel(), MAX_CHANNEL_BYTES);
            writeString(output, frame.scope(), MAX_SCOPE_BYTES);
            output.writeInt(frame.payloadSize());
            output.write(frame.payloadCopy());
        }
        return bytes.toByteArray();
    }

    public static ExtensionFrame decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != SCHEMA) {
                throw new IOException("Unsupported extension frame schema");
            }
            String extensionId = readString(input, MAX_ID_BYTES);
            int schemaVersion = input.readInt();
            String channel = readString(input, MAX_CHANNEL_BYTES);
            String scope = readString(input, MAX_SCOPE_BYTES);
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid extension payload length");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength || input.read() != -1) {
                throw new EOFException("Truncated or trailing extension frame bytes");
            }
            return new ExtensionFrame(extensionId, schemaVersion, channel, scope, payload);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximum) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) {
            throw new IOException("Extension frame string exceeds its format limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Invalid extension frame string length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Truncated extension frame string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
