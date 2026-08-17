package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveAttachmentStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resourcePackIsContentAddressedAndVerified() throws Exception {
        Path archive = temporaryDirectory.resolve("archive");
        Path pack = temporaryDirectory.resolve("server-pack.zip");
        byte[] content = "resource-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(pack, content);

        ArchiveAttachmentStore store = new ArchiveAttachmentStore(archive);
        ArchiveAttachmentStore.AttachmentReference reference = store.put(pack, 1024);

        assertArrayEquals(content, store.read(reference));
        assertThrows(java.io.IOException.class, () -> store.put(pack, content.length - 1));
    }

    @Test
    void modJarIsRejected() throws Exception {
        Path jar = temporaryDirectory.resolve("example.jar");
        Files.write(jar, new byte[]{1, 2, 3});

        assertThrows(
                java.io.IOException.class,
                () -> new ArchiveAttachmentStore(temporaryDirectory.resolve("archive")).put(jar, 1024)
        );
    }
}
