package io.urielbotter.betterwaking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Projects one session per proxy and schedules idle proxies against the first uncovered handoff. */
public final class CoveragePlanner {
    private static final Duration SESSION = Duration.ofHours(8);
    private static final Duration REPORT_HORIZON = Duration.ofHours(24);
    private static final Duration MAX_ESTIMATE = Duration.ofDays(30);

    public enum Phase { DISABLED, OFFLINE, CONNECTING, QUEUED, PLAYING, BLOCKED }

    public enum Tier { REGULAR, PRIORITY, UNKNOWN }

    public record ProxyStatus(String channelId, Phase phase, Tier tier, Instant observedAt,
                              Optional<Duration> remainingQueueEta, Optional<Instant> worldStart,
                              Optional<Instant> connectNotBefore) {}

    public record Assignment(String channelId, Instant connectAt, Instant projectedEntry,
                             Instant coverageDeadline) {}

    public record Plan(Instant generatedAt, List<Assignment> assignments, Optional<Instant> firstProjectedGap) {
        public Plan {
            assignments = List.copyOf(assignments);
        }
    }

    private record Session(Instant start, Instant end) {
        boolean covers(Instant instant) {
            return !start.isAfter(instant) && end.isAfter(instant);
        }
    }

    private record Candidate(ProxyStatus status, Duration eta) {}

    private record Proposal(Candidate candidate, Instant connectAt, Instant entryAt) {
        Assignment assign(Instant deadline) {
            return new Assignment(candidate.status().channelId(), connectAt, entryAt, deadline);
        }
    }

    private final Clock clock;

    public CoveragePlanner(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public Plan plan(List<ProxyStatus> proxies, Map<Tier, Duration> freshTailEtas, int target, Duration margin) {
        if (target < 1) {
            throw new IllegalArgumentException("Coverage target must be positive");
        }
        if (margin.isNegative() || margin.compareTo(SESSION) >= 0) {
            throw new IllegalArgumentException("Coverage margin must be between zero and eight hours (exclusive)");
        }

        Instant now = clock.instant();
        List<Session> sessions = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (ProxyStatus proxy : proxies) {
            projectExistingSession(proxy, now).ifPresent(sessions::add);
            if (proxy.phase() == Phase.OFFLINE && proxy.tier() != Tier.UNKNOWN) {
                Duration eta = freshTailEtas.get(proxy.tier());
                if (eta != null && isUsableEta(eta)) {
                    candidates.add(new Candidate(proxy, eta));
                }
            }
        }

        List<Assignment> assignments = new ArrayList<>();
        Instant cursor = now;
        while (!candidates.isEmpty()) {
            Instant deficit = firstDeficit(sessions, cursor, target);
            Optional<Instant> restoration = projectedRestoration(sessions, deficit, target);
            Optional<Instant> feasible = earliestFeasibleCoverage(sessions, candidates, now, deficit, restoration, target);
            if (feasible.isEmpty() && restoration.isPresent()) {
                cursor = restoration.get();
                continue;
            }
            Instant deadline = feasible.orElse(deficit);
            Instant entryTarget = deadline.minus(margin);
            Proposal proposal = candidates.stream()
                    .map(candidate -> propose(candidate, now, entryTarget))
                    .min(Comparator.comparing(Proposal::entryAt)
                            .thenComparing(option -> option.candidate().eta(), Comparator.reverseOrder())
                            .thenComparing(option -> option.candidate().status().channelId())).orElseThrow();
            assignments.add(proposal.assign(deadline));
            sessions.add(new Session(proposal.entryAt(), proposal.entryAt().plus(SESSION)));
            candidates.remove(proposal.candidate());
        }
        assignments.sort(Comparator.comparing(Assignment::connectAt).thenComparing(Assignment::channelId));
        Optional<Instant> firstProjectedGap = Optional.of(firstDeficit(sessions, now, target))
                .filter(gap -> gap.isBefore(now.plus(REPORT_HORIZON)));
        return new Plan(now, assignments, firstProjectedGap);
    }

    private static Optional<Session> projectExistingSession(ProxyStatus status, Instant now) {
        Optional<Instant> start = switch (status.phase()) {
            case PLAYING -> status.worldStart().filter(worldStart -> !worldStart.isAfter(now));
            case QUEUED, CONNECTING -> status.remainingQueueEta()
                    .filter(CoveragePlanner::isUsableEta)
                    .map(status.observedAt()::plus)
                    .filter(estimatedEntry -> !estimatedEntry.isBefore(now));
            default -> Optional.empty();
        };
        return start.map(time -> new Session(time, time.plus(SESSION)))
                .filter(session -> session.end().isAfter(now));
    }

    static boolean isUsableEta(Duration eta) {
        return !eta.isNegative() && eta.compareTo(MAX_ESTIMATE) <= 0;
    }

    private static Instant firstDeficit(List<Session> sessions, Instant from, int target) {
        if (coverageAt(sessions, from) < target) {
            return from;
        }
        return sessions.stream().map(Session::end).distinct()
                .filter(time -> time.isAfter(from))
                .filter(time -> coverageAt(sessions, time) < target)
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    private static Optional<Instant> projectedRestoration(List<Session> sessions, Instant deficit, int target) {
        return sessions.stream().map(Session::start).distinct()
                .filter(time -> time.isAfter(deficit))
                .filter(time -> coverageAt(sessions, time) >= target)
                .min(Comparator.naturalOrder());
    }

    private static long coverageAt(List<Session> sessions, Instant time) {
        return sessions.stream().filter(session -> session.covers(time)).count();
    }

    private static Optional<Instant> earliestFeasibleCoverage(List<Session> sessions, List<Candidate> candidates,
                                                             Instant now, Instant from, Optional<Instant> until,
                                                             int target) {
        List<Instant> earliestEntries = candidates.stream()
                .map(candidate -> readyAt(candidate, now).plus(candidate.eta())).toList();
        // Keeping a proxy idle lets it align its entry with a slower proxy's arrival.
        List<Instant> checkTimes = new ArrayList<>(earliestEntries);
        checkTimes.add(from);
        sessions.forEach(session -> checkTimes.add(session.start()));
        return checkTimes.stream().distinct()
                .filter(time -> !time.isBefore(from) && until.map(time::isBefore).orElse(true))
                .filter(time -> coverageAt(sessions, time)
                        + earliestEntries.stream().filter(entry -> !entry.isAfter(time)).count() >= target)
                .min(Comparator.naturalOrder());
    }

    private static Proposal propose(Candidate candidate, Instant now, Instant entryTarget) {
        Instant connectAt = later(readyAt(candidate, now), entryTarget.minus(candidate.eta()));
        return new Proposal(candidate, connectAt, connectAt.plus(candidate.eta()));
    }

    private static Instant readyAt(Candidate candidate, Instant now) {
        return later(now, candidate.status().connectNotBefore().orElse(now));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
