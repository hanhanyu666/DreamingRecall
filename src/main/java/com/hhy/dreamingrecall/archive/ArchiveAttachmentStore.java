package com.hhy.dreamingrecall.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stores optional server resource packs as immutable, content-addressed files.
 * Mod JARs are deliberately rejected at this boundary.
 */
public final class ArchiveAttachmentStore {
    private static final String EXTENSION = ".drpack";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;

    public ArchiveAttachmentStore(Path archiveDirectory) {
        this.root = archiveDirectory.toAbsolutePath().normalize().resolve("attachments");
    }

    public AttachmentReference put(Path source, long maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        Path normalized = source.toAbsolutePath().normalize();
        String name = normalized.getFileName() == null ? "resource-pack.zip" : normalized.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Mod JARs cannot be archived as resource-pack attachments");
        }
        long size = Files.size(normalized);
        if (size < 0 || size > maxBytes || size > Integer.MAX_VALUE) {
            throw new IOException("Resource-pack attachment exceeds configured limit");
        }
        byte[] hash = digest(normalized, size);
        String hex = HexFormat.of().formatHex(hash);
        Path target = root.resolve(hex.substring(0, 2)).resolve(hex + EXTENSION);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ArchiveFormat.PARTIAL_EXTENSION);
            try {
                Files.copy(normalized, partial, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.size(partial) != size
                        || !java.util.Arrays.equals(digest(partial, size), hash)) {
                    throw new IOException("Resource-pack changed while it was being archived");
                }
                try {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) {
                Files.deleteIfExists(partial);
                if (!Files.isRegularFile(target)) {
                    throw failure;
                }
            }
        }
        return new AttachmentReference(hex, (int) size, name);
    }

    public byte[] read(AttachmentReference reference) throws IOException {
        Objects.requireNonNull(reference, "reference");
        Path file = path(reference.sha256Hex());
        if (!Files.isRegularFile(file) || Files.size(file) != reference.bytes()) {
            throw new IOException("Resource-pack attachment is missing or has an unexpected size");
        }
        byte[] content = Files.readAllBytes(file);
        String actual = HexFormat.of().formatHex(sha256(content));
        if (!actual.equals(reference.sha256Hex())) {
            throw new IOException("Resource-pack attachment checksum mismatch");
        }
        return content;
    }

    public Path path(String sha256Hex) {
        if (sha256Hex == null || !sha256Hex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid attachment hash");
        }
        String normalized = sha256Hex.toLowerCase(java.util.Locale.ROOT);
        return root.resolve(normalized.substring(0, 2)).resolve(normalized + EXTENSION);
    }

    private static byte[] digest(Path file, long expectedSize) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    read += count;
                    digest.update(buffer, 0, count);
                }
            }
            if (read != expectedSize) {
                throw new IOException("Resource-pack changed while it was being archived");
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    public record AttachmentReference(String sha256Hex, int bytes, String originalName) {
        public AttachmentReference {
            Objects.requireNonNull(sha256Hex, "sha256Hex");
            Objects.requireNonNull(originalName, "originalName");
            if (!sha256Hex.matches("[0-9a-fA-F]{64}") || bytes < 0 || originalName.isBlank()) {
                throw new IllegalArgumentException("Invalid attachment reference");
            }
        }
    }
}
