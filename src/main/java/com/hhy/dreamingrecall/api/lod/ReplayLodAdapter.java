package com.hhy.dreamingrecall.api.lod;

import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;

import java.nio.file.Path;
import java.util.UUID;

/** Optional soft-dependency bridge for Distant Horizons, Voxy and similar LOD renderers. */
public interface ReplayLodAdapter {
    String id();

    boolean isAvailable();

    Session open(UUID archiveId, Path isolatedCacheRoot);

    interface Session extends AutoCloseable {
        void onSnapshot(ReplayWorldSnapshot snapshot);

        default void onSeekStarted() {
        }

        @Override
        void close();
    }
}
