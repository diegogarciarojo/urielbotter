package io.urielbotter;

import io.urielbotter.betterwaking.CoveragePlanner.Phase;
import io.urielbotter.betterwaking.CoveragePlanner.ProxyStatus;
import io.urielbotter.betterwaking.CoveragePlanner.Tier;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

final class BetterWakingWire {
    private static final Pattern REQUEST_ID = Pattern.compile("[a-f0-9]{32}");
    // UrielBotter is the authenticated Discord author after relaying any human message.
    private static final Pattern PRIVATE_COMMAND = Pattern.compile(
            "(?isu)^\\S*?betterwaking\\s+(?:poll|plan)(?:\\s.*)?$");

    private BetterWakingWire() {}

    static boolean isPrivateCommand(String content) {
        return PRIVATE_COMMAND.matcher(content.strip()).matches();
    }

    static Optional<Report> parseReport(String content, String channelId, Instant observedAt) {
        String[] fields = content.strip().split(" +", -1);
        if (fields.length != 12 || !fields[0].equals("BW1") || !fields[1].equals("STATE")
                || !REQUEST_ID.matcher(fields[2]).matches()) {
            return Optional.empty();
        }
        try {
            var phase = Phase.valueOf(fields[3]);
            boolean coordinated = switch (fields[4]) {
                case "COORDINATED" -> true;
                case "AUTONOMOUS" -> false;
                default -> throw new IllegalArgumentException("Modo desconocido.");
            };
            var tier = Tier.valueOf(fields[5]);
            Optional<Integer> position = readNumber(fields[6], 0, Integer.MAX_VALUE).map(Long::intValue);
            Optional<Duration> eta = readNumber(fields[7], 0, Duration.ofDays(30).toSeconds())
                    .map(Duration::ofSeconds);
            Optional<Instant> worldStart = readInstant(fields[8]);
            Optional<Instant> connectNotBefore = readInstant(fields[9]);
            int coverage = readNumber(fields[10], 1, 1_000).orElseThrow(IllegalArgumentException::new).intValue();
            boolean dryRun = switch (fields[11]) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("Dry-run inválido.");
            };
            return Optional.of(new Report(fields[2], new ProxyStatus(channelId, phase, tier, observedAt,
                    eta, worldStart, connectNotBefore), position, coordinated, coverage, dryRun));
        } catch (IllegalArgumentException | DateTimeException invalid) {
            return Optional.empty();
        }
    }

    static Optional<Acknowledgement> parseAcknowledgement(String content) {
        String[] fields = content.strip().split(" +", -1);
        if (fields.length == 4 && fields[0].equals("BW1") && fields[1].equals("ACK")
                && REQUEST_ID.matcher(fields[2]).matches() && fields[3].matches("[A-Z_]{1,32}")) {
            return Optional.of(new Acknowledgement(fields[2], fields[3]));
        }
        return Optional.empty();
    }

    private static Optional<Instant> readInstant(String field) {
        return readNumber(field, 0, 253_402_300_799L).map(Instant::ofEpochSecond);
    }

    private static Optional<Long> readNumber(String field, long min, long max) {
        if (field.equals("-")) {
            return Optional.empty();
        }
        if (!field.matches("[0-9]{1,18}")) {
            throw new IllegalArgumentException("Valor numérico inválido.");
        }
        long value = Long.parseLong(field);
        if (value < min || value > max) {
            throw new IllegalArgumentException("Valor numérico fuera de rango.");
        }
        return Optional.of(value);
    }

    record Report(String requestId, ProxyStatus status, Optional<Integer> position,
                  boolean coordinated, int coverage, boolean dryRun) {}

    record Acknowledgement(String requestId, String code) {}
}
