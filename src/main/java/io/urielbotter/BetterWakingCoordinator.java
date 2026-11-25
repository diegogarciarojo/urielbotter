package io.urielbotter;

import io.urielbotter.BetterWakingWire.Report;
import io.urielbotter.betterwaking.CoveragePlanner;
import io.urielbotter.betterwaking.CoveragePlanner.Assignment;
import io.urielbotter.betterwaking.CoveragePlanner.Phase;
import io.urielbotter.betterwaking.CoveragePlanner.ProxyStatus;
import io.urielbotter.betterwaking.CoveragePlanner.Tier;
import io.urielbotter.betterwaking.PriorityQueueEstimator;
import io.urielbotter.betterwaking.PriorityQueueEstimator.Position;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BetterWakingCoordinator implements AutoCloseable {
    static final Duration RESPONSE_WINDOW = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PLAN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PLAN_LEASE = Duration.ofMinutes(6);
    private static final Logger LOG = LoggerFactory.getLogger(BetterWakingCoordinator.class);

    private final CoordinatorConfig config;
    private final Clock clock;
    private final Discord discord;
    private final Supplier<Optional<QueueApiClient.Snapshot>> queueFeed;
    private final CoveragePlanner planner;
    private final PriorityQueueEstimator priorityEstimator = new PriorityQueueEstimator();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "betterwaking-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private Round round;
    private boolean closed;

    BetterWakingCoordinator(CoordinatorConfig config, Clock clock, Discord discord,
                            Supplier<Optional<QueueApiClient.Snapshot>> queueFeed) {
        this.config = config;
        this.clock = clock;
        this.discord = discord;
        this.queueFeed = queueFeed;
        this.planner = new CoveragePlanner(clock);
    }

    void start() {
        scheduler.scheduleAtFixedRate(() -> runSafely(() -> {
            openRound();
            scheduler.schedule(() -> runSafely(this::finishRound), RESPONSE_WINDOW.toSeconds(), TimeUnit.SECONDS);
        }), 0, config.pollInterval().toSeconds(), TimeUnit.SECONDS);
    }

    void openRound() {
        Instant now = clock.instant();
        Map<String, String> requests = new HashMap<>();
        for (String channel : discord.targets()) {
            if (config.proxyBotIds().containsKey(channel)) {
                requests.put(channel, UUID.randomUUID().toString().replace("-", ""));
            }
        }
        Round opened = new Round(now.plus(RESPONSE_WINDOW), Map.copyOf(requests));
        synchronized (this) {
            if (closed) {
                return;
            }
            round = opened;
        }
        requests.forEach((channel, request) -> discord.send(channel, command("poll " + request),
                now.plus(POLL_TIMEOUT), () -> isCurrent(opened)));
    }

    synchronized void receive(String channel, String author, boolean isBot, boolean isWebhook,
                              List<String> descriptions) {
        if (closed || round == null || !isBot || isWebhook || !author.equals(config.proxyBotIds().get(channel))) {
            return;
        }
        String request = round.requests.get(channel);
        if (request == null || !discord.targets().contains(channel)) {
            return;
        }
        Instant now = clock.instant();
        if (!round.collectionClosed && now.isBefore(round.replyBy) && !round.reports.containsKey(channel)) {
            descriptions.stream().flatMap(text -> BetterWakingWire.parseReport(text, channel, now).stream())
                    .filter(report -> request.equals(report.requestId())).findFirst()
                    .ifPresent(report -> round.reports.put(channel, report));
        }
        if (round.pendingAcks.contains(channel)) {
            descriptions.stream().flatMap(text -> BetterWakingWire.parseAcknowledgement(text).stream())
                    .filter(ack -> request.equals(ack.requestId())).findFirst().ifPresent(ack -> {
                        round.pendingAcks.remove(channel);
                        LOG.debug("BetterWaking {} confirmó la orden con {}", channel, ack.code());
                    });
        }
    }

    void finishRound() {
        Round collected;
        List<Report> reports;
        synchronized (this) {
            if (closed || round == null || round.collectionClosed) {
                return;
            }
            collected = round;
            collected.collectionClosed = true;
            Set<String> targets = discord.targets();
            reports = collected.reports.values().stream()
                    .filter(report -> targets.contains(report.status().channelId())).toList();
        }

        priorityEstimator.observe(clock.instant(), reports.stream()
                .filter(report -> report.status().phase() == Phase.QUEUED && report.status().tier() == Tier.PRIORITY)
                .flatMap(report -> report.position().stream().map(position -> new Position(
                        report.status().channelId(), report.status().observedAt(), position)))
                .toList());
        if (reports.stream().noneMatch(BetterWakingCoordinator::canCoordinate)) {
            return;
        }
        Optional<QueueApiClient.Snapshot> snapshot = queueFeed.get();
        if (snapshot.isEmpty()) {
            return;
        }
        QueueApiClient.Snapshot queue = snapshot.get();
        Map<Tier, Duration> tailEtas = new EnumMap<>(Tier.class);
        queue.estimatedWait(false).ifPresent(eta -> tailEtas.put(Tier.REGULAR, eta));
        queue.estimatedWait(true).or(() -> queue.prio() < Integer.MAX_VALUE
                        ? priorityEstimator.estimatedWait(queue.prio() + 1) : Optional.empty())
                .ifPresent(eta -> tailEtas.put(Tier.PRIORITY, eta));
        List<ProxyStatus> live = reports.stream()
                .filter(report -> report.status().phase() != Phase.OFFLINE || (canCoordinate(report) && !report.dryRun()))
                .map(report -> refineEstimate(report, queue, tailEtas)).toList();
        int target = reports.stream().filter(report -> canCoordinate(report) && !report.dryRun())
                .mapToInt(Report::coverage).reduce(config.targetCoverage(), Math::max);
        var plan = planner.plan(live, tailEtas, target, config.margin());
        Map<String, Instant> starts = plan.assignments().stream()
                .collect(Collectors.toMap(Assignment::channelId, Assignment::connectAt));
        LOG.debug("BetterWaking: objetivo={}, estados={}, conexiones planeadas={}, déficit proyectado={}",
                target, reports.size(), starts.size(), plan.firstProjectedGap().orElse(null));

        Instant now = clock.instant();
        Instant leaseEnd = now.plus(PLAN_LEASE);
        for (Report report : reports) {
            if (canCoordinate(report) && (report.status().phase() != Phase.OFFLINE
                    || tailEtas.containsKey(report.status().tier()))) {
                String channel = report.status().channelId();
                Instant start = report.status().phase() == Phase.OFFLINE && report.dryRun()
                        ? previewStart(live, report.status(), tailEtas, Math.max(target, report.coverage()))
                        : starts.get(channel);
                String startField = start != null && start.isBefore(leaseEnd)
                        ? Long.toString(start.getEpochSecond()) : "-";
                sendPlan(collected, report, now, leaseEnd, startField);
            }
        }
    }

    private Instant previewStart(List<ProxyStatus> live, ProxyStatus preview, Map<Tier, Duration> tailEtas, int target) {
        List<ProxyStatus> scenario = new ArrayList<>(live);
        scenario.removeIf(status -> status.phase() == Phase.OFFLINE);
        scenario.add(preview);
        return planner.plan(scenario, tailEtas, target, config.margin()).assignments().stream()
                .filter(assignment -> assignment.channelId().equals(preview.channelId())).findFirst()
                .map(Assignment::connectAt).orElse(null);
    }

    private ProxyStatus refineEstimate(Report report, QueueApiClient.Snapshot queue, Map<Tier, Duration> tailEtas) {
        ProxyStatus status = report.status();
        Optional<Duration> eta = switch (status.phase()) {
            case QUEUED -> switch (status.tier()) {
                case REGULAR -> report.position().flatMap(queue::regularEta).or(status::remainingQueueEta);
                case PRIORITY -> report.position().flatMap(priorityEstimator::estimatedWait);
                case UNKNOWN -> status.remainingQueueEta();
            };
            case CONNECTING -> status.remainingQueueEta().or(() -> Optional.ofNullable(tailEtas.get(status.tier())));
            default -> status.remainingQueueEta();
        };
        return new ProxyStatus(status.channelId(), status.phase(), status.tier(), status.observedAt(),
                eta, status.worldStart(), status.connectNotBefore());
    }

    private void sendPlan(Round collected, Report report, Instant now, Instant leaseEnd, String startField) {
        String channel = report.status().channelId();
        synchronized (this) {
            if (!isCurrent(collected)) {
                return;
            }
            collected.pendingAcks.add(channel);
        }
        String payload = "plan " + report.requestId() + " " + leaseEnd.getEpochSecond() + " " + startField;
        discord.send(channel, command(payload), now.plus(PLAN_TIMEOUT), () -> isCurrent(collected));
    }

    private static boolean canCoordinate(Report report) {
        return report.coordinated() && report.status().phase() != Phase.DISABLED && report.status().phase() != Phase.BLOCKED;
    }

    private synchronized boolean isCurrent(Round expected) {
        return !closed && round == expected;
    }

    private String command(String body) {
        return config.commandPrefix() + "betterWaking " + body;
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOG.warn("Falló un ciclo de BetterWaking. Se reintentará en el próximo sondeo.", failure);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    interface Discord {
        Set<String> targets();

        void send(String channel, String message, Instant deadline, BooleanSupplier current);
    }

    private static final class Round {
        private final Instant replyBy;
        private final Map<String, String> requests;
        private final Map<String, Report> reports = new HashMap<>();
        private final Set<String> pendingAcks = new HashSet<>();
        private boolean collectionClosed;

        private Round(Instant replyBy, Map<String, String> requests) {
            this.replyBy = replyBy;
            this.requests = requests;
        }
    }
}
