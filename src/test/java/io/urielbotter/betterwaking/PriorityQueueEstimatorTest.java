package io.urielbotter.betterwaking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityQueueEstimatorTest {
    private static final Instant NOW = Instant.parse("2026-11-24T22:00:00Z");

    private final PriorityQueueEstimator estimator = new PriorityQueueEstimator();

    @Test
    void needsThreeDistinctReportsWithMeasuredProgress() {
        assertTrue(estimator.estimatedWait(42).isEmpty());
        estimator.observe(minute(0), List.of(position("a", 0, 100)));
        estimator.observe(minute(2), List.of(position("a", 2, 96)));
        estimator.observe(minute(3), List.of(position("a", 2, 96)));
        assertTrue(estimator.estimatedWait(42).isEmpty());

        estimator.observe(minute(4), List.of(position("a", 4, 92)));
        assertEquals(Optional.of(Duration.ofMinutes(21)), estimator.estimatedWait(42));
        assertEquals(Optional.of(Duration.ZERO), estimator.estimatedWait(0));
        assertTrue(estimator.estimatedWait(-1).isEmpty());
    }

    @Test
    void incorporatesPausesAndExpiresWhenReportsStop() {
        estimator.observe(minute(0), List.of(position("a", 0, 100)));
        estimator.observe(minute(2), List.of(position("a", 2, 96)));
        estimator.observe(minute(4), List.of(position("a", 4, 92)));
        estimator.observe(minute(6), List.of(position("a", 6, 92)));
        assertEquals(Optional.of(Duration.ofSeconds(450)), estimator.estimatedWait(10));

        estimator.observe(minute(13), List.of(position("a", 6, 92)));
        assertTrue(estimator.estimatedWait(10).isEmpty());
    }

    @Test
    void newQueueRunAndChannelsNoLongerQueuingInvalidateSamples() {
        estimator.observe(minute(0), List.of(position("a", 0, 100)));
        estimator.observe(minute(2), List.of(position("a", 2, 96)));
        estimator.observe(minute(4), List.of(position("a", 4, 92)));
        assertTrue(estimator.estimatedWait(42).isPresent());

        estimator.observe(minute(6), List.of(position("a", 6, 120)));
        assertTrue(estimator.estimatedWait(42).isEmpty());
        estimator.observe(minute(8), List.of(position("a", 8, 116)));
        estimator.observe(minute(10), List.of(position("a", 10, 112)));
        assertTrue(estimator.estimatedWait(42).isPresent());
        estimator.observe(minute(10), List.of());
        assertTrue(estimator.estimatedWait(42).isEmpty());
    }

    @Test
    void leavesEtaUnknownWithoutProgressAndIgnoresFutureReports() {
        estimator.observe(minute(0), List.of(position("a", 0, 100)));
        estimator.observe(minute(2), List.of(position("a", 2, 100)));
        estimator.observe(minute(4), List.of(position("a", 4, 100), position("b", 5, 100)));
        assertTrue(estimator.estimatedWait(42).isEmpty());
    }

    @Test
    void usesSlowerMedianWhenTwoSourcesDiffer() {
        estimator.observe(minute(0), List.of(position("a", 0, 60), position("b", 0, 60)));
        estimator.observe(minute(2), List.of(position("a", 2, 50), position("b", 2, 58)));
        estimator.observe(minute(4), List.of(position("a", 4, 40), position("b", 4, 56)));

        assertEquals(Optional.of(Duration.ofMinutes(3)), estimator.estimatedWait(3));
    }

    private static Instant minute(int offset) {
        return NOW.plus(Duration.ofMinutes(offset));
    }

    private static PriorityQueueEstimator.Position position(String id, int minute, int position) {
        return new PriorityQueueEstimator.Position(id, minute(minute), position);
    }
}
