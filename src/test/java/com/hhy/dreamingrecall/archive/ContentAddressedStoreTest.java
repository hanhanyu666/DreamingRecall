package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentAddressedStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identicalPayloadsReuseOneVerifiedObject() throws Exception {
        ContentAddressedStore store = new ContentAddressedStore(temporaryDirectory, 1);
        byte[] payload = new byte[64 * 1024];
        java.util.Arrays.fill(payload, (byte) 42);

        ContentReference first = store.put(payload);
        ContentReference second = store.put(payload.clone());

        assertEquals(first, second);
        assertTrue(Files.isRegularFile(store.path(first)));
        assertArrayEquals(payload, store.read(first));
    }

    @Test
    void referenceMarkerDoesNotConfuseOrdinaryPayload() {
        ContentReference reference = new ContentReference(new byte[32], 123);
        byte[] fallback = new byte[]{4, 5, 6};

        assertEquals(reference, ContentReferenceCodec.decode(ContentReferenceCodec.encode(reference)).orElseThrow());
        ContentReferenceEnvelope envelope = ContentReferenceCodec.decodeEnvelope(
                ContentReferenceCodec.encode(reference, fallback)
        ).orElseThrow();
        assertEquals(reference, envelope.reference());
        assertArrayEquals(fallback, envelope.fallbackPayload());
        assertTrue(ContentReferenceCodec.decode(new byte[]{1, 2, 3}).isEmpty());
    }
}
