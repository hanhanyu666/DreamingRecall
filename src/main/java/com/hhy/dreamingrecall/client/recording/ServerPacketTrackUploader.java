package com.hhy.dreamingrecall.client.recording;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayViewController;
import com.hhy.dreamingrecall.client.playback.packet.ReplayPacketDispatchContext;
import com.hhy.dreamingrecall.network.ClientPacketBatchPayload;
import com.hhy.dreamingrecall.network.RecordingControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Retains the bounded connection prelude needed to construct a replay client,
 * then uploads clientbound frames only while the server says it is recording.
 */
public final class ServerPacketTrackUploader {
    public static final ServerPacketTrackUploader INSTANCE = new ServerPacketTrackUploader();

    private static final int MAX_PRELUDE_FRAMES = 16_384;
    private static final int MAX_PENDING_FRAMES = 32_768;
    private static final long MAX_PRELUDE_BYTES = 24L * 1024 * 1024;
    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;

    private final ArrayDeque<ClientPacketBatchPayload.Frame> prelude = new ArrayDeque<>();
    private final ArrayDeque<ClientPacketBatchPayload.Frame> pending = new ArrayDeque<>();

    private UUID activeRecordingId;
    private ProtocolPhase lastPhase;
    private String dimensionId = "";
    private long preludeBytes;
    private long pendingBytes;
    private long nextSequence;
    private boolean preludeComplete = true;
    private boolean uploadFailed;
    private boolean discontinuityPending;
    private boolean cameraTrackAllowed;

    private ServerPacketTrackUploader() {
    }

    public synchronized boolean isActive() {
        return activeRecordingId != null && !uploadFailed;
    }

    public synchronized boolean cameraTrackAllowed() {
        return activeRecordingId != null && cameraTrackAllowed;
    }

    public void inboundPacket(ProtocolInfo<?> protocol, Packet<?> packet, byte[] frameBytes) {
        if (ReplayPacketDispatchContext.isActive()) {
            return;
        }
        ProtocolPhase phase = protocolPhase(protocol.id());
        if (phase == null) {
            return;
        }
        String packetTypeId = packet.type().id().toString();
        String namespace = packet instanceof ClientboundCustomPayloadPacket custom
                ? custom.payload().type().id().getNamespace()
                : packet.type().id().getNamespace();
        ClientPacketBatchPayload.Frame frame;
        try {
            frame = new ClientPacketBatchPayload.Frame(
                    System.nanoTime(),
                    phase,
                    packetTypeId,
                    namespace,
                    phase == ProtocolPhase.PLAY ? currentDimensionId() : "",
                    frameBytes
            );
        } catch (IllegalArgumentException tooLargeOrInvalid) {
            markDiscontinuity();
            return;
        }

        synchronized (this) {
            if (phase == ProtocolPhase.LOGIN && lastPhase == ProtocolPhase.PLAY) {
                resetConnectionLocked();
            }
            lastPhase = phase;
            if (activeRecordingId == null) {
                retainPreludeLocked(frame);
            } else if (!uploadFailed) {
                enqueuePendingLocked(frame);
            }
        }
    }

