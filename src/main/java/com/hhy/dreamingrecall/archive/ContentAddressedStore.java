package com.hhy.dreamingrecall.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32C;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class ContentAddressedStore {
    private static final int MAGIC = 0x44524354; // DRCT
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 5;
    private static final int MAX_CONTENT_BYTES = ArchiveFormat.MAX_SEGMENT_BYTES;

    private final Path root;
    private final int compressionLevel;

    public ContentAddressedStore(Path archiveDirectory, int compressionLevel) {
        this.root = archiveDirectory.resolve("content");
        this.compressionLevel = compressionLevel;
    }

    public ContentReference put(byte[] content) throws IOException {
        if (content.length > MAX_CONTENT_BYTES) {
            throw new IOException("Content object exceeds archive format limit");
        }
        byte[] hash = sha256(content);
        ContentReference reference = new ContentReference(hash, content.length);
        Path target = path(reference);
        if (Files.isRegularFile(target)) {
            return reference;
        }

        Files.createDirectories(target.getParent());
        byte[] compressed = deflate(content);
        CRC32C crc = new CRC32C();
        crc.update(content);
        Path partial = target.resolveSibling(target.getFileName() + ArchiveFormat.PARTIAL_EXTENSION);
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             OutputStream raw = Channels.newOutputStream(channel);
             DataOutputStream output = new DataOutputStream(raw)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(content.length);
            output.writeInt(compressed.length);
            output.writeInt((int) crc.getValue());
            output.write(compressed);
            output.flush();
            channel.force(true);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (Files.isRegularFile(target)) {
                return reference;
            }
            throw failure;
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            Files.deleteIfExists(partial);
        }
        return reference;
    }

    public byte[] read(ContentReference reference) throws IOException {
        Path path = path(reference);
        long size = Files.size(path);
        if (size < HEADER_BYTES) {
            throw new IOException("Content object is shorter than its header");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported content object format");
            }
            int uncompressedLength = input.readInt();
            int compressedLength = input.readInt();
            int expectedCrc = input.readInt();
            if (uncompressedLength != reference.uncompressedBytes()
                    || uncompressedLength < 0
                    || uncompressedLength > MAX_CONTENT_BYTES
                    || compressedLength <= 0
                    || compressedLength > MAX_CONTENT_BYTES
                    || size != HEADER_BYTES + (long) compressedLength) {
                throw new IOException("Invalid content object lengths");
            }
            byte[] compressed = input.readNBytes(compressedLength);
            byte[] content = inflate(compressed, uncompressedLength);
            CRC32C crc = new CRC32C();
            crc.update(content);
            if ((int) crc.getValue() != expectedCrc) {
                throw new IOException("Content object checksum mismatch");
            }
            if (!Arrays.equals(sha256(content), reference.sha256Copy())) {
                throw new IOException("Content object identity mismatch");
            }
            return content;
        }
    }

    public Path path(ContentReference reference) {
        String hash = reference.hexHash();
        return root.resolve(hash.substring(0, 2)).resolve(hash + ".drcontent");
    }

    private byte[] deflate(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(compressionLevel);
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes, deflater, 64 * 1024)) {
            output.write(content);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] content = input.readNBytes(expectedLength + 1);
            if (content.length != expectedLength) {
                throw new IOException("Inflated content size does not match its header");
            }
            return content;
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
