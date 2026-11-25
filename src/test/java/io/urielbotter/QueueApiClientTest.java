package io.urielbotter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueueApiClientTest {
    private static final String QUEUE = "/queue";
    private static final String EQUATION = "/queue/eta-equation";
    private static final Instant START = Instant.parse("2026-11-24T22:00:00Z");
    private static final String COEFFICIENTS = "{\"factor\":2.0,\"pow\":1.0}";

    @Test
    void refreshesOneSharedQueueEveryMinuteAndTheCoefficientsEverySixHours() {
        MutableClock clock = new MutableClock();
        List<String> calls = new ArrayList<>();
        QueueApiClient client = new QueueApiClient(clock, path -> {
            calls.add(path);
            return ok(path.equals(QUEUE) ? queue(clock.instant(), 100, 4) : COEFFICIENTS);
        });

        QueueApiClient.Snapshot first = client.snapshot().orElseThrow();
        assertEquals(Optional.of(Duration.ofSeconds(202)), first.estimatedWait(false));
        assertEquals(Optional.of(Duration.ofSeconds(42)), first.regularEta(21));
        assertTrue(first.estimatedWait(true).isEmpty());
        clock.advance(Duration.ofSeconds(59));
        assertSame(first, client.snapshot().orElseThrow());
        assertEquals(List.of(QUEUE, EQUATION), calls);

        clock.advance(Duration.ofSeconds(1));
        assertEquals(clock.instant(), client.snapshot().orElseThrow().observedAt());
        assertEquals(List.of(QUEUE, EQUATION, QUEUE), calls);
        clock.advance(Duration.ofHours(6).minusMinutes(1));
        client.snapshot().orElseThrow();
        assertEquals(List.of(QUEUE, EQUATION, QUEUE, QUEUE, EQUATION), calls);
    }

    @Test
    void preservesOffsetAndFractionalTimeAndUsesZeroAsTheEmptyPriorityLowerBound() {
        MutableClock clock = new MutableClock();
        QueueApiClient client = new QueueApiClient(clock, path -> ok(path.equals(QUEUE)
                ? "{\"time\":\"2026-11-24T15:59:59.352182-06:00\",\"prio\":0,\"regular\":0}"
                : COEFFICIENTS));

        QueueApiClient.Snapshot snapshot = client.snapshot().orElseThrow();
        assertEquals(Instant.parse("2026-11-24T21:59:59.352182Z"), snapshot.observedAt());
        assertEquals(Optional.of(Duration.ZERO), snapshot.estimatedWait(true));
        assertEquals(Optional.of(Duration.ofSeconds(2)), snapshot.estimatedWait(false));
        assertTrue(snapshot.regularEta(-1).isEmpty());
    }

    @Test
    void rejectsAnExpiredCachedObservationBeforeTheNextRequestIsDue() {
        MutableClock clock = new MutableClock();
        List<String> calls = new ArrayList<>();
        QueueApiClient client = new QueueApiClient(clock, path -> {
            calls.add(path);
            return ok(path.equals(QUEUE) ? queue(START.minusSeconds(290), 100, 1) : COEFFICIENTS);
        });

        client.snapshot().orElseThrow();
        clock.advance(Duration.ofSeconds(11));
        assertTrue(client.snapshot().isEmpty());
        assertEquals(List.of(QUEUE, EQUATION), calls);
        clock.advance(Duration.ofSeconds(49));
        assertTrue(client.snapshot().isEmpty());
        assertEquals(List.of(QUEUE, EQUATION, QUEUE), calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"120", "Tue, 24 Nov 2026 22:02:00 GMT"})
    void honorsRetryAfterAsSecondsOrAnHttpDate(String header) {
        MutableClock clock = new MutableClock();
        List<String> calls = new ArrayList<>();
        QueueApiClient client = new QueueApiClient(clock, path -> {
            calls.add(path);
            if (calls.size() == 1) {
                return new QueueApiClient.Response(429, "", Optional.of(header));
            }
            return ok(path.equals(QUEUE) ? queue(clock.instant(), 100, 1) : COEFFICIENTS);
        });

        assertTrue(client.snapshot().isEmpty());
        clock.advance(Duration.ofSeconds(119));
        assertTrue(client.snapshot().isEmpty());
        assertEquals(List.of(QUEUE), calls);
        clock.advance(Duration.ofSeconds(1));
        client.snapshot().orElseThrow();
        assertEquals(List.of(QUEUE, QUEUE, EQUATION), calls);
    }

    @Test
    void noDataAndTransportErrorsDropTheLastPlanInputAndBackOff() {
        MutableClock clock = new MutableClock();
        List<String> calls = new ArrayList<>();
        QueueApiClient client = new QueueApiClient(clock, path -> {
            calls.add(path);
            return switch (calls.size()) {
                case 3 -> new QueueApiClient.Response(204, "", Optional.empty());
                case 4 -> throw new IOException("timeout simulado");
                default -> ok(path.equals(QUEUE) ? queue(clock.instant(), 100, 1) : COEFFICIENTS);
            };
        });

        client.snapshot().orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        assertTrue(client.snapshot().isEmpty());
        clock.advance(Duration.ofMinutes(1));
        assertTrue(client.snapshot().isEmpty());
        clock.advance(Duration.ofSeconds(119));
        assertTrue(client.snapshot().isEmpty());
        assertEquals(List.of(QUEUE, EQUATION, QUEUE, QUEUE), calls);
        clock.advance(Duration.ofSeconds(1));
        client.snapshot().orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"time\":\"2026-11-24T22:00:00Z\",\"prio\":0}",
            "{\"time\":\"2026-11-24T22:00:00Z\",\"prio\":0,\"regular\":1.5}",
            "{\"time\":\"2026-11-24T22:00:00Z\",\"prio\":0,\"regular\":\"1\"}",
            "{\"time\":\"2026-11-24T22:00:00Z\",\"prio\":0,\"regular\":-1}",
            "{\"time\":\"2026-11-24T22:01:01Z\",\"prio\":0,\"regular\":1}"
    })
    void treatsMalformedOrFutureDataAsUnavailable(String body) {
        List<String> calls = new ArrayList<>();
        QueueApiClient client = new QueueApiClient(new MutableClock(), path -> {
            calls.add(path);
            return ok(body);
        });

        assertTrue(client.snapshot().isEmpty());
        assertEquals(List.of(QUEUE), calls);
    }

    private static String queue(Instant observedAt, int regular, int prio) {
        return "{\"time\":\"%s\",\"regular\":%d,\"prio\":%d}".formatted(observedAt, regular, prio);
    }

    private static QueueApiClient.Response ok(String body) {
        return new QueueApiClient.Response(200, body, Optional.empty());
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
