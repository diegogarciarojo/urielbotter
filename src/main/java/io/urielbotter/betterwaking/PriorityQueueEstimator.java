package io.urielbotter.betterwaking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Learns priority queue pace from observed position changes, without using the regular queue fit. */
public final class PriorityQueueEstimator {
    private static final Duration HISTORY = Duration.ofMinutes(30);
    private static final Duration MAX_SAMPLE_AGE = Duration.ofMinutes(6);
    private static final Duration MIN_OBSERVATION_SPAN = Duration.ofMinutes(4);
    private static final int MIN_SAMPLES = 3;
    private static final int MAX_SAMPLES = 32;

    public record Position(String channelId, Instant observedAt, int position) {}

    private final Map<String, Deque<Position>> historyByChannel = new HashMap<>();
    private Optional<Double> secondsPerPosition = Optional.empty();

    /** Supplies the current reports for all managed proxies presently in the priority queue. */
    public void observe(Instant now, List<Position> positions) {
        Set<String> currentChannels = new HashSet<>();
        for (Position position : positions) {
            if (position.position() < 0 || position.observedAt().isAfter(now)
                    || position.observedAt().isBefore(now.minus(MAX_SAMPLE_AGE))) {
                continue;
            }
            currentChannels.add(position.channelId());
            Deque<Position> history = historyByChannel.computeIfAbsent(position.channelId(), ignored -> new ArrayDeque<>());
            if (!history.isEmpty()) {
                Position previous = history.getLast();
                if (!position.observedAt().isAfter(previous.observedAt())) {
                    continue;
                }
                if (position.position() > previous.position()
                        || previous.observedAt().plus(MAX_SAMPLE_AGE).isBefore(position.observedAt())) {
                    history.clear();
                }
            }
            history.addLast(position);
            while (history.size() > MAX_SAMPLES) {
                history.removeFirst();
            }
        }
        historyByChannel.keySet().retainAll(currentChannels);

        List<Double> rates = new ArrayList<>();
        for (Deque<Position> history : historyByChannel.values()) {
            while (!history.isEmpty() && history.getFirst().observedAt().isBefore(now.minus(HISTORY))) {
                history.removeFirst();
            }
            pace(history).ifPresent(rates::add);
        }
        rates.sort(Comparator.naturalOrder());
        secondsPerPosition = rates.isEmpty() ? Optional.empty() : Optional.of(rates.get(rates.size() / 2));
    }

    public Optional<Duration> estimatedWait(int position) {
        if (position < 0) {
            return Optional.empty();
        }
        return secondsPerPosition.map(seconds -> Duration.ofSeconds((long) Math.ceil(seconds * position)))
                .filter(CoveragePlanner::isUsableEta);
    }

    private static Optional<Double> pace(Deque<Position> history) {
        if (history.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        Position first = history.getFirst();
        Position last = history.getLast();
        Duration observed = Duration.between(first.observedAt(), last.observedAt());
        int advancement = first.position() - last.position();
        if (observed.compareTo(MIN_OBSERVATION_SPAN) < 0 || advancement == 0) {
            return Optional.empty();
        }
        return Optional.of(observed.toMillis() / (1000.0 * advancement));
    }
}
