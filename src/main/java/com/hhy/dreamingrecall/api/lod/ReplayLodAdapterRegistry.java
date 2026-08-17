package com.hhy.dreamingrecall.api.lod;

import com.hhy.dreamingrecall.playback.ExtensionFaultBoundary;
import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReplayLodAdapterRegistry {
    private static final System.Logger LOGGER = System.getLogger(ReplayLodAdapterRegistry.class.getName());
    private static final List<ReplayLodAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private ReplayLodAdapterRegistry() {
    }

    public static void register(ReplayLodAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        if (adapter.id() == null || adapter.id().isBlank()) {
            throw new IllegalArgumentException("LOD adapter id cannot be blank");
        }
        ADAPTERS.removeIf(existing -> existing.id().equals(adapter.id()));
        ADAPTERS.add(adapter);
    }

    public static List<ReplayLodAdapter> adapters() {
        return List.copyOf(ADAPTERS);
    }

    public static List<SessionHandle> open(UUID archiveId, Path gameDirectory) {
        Path root = gameDirectory.toAbsolutePath().normalize()
                .resolve("dreamingrecall").resolve("lod").resolve(archiveId.toString());
        ArrayList<SessionHandle> sessions = new ArrayList<>();
        for (ReplayLodAdapter adapter : ADAPTERS) {
            if (!adapter.isAvailable()) {
                continue;
            }
            try {
                Files.createDirectories(root.resolve(adapter.id()));
                ReplayLodAdapter.Session session = adapter.open(archiveId, root.resolve(adapter.id()));
                if (session != null) {
                    sessions.add(new SessionHandle(adapter.id(), session));
                }
            } catch (Throwable failure) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Could not open optional replay LOD adapter " + adapter.id(),
                        failure
                );
            }
        }
        return List.copyOf(sessions);
    }

    public static final class SessionHandle implements AutoCloseable {
        private final String id;
        private final ReplayLodAdapter.Session delegate;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ExtensionFaultBoundary faults = new ExtensionFaultBoundary(
                3,
                (extension, failure) -> LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Replay LOD adapter " + extension + " failed",
                        failure
                )
        );

        private SessionHandle(String id, ReplayLodAdapter.Session delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        public String id() {
            return id;
        }

        public void onSnapshot(ReplayWorldSnapshot snapshot) {
            if (closed.get()) {
                return;
            }
            faults.invoke(id, () -> {
                delegate.onSnapshot(snapshot);
                return null;
            });
        }

        public void onSeekStarted() {
            if (closed.get()) {
                return;
            }
            faults.invoke(id, () -> {
                delegate.onSeekStarted();
                return null;
            });
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.close();
            } catch (Throwable failure) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Could not close replay LOD adapter " + id,
                        failure
                );
            }
        }
    }
}