    public synchronized void recordingControl(RecordingControlPayload control) {
        Objects.requireNonNull(control, "control");
        if (!control.active()) {
            if (control.recordingId().equals(activeRecordingId)) {
                activeRecordingId = null;
                cameraTrackAllowed = false;
                pending.clear();
                pendingBytes = 0;
                uploadFailed = false;
                discontinuityPending = false;
                // A second archive cannot replay the period omitted between two
                // controls from this connection, so force its packet track to
                // use the semantic fallback unless the player reconnects.
                prelude.clear();
                preludeBytes = 0;
                preludeComplete = false;
            }
            return;
        }
        if (control.recordingId().equals(activeRecordingId)) {
            cameraTrackAllowed = control.cameraTrackAllowed();
            return;
        }

        activeRecordingId = control.recordingId();
        cameraTrackAllowed = control.cameraTrackAllowed();
        nextSequence = 0;
        pending.clear();
        pendingBytes = 0;
        uploadFailed = false;
        discontinuityPending = !preludeComplete;
        while (!prelude.isEmpty()) {
            ClientPacketBatchPayload.Frame frame = prelude.removeFirst();
            pending.addLast(frame);
            pendingBytes += frame.estimatedWireBytes();
        }
        preludeBytes = 0;
        if (!preludeComplete || pendingBytes > MAX_PENDING_BYTES || pending.size() > MAX_PENDING_FRAMES) {
            failUploadLocked();
        }
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.level != null
                && !ReplayWorldController.isReplayLevel(minecraft.level)
                && !PacketReplayViewController.isReplayLevel(minecraft.level)) {
            synchronized (this) {
                dimensionId = minecraft.level.dimension().location().toString();
            }
        }
        ClientPacketBatchPayload batch = nextBatch();
        if (batch == null) {
            return;
        }
        if (minecraft.getConnection() == null
                || !NetworkRegistry.hasChannel(minecraft.getConnection(), ClientPacketBatchPayload.TYPE.id())) {
            markDiscontinuity();
            return;
        }
        try {
            PacketDistributor.sendToServer(batch);
        } catch (RuntimeException failure) {
            DreamingRecall.LOGGER.warn("Could not upload DreamingRecall packet track batch", failure);
            markDiscontinuity();
        }
    }

    public synchronized void disconnected() {
        resetConnectionLocked();
    }

    private synchronized ClientPacketBatchPayload nextBatch() {
        if (activeRecordingId == null || pending.isEmpty() && !discontinuityPending) {
            return null;
        }
        ArrayList<ClientPacketBatchPayload.Frame> frames = new ArrayList<>();
        int bytes = 0;
        while (!pending.isEmpty() && frames.size() < ClientPacketBatchPayload.MAX_FRAMES) {
            ClientPacketBatchPayload.Frame frame = pending.peekFirst();
            int frameBytes = frame.estimatedWireBytes();
            if (!frames.isEmpty() && bytes + frameBytes > ClientPacketBatchPayload.MAX_BATCH_BYTES) {
                break;
            }
            if (frameBytes > ClientPacketBatchPayload.MAX_BATCH_BYTES) {
                failUploadLocked();
                break;
            }
            pending.removeFirst();
            pendingBytes -= frameBytes;
            frames.add(frame);
            bytes += frameBytes;
        }
        boolean discontinuity = discontinuityPending;
        discontinuityPending = false;
        return new ClientPacketBatchPayload(activeRecordingId, nextSequence++, discontinuity, frames);
    }

    private void retainPreludeLocked(ClientPacketBatchPayload.Frame frame) {
        if (!preludeComplete) {
            return;
        }
        int bytes = frame.estimatedWireBytes();
        if (prelude.size() >= MAX_PRELUDE_FRAMES || preludeBytes + bytes > MAX_PRELUDE_BYTES) {
            prelude.clear();
            preludeBytes = 0;
            preludeComplete = false;
            return;
        }
        prelude.addLast(frame);
        preludeBytes += bytes;
    }

    private void enqueuePendingLocked(ClientPacketBatchPayload.Frame frame) {
        int bytes = frame.estimatedWireBytes();
        if (pending.size() >= MAX_PENDING_FRAMES || pendingBytes + bytes > MAX_PENDING_BYTES) {
            failUploadLocked();
            return;
        }
        pending.addLast(frame);
        pendingBytes += bytes;
    }

    private void markDiscontinuity() {
        synchronized (this) {
            if (activeRecordingId == null) {
                preludeComplete = false;
                prelude.clear();
                preludeBytes = 0;
            } else {
                failUploadLocked();
            }
        }
    }

    private void failUploadLocked() {
        uploadFailed = true;
        discontinuityPending = true;
        pending.clear();
        pendingBytes = 0;
    }

    private void resetConnectionLocked() {
        activeRecordingId = null;
        lastPhase = null;
        dimensionId = "";
        prelude.clear();
        pending.clear();
        preludeBytes = 0;
        pendingBytes = 0;
        nextSequence = 0;
        preludeComplete = true;
        uploadFailed = false;
        discontinuityPending = false;
        cameraTrackAllowed = false;
    }

    private synchronized String currentDimensionId() {
        return dimensionId;
    }

    private static ProtocolPhase protocolPhase(ConnectionProtocol protocol) {
        return switch (protocol) {
            case LOGIN -> ProtocolPhase.LOGIN;
            case CONFIGURATION -> ProtocolPhase.CONFIGURATION;
            case PLAY -> ProtocolPhase.PLAY;
            default -> null;
        };
    }
}
