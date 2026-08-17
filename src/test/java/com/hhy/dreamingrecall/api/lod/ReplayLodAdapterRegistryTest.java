package com.hhy.dreamingrecall.api.lod;

import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayLodAdapterRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void isolatesRepeatedAdapterFailuresButStillClosesItsSession() {
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger snapshotCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        String adapterId = "test-" + UUID.randomUUID();
        ReplayLodAdapterRegistry.register(new ReplayLodAdapter() {
            @Override
            public String id() {
                return adapterId;
            }

            @Override
            public boolean isAvailable() {
                return available.get();
            }

            @Override
            public Session open(UUID archiveId, Path isolatedCacheRoot) {
                return new Session() {
                    @Override
                    public void onSnapshot(ReplayWorldSnapshot snapshot) {
                        snapshotCalls.incrementAndGet();
                        throw new IllegalStateException("adapter failure");
                    }

                    @Override
                    public void close() {
                        closeCalls.incrementAndGet();
                    }
                };
            }
        });

        try {
            List<ReplayLodAdapterRegistry.SessionHandle> handles = ReplayLodAdapterRegistry.open(
                    UUID.randomUUID(),
                    temporaryDirectory
            );
            ReplayLodAdapterRegistry.SessionHandle handle = handles.stream()
                    .filter(candidate -> candidate.id().equals(adapterId))
                    .findFirst()
                    .orElseThrow();
            ReplayWorldSnapshot snapshot = new ReplayWorldSnapshot(
                    0,
                    0,
                    false,
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );

            for (int index = 0; index < 5; index++) {
                handle.onSnapshot(snapshot);
            }
            assertEquals(3, snapshotCalls.get());

            handle.close();
            handle.close();
            handle.onSnapshot(snapshot);
            assertEquals(1, closeCalls.get());
            assertEquals(3, snapshotCalls.get());
        } finally {
            available.set(false);
        }
    }
}
