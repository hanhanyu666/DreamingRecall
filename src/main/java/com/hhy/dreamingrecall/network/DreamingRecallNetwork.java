package com.hhy.dreamingrecall.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class DreamingRecallNetwork {
    private static final String PROTOCOL = "3";

    private DreamingRecallNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(DreamingRecallNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).optional().playToServer(
                CameraSamplePayload.TYPE,
                CameraSamplePayload.STREAM_CODEC,
                CameraSamplePayload::handle
        ).playToServer(
                PlayerVisualSamplePayload.TYPE,
                PlayerVisualSamplePayload.STREAM_CODEC,
                PlayerVisualSamplePayload::handle
        ).playToServer(
                StartRecordingRequestPayload.TYPE,
                StartRecordingRequestPayload.STREAM_CODEC,
                StartRecordingRequestPayload::handle
        ).playToServer(
                ClientPacketBatchPayload.TYPE,
                ClientPacketBatchPayload.STREAM_CODEC,
                ClientPacketBatchPayload::handle
        ).playToClient(
                RecordingControlPayload.TYPE,
                RecordingControlPayload.STREAM_CODEC,
                RecordingControlPayload::handle
        );
    }
}
