package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveIndexTest {
    @Test
    void findsLatestSegmentStartingAtOrBeforeTarget() {
        ArchiveIndex index = new ArchiveIndex(List.of(
                segment(2, 200),
                segment(0, 0),
                segment(1, 100)
        ));

        assertTrue(index.segmentAtOrBefore(0).isPresent());
        assertEquals(0, index.segmentAtOrBefore(99).orElseThrow().sequence());
        assertEquals(1, index.segmentAtOrBefore(199).orElseThrow().sequence());
        assertEquals(2, index.segmentAtOrBefore(999).orElseThrow().sequence());
    }

    private static SegmentMetadata segment(long sequence, long start) {
        return new SegmentMetadata(Path.of(sequence + ".drseg"), sequence, start, start + 99, 1, 10, 8, 0);
    }
}
