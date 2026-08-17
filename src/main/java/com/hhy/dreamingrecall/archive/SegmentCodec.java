package com.hhy.dreamingrecall.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class SegmentCodec {
    private static final int COMPRESSION_DEFLATE = 1;
    private static final int FIXED_FRAME_BYTES = Integer.BYTES + 1 + Long.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES;

    private SegmentCodec() {
    }

    public static byte[] encodeRecords(List<ReplayRecord> records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (ReplayRecord record : records) {
                byte[] dimension = record.dimensionId().getBytes(StandardCharsets.UTF_8);
                if (dimension.length > ArchiveFormat.MAX_DIMENSION_ID_BYTES) {
                    throw new IOException("Dimension identifier is too long");
                }
                if (record.payloadSize() > ArchiveFormat.MAX_RECORD_BYTES) {
                    throw new IOException("Record payload exceeds format limit");
                }
                int frameBytes = Math.addExact(FIXED_FRAME_BYTES, Math.addExact(dimension.length, record.payloadSize()));
                output.writeInt(frameBytes);
                output.writeInt(record.typeId());
                output.writeByte(record.priority().id());
                output.writeLong(record.archiveNanos());
                output.writeLong(record.serverTick());
                output.writeInt(dimension.length);
                output.write(dimension);
                output.writeInt(record.payloadSize());
                record.writePayload(output);
            }
        }
        return bytes.toByteArray();
    }

    public static List<ReplayRecord> decodeRecords(byte[] encoded, int expectedCount) throws IOException {
        ArrayList<ReplayRecord> records = new ArrayList<>(expectedCount);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            for (int index = 0; index < expectedCount; index++) {
                int frameBytes = input.readInt();
                if (frameBytes < FIXED_FRAME_BYTES || frameBytes > ArchiveFormat.MAX_RECORD_BYTES + ArchiveFormat.MAX_DIMENSION_ID_BYTES + FIXED_FRAME_BYTES) {
                    throw new IOException("Invalid record frame size " + frameBytes);
                }
                byte[] frame = input.readNBytes(frameBytes);
                if (frame.length != frameBytes) {
                    throw new EOFException("Truncated record frame");
                }
                records.add(decodeFrame(frame));
            }
            if (input.read() != -1) {
                throw new IOException("Segment contains trailing record bytes");
            }
        }
        return records;
    }

    private static ReplayRecord decodeFrame(byte[] frame) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame))) {
            int typeId = input.readInt();
            RecordPriority priority = RecordPriority.fromId(input.readUnsignedByte());
            long archiveNanos = input.readLong();
            long serverTick = input.readLong();
            int dimensionLength = readBoundedLength(input, ArchiveFormat.MAX_DIMENSION_ID_BYTES, "dimension identifier");
            String dimension = new String(input.readNBytes(dimensionLength), StandardCharsets.UTF_8);
            int payloadLength = readBoundedLength(input, ArchiveFormat.MAX_RECORD_BYTES, "record payload");
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength || input.read() != -1) {
                throw new IOException("Record frame length does not match its fields");
            }
            return new ReplayRecord(typeId, priority, archiveNanos, serverTick, dimension, payload);
        }
    }

    public static SegmentMetadata commit(
            Path segmentsDirectory,
            long sequence,
            List<ReplayRecord> records,
            int compressionLevel
    ) throws IOException {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("Cannot commit an empty segment");
        }
        Files.createDirectories(segmentsDirectory);
        byte[] uncompressed = encodeRecords(records);
        if (uncompressed.length > ArchiveFormat.MAX_SEGMENT_BYTES) {
            throw new IOException("Segment exceeds format size limit");
        }
        byte[] compressed = deflate(uncompressed, compressionLevel);
        CRC32C crc = new CRC32C();
        crc.update(uncompressed);
        int checksum = (int) crc.getValue();
        long start = records.getFirst().archiveNanos();
        long end = records.getLast().archiveNanos();

        Path partial = segmentsDirectory.resolve(ArchiveFormat.partialFileName(sequence));
        Path committed = segmentsDirectory.resolve(ArchiveFormat.segmentFileName(sequence));
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             OutputStream raw = Channels.newOutputStream(channel);
             DataOutputStream output = new DataOutputStream(raw)) {
            output.writeInt(ArchiveFormat.SEGMENT_MAGIC);
            output.writeShort(ArchiveFormat.SEGMENT_FORMAT_MAJOR);
            output.writeShort(ArchiveFormat.SEGMENT_FORMAT_MINOR);
            output.writeByte(COMPRESSION_DEFLATE);
            output.writeLong(sequence);
            output.writeLong(start);
            output.writeLong(end);
            output.writeInt(records.size());
            output.writeInt(uncompressed.length);
            output.writeInt(compressed.length);
            output.writeInt(checksum);
            output.write(compressed);
            output.flush();
            channel.force(true);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        try {
            Files.move(partial, committed, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, committed);
        }
        return new SegmentMetadata(committed, sequence, start, end, records.size(), uncompressed.length, compressed.length, checksum);
    }

    public static SegmentMetadata readMetadata(Path path) throws IOException {
        long fileSize = Files.size(path);
        if (fileSize < ArchiveFormat.SEGMENT_HEADER_BYTES) {
            throw new IOException("Segment is shorter than its header");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            Header header = readHeader(input);
            long expectedSize = Math.addExact(ArchiveFormat.SEGMENT_HEADER_BYTES, header.compressedBytes());
            if (fileSize != expectedSize) {
                throw new IOException("Segment file size does not match its header");
            }
            return header.metadata(path);
        }
    }

    public static SegmentReadResult read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            Header header = readHeader(input);
            byte[] compressed = input.readNBytes(header.compressedBytes());
            if (compressed.length != header.compressedBytes() || input.read() != -1) {
                throw new IOException("Segment payload length does not match its header");
            }
            byte[] uncompressed = inflate(compressed, header.uncompressedBytes());
            CRC32C crc = new CRC32C();
            crc.update(uncompressed);
            if ((int) crc.getValue() != header.payloadCrc32c()) {
                throw new IOException("Segment payload checksum mismatch");
            }
            List<ReplayRecord> records = decodeRecords(uncompressed, header.recordCount());
            if (records.isEmpty()
                    || records.getFirst().archiveNanos() != header.startArchiveNanos()
                    || records.getLast().archiveNanos() != header.endArchiveNanos()) {
                throw new IOException("Segment time bounds do not match its records");
            }
            return new SegmentReadResult(header.metadata(path), records);
        }
    }

    private static Header readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != ArchiveFormat.SEGMENT_MAGIC) {
            throw new IOException("Not a DreamingRecall segment");
        }
        int major = input.readUnsignedShort();
        int minor = input.readUnsignedShort();
        if (major != ArchiveFormat.SEGMENT_FORMAT_MAJOR || minor > ArchiveFormat.SEGMENT_FORMAT_MINOR) {
            throw new IOException("Unsupported segment format " + major + "." + minor);
        }
        int compression = input.readUnsignedByte();
        if (compression != COMPRESSION_DEFLATE) {
            throw new IOException("Unsupported segment compression " + compression);
        }
        long sequence = input.readLong();
        long start = input.readLong();
        long end = input.readLong();
        int recordCount = input.readInt();
        int uncompressed = input.readInt();
        int compressed = input.readInt();
        int checksum = input.readInt();
        if (sequence < 0 || start < 0 || end < start || recordCount <= 0) {
            throw new IOException("Invalid segment identity or time bounds");
        }
        if (uncompressed <= 0 || uncompressed > ArchiveFormat.MAX_SEGMENT_BYTES
                || compressed <= 0 || compressed > ArchiveFormat.MAX_SEGMENT_BYTES) {
            throw new IOException("Invalid segment payload size");
        }
        return new Header(sequence, start, end, recordCount, uncompressed, compressed, checksum);
    }

    private static int readBoundedLength(DataInputStream input, int maximum, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IOException("Invalid " + label + " length " + length);
        }
        return length;
    }

    private static byte[] deflate(byte[] bytes, int compressionLevel) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(compressionLevel);
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed, deflater, 64 * 1024)) {
            output.write(bytes);
        } finally {
            deflater.end();
        }
        return compressed.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, int expectedBytes) throws IOException {
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = input.readNBytes(expectedBytes + 1);
            if (bytes.length != expectedBytes) {
                throw new IOException("Inflated segment size does not match its header");
            }
            return bytes;
        }
    }

    private record Header(
            long sequence,
            long startArchiveNanos,
            long endArchiveNanos,
            int recordCount,
            int uncompressedBytes,
            int compressedBytes,
            int payloadCrc32c
    ) {
        SegmentMetadata metadata(Path path) {
            return new SegmentMetadata(
                    path,
                    sequence,
                    startArchiveNanos,
                    endArchiveNanos,
                    recordCount,
                    uncompressedBytes,
                    compressedBytes,
                    payloadCrc32c
            );
        }
    }
}
