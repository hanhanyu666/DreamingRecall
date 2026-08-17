package com.hhy.dreamingrecall.archive;

import java.util.Arrays;

public final class ContentReference {
    public static final int SHA_256_BYTES = 32;
    private final byte[] sha256;
    private final int uncompressedBytes;

    public ContentReference(byte[] sha256, int uncompressedBytes) {
        if (sha256.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("Content identity must be SHA-256");
        }
        if (uncompressedBytes < 0) {
            throw new IllegalArgumentException("Content length cannot be negative");
        }
        this.sha256 = sha256.clone();
        this.uncompressedBytes = uncompressedBytes;
    }

    public byte[] sha256Copy() {
        return sha256.clone();
    }

    public int uncompressedBytes() {
        return uncompressedBytes;
    }

    public String hexHash() {
        return java.util.HexFormat.of().formatHex(sha256);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentReference that
                && uncompressedBytes == that.uncompressedBytes
                && Arrays.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(sha256) + uncompressedBytes;
    }
}
