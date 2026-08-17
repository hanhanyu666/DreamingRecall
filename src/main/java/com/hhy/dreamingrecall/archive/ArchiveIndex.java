package com.hhy.dreamingrecall.archive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ArchiveIndex {
    private final List<SegmentMetadata> segments;

    public ArchiveIndex(List<SegmentMetadata> segments) {
        ArrayList<SegmentMetadata> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(SegmentMetadata::sequence));
        this.segments = List.copyOf(sorted);
    }

    public List<SegmentMetadata> segments() {
        return segments;
    }

    public Optional<SegmentMetadata> segmentAtOrBefore(long archiveNanos) {
        int low = 0;
        int high = segments.size() - 1;
        SegmentMetadata candidate = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            SegmentMetadata current = segments.get(middle);
            if (current.startArchiveNanos() <= archiveNanos) {
                candidate = current;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return Optional.ofNullable(candidate);
    }

    public List<SegmentMetadata> segmentsIntersecting(long startArchiveNanos, long endArchiveNanos) {
        if (startArchiveNanos < 0 || endArchiveNanos < startArchiveNanos) {
            throw new IllegalArgumentException("Invalid archive time range");
        }
        ArrayList<SegmentMetadata> matches = new ArrayList<>();
        for (SegmentMetadata segment : segments) {
            if (segment.endArchiveNanos() < startArchiveNanos) {
                continue;
            }
            if (segment.startArchiveNanos() > endArchiveNanos) {
                break;
            }
            matches.add(segment);
        }
        return List.copyOf(matches);
    }
}
