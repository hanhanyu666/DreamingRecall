package com.hhy.dreamingrecall.api.extension;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionFrameCodecTest {
    @Test
    void roundTripsVersionedOptionalPayload() throws Exception {
        ExtensionFrame frame = new ExtensionFrame("example:machines", 3, "animation", "minecraft:overworld", new byte[]{1, 2, 3});

        ExtensionFrame decoded = ExtensionFrameCodec.decode(ExtensionFrameCodec.encode(frame));

        assertEquals(frame.extensionId(), decoded.extensionId());
        assertEquals(frame.schemaVersion(), decoded.schemaVersion());
        assertEquals(frame.channel(), decoded.channel());
        assertEquals(frame.scope(), decoded.scope());
        assertArrayEquals(frame.payloadCopy(), decoded.payloadCopy());
    }

    @Test
    void rejectsTruncatedPayload() throws Exception {
        byte[] encoded = ExtensionFrameCodec.encode(new ExtensionFrame("example:test", 1, "state", "", new byte[]{1, 2, 3}));
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);

        assertThrows(IOException.class, () -> ExtensionFrameCodec.decode(truncated));
    }
}
