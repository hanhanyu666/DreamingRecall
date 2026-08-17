package com.hhy.dreamingrecall.playback.source;

import com.hhy.dreamingrecall.archive.ArchiveIndex;
import com.hhy.dreamingrecall.archive.ArchiveFormat;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.archive.ArchiveScanResult;
import com.hhy.dreamingrecall.archive.ArchiveScanner;
import com.hhy.dreamingrecall.archive.ContentAddressedStore;
import com.hhy.dreamingrecall.archive.ContentReferenceCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalArchiveDataSource implements ArchiveDataSource {
    private final ArchiveManifest manifest;
    private final ArchiveIndex index;
    private final ArchiveIndex checkpointIndex;
    private final ContentAddressedStore contentStore;
    private final ExecutorService executor;

    private LocalArchiveDataSource(
            Path archiveDirectory,
            ArchiveManifest manifest,
            ArchiveIndex index,
            ArchiveIndex checkpointIndex
    ) {
        this.manifest = manifest;
        this.index = index;
        this.checkpointIndex = checkpointIndex;
        this.contentStore = new ContentAddressedStore(archiveDirectory, 1);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("DreamingRecall-LocalArchiveReader-", 0).factory()
        );
    }

    public static LocalArchiveDataSource open(Path archiveDirectory, String requiredMinecraftVersion) throws IOException {
        Objects.requireNonNull(requiredMinecraftVersion, "requiredMinecraftVersion");
        Path normalized = archiveDirectory.toAbsolutePath().normalize();
        ArchiveManifest manifest = ArchiveManifestCodec.readManifest(normalized);
        if (manifest.formatMajor() != ArchiveFormat.FORMAT_MAJOR
                || manifest.formatMinor() > ArchiveFormat.FORMAT_MINOR) {
            throw new IOException("Unsupported archive format " + manifest.formatMajor() + "." + manifest.formatMinor());
        }
        if (!manifest.minecraftVersion().equals(requiredMinecraftVersion)) {
            throw new IOException(
                    "Archive Minecraft version " + manifest.minecraftVersion()
                            + " does not match playback version " + requiredMinecraftVersion
            );
        }
        ArchiveScanResult scan = ArchiveScanner.scan(normalized, false);
        boolean hasStructuralError = scan.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.severity() == com.hhy.dreamingrecall.archive.ArchiveDiagnostic.Severity.ERROR);
        if (hasStructuralError || scan.index().segments().isEmpty()) {
            throw new IOException("Archive has no structurally valid replay segments");
        }
        ArchiveScanResult checkpoints = ArchiveScanner.scanSegmentDirectory(
                normalized.resolve("checkpoints"),
                false
        );
        return new LocalArchiveDataSource(normalized, manifest, scan.index(), checkpoints.index());
    }

    @Override
    public ArchiveManifest manifest() {
        return manifest;
    }

    @Override
    public ArchiveIndex index() {
        return index;
    }

    @Override
    public ArchiveIndex checkpointIndex() {
        return checkpointIndex;
    }

    @Override
    public CompletableFuture<SegmentReadResult> readSegment(SegmentMetadata segment, ReadCancellation cancellation) {
        return readSegment(segment, cancellation, true);
    }

    @Override
    public CompletableFuture<SegmentReadResult> readRawSegment(
            SegmentMetadata segment,
            ReadCancellation cancellation
    ) {
        return readSegment(segment, cancellation, false);
    }

    private CompletableFuture<SegmentReadResult> readSegment(
            SegmentMetadata segment,
            ReadCancellation cancellation,
            boolean resolveContent
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                cancellation.throwIfCancelled();
                SegmentReadResult encoded = SegmentCodec.read(segment.path());
                if (!resolveContent) {
                    return encoded;
                }
                ArrayList<ReplayRecord> resolved = new ArrayList<>(encoded.records().size());
                for (ReplayRecord record : encoded.records()) {
                    cancellation.throwIfCancelled();
                    byte[] payload = record.payloadCopy();
                    var envelope = record.typeId() == CoreRecordType.CHUNK_BASELINE.id()
                            ? ContentReferenceCodec.decodeEnvelope(payload)
                            : java.util.Optional.<com.hhy.dreamingrecall.archive.ContentReferenceEnvelope>empty();
                    if (envelope.isPresent()) {
                        try {
                            payload = contentStore.read(envelope.get().reference());
                        } catch (IOException contentFailure) {
                            byte[] fallback = envelope.get().fallbackPayload();
                            if (fallback.length == 0) {
                                throw contentFailure;
                            }
                            payload = fallback;
                        }
                    }
                    resolved.add(new ReplayRecord(
                            record.typeId(),
                            record.priority(),
                            record.archiveNanos(),
                            record.serverTick(),
                            record.dimensionId(),
                            payload
                    ));
                }
                return new SegmentReadResult(encoded.metadata(), resolved);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
