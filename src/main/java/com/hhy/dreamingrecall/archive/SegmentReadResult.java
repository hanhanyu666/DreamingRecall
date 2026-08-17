package com.hhy.dreamingrecall.archive;

import java.util.List;

public record SegmentReadResult(SegmentMetadata metadata, List<ReplayRecord> records) {
    public SegmentReadResult {
        records = List.copyOf(records);
    }
}
