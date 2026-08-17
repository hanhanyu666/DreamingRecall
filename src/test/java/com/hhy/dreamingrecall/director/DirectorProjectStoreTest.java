package com.hhy.dreamingrecall.director;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorProjectStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingProjectIsCreatedAndSavedUnderClientDirectorRoot() throws Exception {
        UUID archiveId = UUID.randomUUID();
        DirectorProject project = DirectorProjectStore.loadOrCreate(
                temporaryDirectory,
                archiveId,
                "test archive"
        );

        assertEquals(archiveId, project.archiveId());
        DirectorProjectStore.save(temporaryDirectory, project);

        Path expected = DirectorProjectStore.path(temporaryDirectory, archiveId);
        assertTrue(Files.isRegularFile(expected));
        assertEquals(project, DirectorProjectCodec.read(expected));
    }
}
