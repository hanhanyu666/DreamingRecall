package com.hhy.dreamingrecall.playback;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class ExtensionFaultBoundary {
    private final int disableThreshold;
    private final BiConsumer<String, Throwable> diagnosticSink;
    private final Map<String, Integer> failures = new HashMap<>();

    public ExtensionFaultBoundary(int disableThreshold, BiConsumer<String, Throwable> diagnosticSink) {
        if (disableThreshold < 1) {
            throw new IllegalArgumentException("disableThreshold must be positive");
        }
        this.disableThreshold = disableThreshold;
        this.diagnosticSink = diagnosticSink;
    }

    public <T> Optional<T> invoke(String extensionId, CheckedSupplier<T> callback) {
        if (isDisabled(extensionId)) {
            return Optional.empty();
        }
        try {
            T result = callback.get();
            failures.remove(extensionId);
            return Optional.ofNullable(result);
        } catch (Throwable failure) {
            failures.merge(extensionId, 1, Integer::sum);
            diagnosticSink.accept(extensionId, failure);
            return Optional.empty();
        }
    }

    public boolean isDisabled(String extensionId) {
        return failures.getOrDefault(extensionId, 0) >= disableThreshold;
    }

    public int failureCount(String extensionId) {
        return failures.getOrDefault(extensionId, 0);
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
