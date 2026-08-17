package com.hhy.dreamingrecall.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinScheduleTest {
    @Test
    void visitsAtMostTheRequestedBudgetAndRotatesFairly() {
        RoundRobinSchedule<String> schedule = new RoundRobinSchedule<>();
        assertTrue(schedule.add("one"));
        assertTrue(schedule.add("two"));
        assertTrue(schedule.add("three"));
        assertFalse(schedule.add("two"));

        assertEquals(List.of("one", "two"), take(schedule, 2));
        assertEquals(List.of("three", "one"), take(schedule, 2));
        assertEquals(3, schedule.size());
    }

    @Test
    void removalAndClearPreserveQueueMembership() {
        RoundRobinSchedule<String> schedule = new RoundRobinSchedule<>();
        schedule.add("one");
        schedule.add("two");
        schedule.next();

        assertTrue(schedule.remove("one"));
        assertFalse(schedule.remove("one"));
        assertEquals("two", schedule.next());

        schedule.clear();
        assertEquals(0, schedule.size());
        assertNull(schedule.next());
    }

    private static <T> List<T> take(RoundRobinSchedule<T> schedule, int budget) {
        ArrayList<T> values = new ArrayList<>();
        for (int index = 0; index < Math.min(budget, schedule.size()); index++) {
            values.add(schedule.next());
        }
        return values;
    }
}
