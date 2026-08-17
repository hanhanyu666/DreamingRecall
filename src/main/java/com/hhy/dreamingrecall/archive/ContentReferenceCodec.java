package com.hhy.dreamingrecall.archive;

import java.nio.ByteBuffer;
import java.util.Optional;

public final class ContentReferenceCodec {
    private static final int MAGIC = 0x44524352; // DRCR
    private static final byte VERSION_WITHOUT_FALLBACK = 1;
    private static final byte VERSION_WITH_FALLBACK = 2;
    private static final int V1_ENCODED_BYTES = Integer.BYTES + 1 + ContentReference.SHA_256_BYTES + Integer.BYTES;
    private static final int V2_HEADER_BYTES = V1_ENCODED_BYTES + Integer.BYTES;
    private static final int MAX_FALLBACK_BYTES = 1024 * 1024;

    private ContentReferenceCodec() {
    }

    public static byte[] encode(ContentReference reference) {
        return ByteBuffer.allocate(V1_ENCODED_BYTES)
                .putInt(MAGIC)
                .put(VERSION_WITHOUT_FALLBACK)
                .put(reference.sha256Copy())
                .putInt(reference.uncompressedBytes())
                .array();
    }

    public static byte[] encode(ContentReference reference, byte[] fallbackPayload) {
        if (fallbackPayload.length > MAX_FALLBACK_BYTES) {
            throw new IllegalArgumentException("Content fallback exceeds the archive format limit");
        }
        return ByteBuffer.allocate(V2_HEADER_BYTES + fallbackPayload.length)
                .putInt(MAGIC)
                .put(VERSION_WITH_FALLBACK)
                .put(reference.sha256Copy())
                .putInt(reference.uncompressedBytes())
                .putInt(fallbackPayload.length)
                .put(fallbackPayload)
                .array();
    }

    public static Optional<ContentReference> decode(byte[] payload) {
        return decodeEnvelope(payload).map(ContentReferenceEnvelope::reference);
    }

    public static Optional<ContentReferenceEnvelope> decodeEnvelope(byte[] payload) {
        if (payload.length < V1_ENCODED_BYTES) {
            return Optional.empty();
        }
        ByteBuffer input = ByteBuffer.wrap(payload);
        if (input.getInt() != MAGIC) {
            return Optional.empty();
        }
        byte version = input.get();
        byte[] hash = new byte[ContentReference.SHA_256_BYTES];
        input.get(hash);
        int length = input.getInt();
        if (length < 0) {
            return Optional.empty();
        }
        ContentReference reference = new ContentReference(hash, length);
        if (version == VERSION_WITHOUT_FALLBACK && payload.length == V1_ENCODED_BYTES) {
            return Optional.of(new ContentReferenceEnvelope(reference, new byte[0]));
        }
        if (version != VERSION_WITH_FALLBACK || payload.length < V2_HEADER_BYTES) {
            return Optional.empty();
        }
        int fallbackLength = input.getInt();
        if (fallbackLength < 0
                || fallbackLength > MAX_FALLBACK_BYTES
                || fallbackLength != input.remaining()) {
            return Optional.empty();
        }
        byte[] fallback = new byte[fallbackLength];
        input.get(fallback);
        return Optional.of(new ContentReferenceEnvelope(reference, fallback));
    }
}
