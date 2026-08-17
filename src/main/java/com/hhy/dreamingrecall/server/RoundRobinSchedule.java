package com.hhy.dreamingrecall.server;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class RoundRobinSchedule<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final Set<T> members = new HashSet<>();

    boolean add(T value) {
        Objects.requireNonNull(value, "value");
        if (!members.add(value)) {
            return false;
        }
        queue.addLast(value);
        return true;
    }

    T next() {
        T value = queue.pollFirst();
        if (value != null) {
            queue.addLast(value);
        }
        return value;
    }

    boolean remove(T value) {
        if (!members.remove(value)) {
            return false;
        }
        queue.remove(value);
        return true;
    }

    int size() {
        return queue.size();
    }

    void clear() {
        queue.clear();
        members.clear();
    }
}
