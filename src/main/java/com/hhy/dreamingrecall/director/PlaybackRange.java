package com.hhy.dreamingrecall.director;

public record PlaybackRange(long startArchiveNanos, long endArchiveNanos) {
    public PlaybackRange {
        if (startArchiveNanos < 0 || endArchiveNanos < startArchiveNanos) {
            throw new IllegalArgumentException("Invalid playback range");
        }
    }
}
