package io.urielbotter.betterwaking;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.urielbotter.betterwaking.CoveragePlanner.Phase;
import static io.urielbotter.betterwaking.CoveragePlanner.Tier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoveragePlannerTest {
    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    private static final ZonedDateTime LOCAL_NOW = ZonedDateTime.of(2026, 11, 24, 16, 0, 0, 0, MEXICO);
    private static final Instant NOW = LOCAL_NOW.toInstant();
    private static final Duration MARGIN = Duration.ofMinutes(15);

    private final CoveragePlanner planner = new CoveragePlanner(Clock.fixed(NOW, MEXICO));

    @Test
    void stagesThreeProxiesUsingDifferentLaneEtasInMexicoCity() {
        CoveragePlanner.Plan plan = planner.plan(
                List.of(playing("a", NOW.minus(Duration.ofHours(6))), offline("b", Tier.PRIORITY),
                        offline("c", Tier.REGULAR)),
                Map.of(Tier.PRIORITY, Duration.ofMinutes(30), Tier.REGULAR, Duration.ofHours(4)), 1, MARGIN);

        assertEquals(List.of("b", "c"), plan.assignments().stream().map(CoveragePlanner.Assignment::channelId).toList());
        assertEquals("2026-11-24T17:15", localTime(plan.assignments().get(0).connectAt()));
        assertEquals("2026-11-24T17:45", localTime(plan.assignments().get(0).projectedEntry()));
        assertEquals("2026-11-24T21:30", localTime(plan.assignments().get(1).connectAt()));
        assertEquals("2026-11-25T01:30", localTime(plan.assignments().get(1).projectedEntry()));
        assertEquals(List.of(LOCAL_NOW.plusHours(2).toInstant(), LOCAL_NOW.plusHours(9).plusMinutes(45).toInstant()),
                plan.assignments().stream().map(CoveragePlanner.Assignment::coverageDeadline).toList());
        assertEquals(Optional.of(LOCAL_NOW.plusHours(17).plusMinutes(30).toInstant()), plan.firstProjectedGap());
    }

    @Test
    void skipsGapThatExistingQueueWillHealAndDoesNotRequeueIt() {
        Instant fiveMinutesAgo = NOW.minus(Duration.ofMinutes(5));
        CoveragePlanner.ProxyStatus queued = status("queued", Phase.QUEUED, Tier.REGULAR, fiveMinutesAgo,
                Duration.ofMinutes(20), null, null);
        CoveragePlanner.Plan plan = planner.plan(List.of(offline("idle", Tier.REGULAR), queued),
                Map.of(Tier.REGULAR, Duration.ofHours(1)), 1, MARGIN);

        assertEquals(List.of(new CoveragePlanner.Assignment("idle", NOW.plus(Duration.ofHours(7)),
                NOW.plus(Duration.ofHours(8)), NOW.plus(Duration.ofHours(8)).plus(Duration.ofMinutes(15)))),
                plan.assignments());
        assertEquals(Optional.of(NOW), plan.firstProjectedGap());
    }

    @Test
    void coldStartStartsOneAccountAndKeepsTheSecondForTheNextHandoff() {
        CoveragePlanner.Plan plan = planner.plan(List.of(offline("fast", Tier.PRIORITY), offline("slow", Tier.REGULAR)),
                Map.of(Tier.PRIORITY, Duration.ofHours(1), Tier.REGULAR, Duration.ofHours(3)), 1, MARGIN);

        assertEquals(NOW, plan.assignments().get(0).connectAt());
        assertEquals("fast", plan.assignments().get(0).channelId());
        assertEquals(NOW.plus(Duration.ofHours(5).plusMinutes(45)), plan.assignments().get(1).connectAt());
    }

    @Test
    void delaysFastAccountToOverlapVeryLongQueueWhenTargetRequiresBoth() {
        CoveragePlanner.Plan plan = planner.plan(List.of(offline("fast", Tier.PRIORITY), offline("slow", Tier.REGULAR)),
                Map.of(Tier.PRIORITY, Duration.ofHours(1), Tier.REGULAR, Duration.ofHours(30)), 2, MARGIN);

        assertEquals("slow", plan.assignments().get(0).channelId());
        assertEquals(NOW, plan.assignments().get(0).connectAt());
        assertEquals(NOW.plus(Duration.ofHours(30)), plan.assignments().get(0).projectedEntry());
        assertEquals("fast", plan.assignments().get(1).channelId());
        assertEquals(NOW.plus(Duration.ofHours(28).plusMinutes(45)), plan.assignments().get(1).connectAt());
        assertEquals(Optional.of(NOW), plan.firstProjectedGap());
    }

    @Test
    void replacesTwoSimultaneousExpirationsWhenTargetIsTwo() {
        Instant simultaneousStart = NOW.minus(Duration.ofHours(6));
        List<CoveragePlanner.ProxyStatus> statuses = List.of(playing("old-a", simultaneousStart),
                playing("old-b", simultaneousStart), offline("new-b", Tier.REGULAR), offline("new-a", Tier.REGULAR));
        CoveragePlanner.Plan plan = planner.plan(statuses, Map.of(Tier.REGULAR, Duration.ofHours(1)), 2, MARGIN);

        assertEquals(List.of("new-a", "new-b"), plan.assignments().stream().map(CoveragePlanner.Assignment::channelId).toList());
        assertTrue(plan.assignments().stream().allMatch(assignment ->
                assignment.connectAt().equals(NOW.plus(Duration.ofMinutes(45)))));
        assertEquals(Optional.of(NOW.plus(Duration.ofHours(9).plusMinutes(45))), plan.firstProjectedGap());
    }

    @Test
    void treatsTouchingSessionIntervalsAsContinuous() {
        CoveragePlanner.ProxyStatus sameInstantHandoff = status("new", Phase.QUEUED, Tier.REGULAR, NOW,
                Duration.ofHours(2), null, null);
        CoveragePlanner.Plan plan = planner.plan(List.of(playing("old", NOW.minus(Duration.ofHours(6))), sameInstantHandoff),
                Map.of(), 1, Duration.ZERO);

        assertTrue(plan.assignments().isEmpty());
        assertEquals(Optional.of(NOW.plus(Duration.ofHours(10))), plan.firstProjectedGap());
    }

    @Test
    void retainsCooldownAndRecalculatesOnNewEtaAndClock() {
        CoveragePlanner.ProxyStatus cooling = status("idle", Phase.OFFLINE, Tier.REGULAR, NOW,
                null, null, NOW.plus(Duration.ofMinutes(20)));
        List<CoveragePlanner.ProxyStatus> statuses = List.of(playing("old", NOW.minus(Duration.ofHours(6))), cooling);
        CoveragePlanner.Plan first = planner.plan(statuses, Map.of(Tier.REGULAR, Duration.ofMinutes(30)), 1, MARGIN);
        Instant fiveMinutesLater = NOW.plus(Duration.ofMinutes(5));
        CoveragePlanner laterPlanner = new CoveragePlanner(Clock.fixed(fiveMinutesLater, MEXICO));
        CoveragePlanner.Plan second = laterPlanner.plan(statuses,
                Map.of(Tier.REGULAR, Duration.ofHours(3)), 1, MARGIN);

        assertEquals(NOW.plus(Duration.ofMinutes(75)), first.assignments().getFirst().connectAt());
        assertEquals(fiveMinutesLater, second.generatedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(20)), second.assignments().getFirst().connectAt());
        assertEquals(Optional.of(NOW.plus(Duration.ofHours(2))), second.firstProjectedGap());
    }

    @Test
    void expiredSessionAndUnknownQueueEtaDoNotCreatePhantomCoverage() {
        List<CoveragePlanner.ProxyStatus> statuses = List.of(playing("expired", NOW.minus(Duration.ofHours(8))),
                status("unknown-queue", Phase.QUEUED, Tier.PRIORITY, NOW, null, null, null),
                status("overdue-queue", Phase.QUEUED, Tier.REGULAR, NOW.minus(Duration.ofMinutes(2)),
                        Duration.ofMinutes(1), null, null), offline("idle", Tier.REGULAR));
        CoveragePlanner.Plan plan = planner.plan(statuses, Map.of(Tier.REGULAR, Duration.ofHours(1)), 1, MARGIN);

        assertEquals(List.of(new CoveragePlanner.Assignment("idle", NOW, NOW.plus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(1)))), plan.assignments());
        assertEquals(Optional.of(NOW), plan.firstProjectedGap());
    }

    @Test
    void missingEtaAndIneligiblePhasesReceiveNoAssignment() {
        List<CoveragePlanner.ProxyStatus> statuses = List.of(offline("no-eta", Tier.PRIORITY),
                offline("unknown-tier", Tier.UNKNOWN), status("banned", Phase.BLOCKED, Tier.REGULAR, NOW, null, null, null),
                status("disabled", Phase.DISABLED, Tier.REGULAR, NOW, null, null, null),
                status("connecting", Phase.CONNECTING, Tier.REGULAR, NOW, null, null, null));
        CoveragePlanner.Plan plan = planner.plan(statuses, Map.of(Tier.REGULAR, Duration.ofHours(1)), 2, MARGIN);

        assertTrue(plan.assignments().isEmpty());
        assertEquals(Optional.of(NOW), plan.firstProjectedGap());
    }

    @Test
    void outOfRangeEstimateDoesNotPreventValidLanePlanning() {
        CoveragePlanner.Plan plan = planner.plan(List.of(offline("prio", Tier.PRIORITY), offline("regular", Tier.REGULAR)),
                Map.of(Tier.PRIORITY, Duration.ofSeconds(Long.MAX_VALUE), Tier.REGULAR, Duration.ofHours(1)), 1, MARGIN);

        assertEquals(List.of("regular"), plan.assignments().stream().map(CoveragePlanner.Assignment::channelId).toList());
        assertEquals(NOW, plan.assignments().getFirst().connectAt());
    }

    @Test
    void attemptsBestEffortWhenConfiguredTargetIsUnattainable() {
        CoveragePlanner.Plan plan = planner.plan(List.of(offline("idle", Tier.REGULAR)),
                Map.of(Tier.REGULAR, Duration.ofHours(1)), 4, MARGIN);

        assertEquals(1, plan.assignments().size());
        assertEquals(NOW, plan.assignments().getFirst().connectAt());
        assertEquals(Optional.of(NOW), plan.firstProjectedGap());
    }

    @Test
    void rejectsParametersThatCannotDefineCoverage() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), Map.of(), 0, MARGIN));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), Map.of(), 1, Duration.ofHours(8)));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), Map.of(), 1, Duration.ofSeconds(-1)));
    }

    private static CoveragePlanner.ProxyStatus playing(String channel, Instant worldStart) {
        return status(channel, Phase.PLAYING, Tier.REGULAR, NOW, null, worldStart, null);
    }

    private static CoveragePlanner.ProxyStatus offline(String channel, Tier tier) {
        return status(channel, Phase.OFFLINE, tier, NOW, null, null, null);
    }

    private static CoveragePlanner.ProxyStatus status(String channel, Phase phase, Tier tier, Instant observedAt,
                                                       Duration eta, Instant worldStart, Instant connectNotBefore) {
        return new CoveragePlanner.ProxyStatus(channel, phase, tier, observedAt, Optional.ofNullable(eta),
                Optional.ofNullable(worldStart), Optional.ofNullable(connectNotBefore));
    }

    private static String localTime(Instant instant) {
        return instant.atZone(MEXICO).toLocalDateTime().toString();
    }
}
