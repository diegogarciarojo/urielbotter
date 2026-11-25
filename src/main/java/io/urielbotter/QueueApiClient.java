package io.urielbotter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QueueApiClient {
    private static final Logger LOG = LoggerFactory.getLogger(QueueApiClient.class);
    private static final URI BASE_URI = URI.create("https://api.2b2t.vc");
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUEUE_REFRESH = Duration.ofMinutes(1);
    private static final Duration QUEUE_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final Duration EQUATION_REFRESH = Duration.ofHours(6);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final Clock clock;
    private final Transport transport;
    private Snapshot cached;
    private Equation equation;
    private Instant nextAttempt = Instant.MIN;
    private Duration backoff = QUEUE_REFRESH;

    QueueApiClient(Clock clock) {
        this(clock, httpTransport());
    }

    QueueApiClient(Clock clock, Transport transport) {
        this.clock = Objects.requireNonNull(clock);
        this.transport = Objects.requireNonNull(transport);
    }

    synchronized Optional<Snapshot> snapshot() {
        Instant now = clock.instant();
        if (now.isBefore(nextAttempt)) {
            return Optional.ofNullable(cached).filter(value -> isFresh(value.observedAt(), now));
        }

        try {
            JsonObject queue = get("/queue");
            int regular = length(queue, "regular");
            int prio = length(queue, "prio");
            Instant observedAt = OffsetDateTime.parse(primitive(queue, "time").getAsString()).toInstant();
            if (!isFresh(observedAt, clock.instant())) {
                fail("muestra vencida o adelantada", Optional.empty());
                return Optional.empty();
            }

            if (equation == null || !clock.instant().isBefore(equation.expiresAt())) {
                JsonObject coefficients = get("/queue/eta-equation");
                equation = new Equation(positiveNumber(coefficients, "factor"),
                        positiveNumber(coefficients, "pow"), clock.instant().plus(EQUATION_REFRESH));
            }

            cached = new Snapshot(observedAt, regular, prio, equation.factor(), equation.pow());
            Instant refreshedAt = clock.instant();
            nextAttempt = refreshedAt.plus(QUEUE_REFRESH);
            backoff = QUEUE_REFRESH;
            return Optional.of(cached).filter(value -> isFresh(value.observedAt(), refreshedAt));
        } catch (HttpFailure e) {
            fail("HTTP " + e.statusCode, e.retryAfter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("consulta interrumpida", Optional.empty());
        } catch (IOException | JsonParseException | IllegalArgumentException | DateTimeException | ArithmeticException e) {
            fail("respuesta no utilizable (" + e.getClass().getSimpleName() + ")", Optional.empty());
        }
        return Optional.empty();
    }

    private void fail(String reason, Optional<String> retryAfter) {
        cached = null;
        Instant now = clock.instant();
        nextAttempt = now.plus(backoff);
        Optional<Instant> serverRetry = retryAfter.flatMap(header -> retryAt(header, now));
        if (serverRetry.isPresent() && serverRetry.get().isAfter(nextAttempt)) {
            nextAttempt = serverRetry.get();
        }
        LOG.warn("BetterWaking API de cola: {}. Próxima consulta a partir de {}.", reason, nextAttempt);
        Duration nextBackoff = backoff.multipliedBy(2);
        backoff = nextBackoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : nextBackoff;
    }

    private JsonObject get(String path) throws IOException, InterruptedException {
        Response response = transport.get(path);
        if (response.statusCode() != 200) {
            throw new HttpFailure(response.statusCode(), response.retryAfter());
        }
        JsonObject object = JSON.fromJson(response.body(), JsonObject.class);
        if (object == null) {
            throw new IllegalArgumentException("Respuesta JSON vacía");
        }
        return object;
    }

    private static boolean isFresh(Instant observedAt, Instant now) {
        return !observedAt.isBefore(now.minus(QUEUE_MAX_AGE)) && !observedAt.isAfter(now.plus(CLOCK_SKEW));
    }

    private static Optional<Instant> retryAt(String header, Instant now) {
        String value = header.strip();
        if (value.matches("[0-9]+")) {
            try {
                return Optional.of(now.plusSeconds(Long.parseLong(value)));
            } catch (DateTimeException | NumberFormatException | ArithmeticException e) {
                return Optional.of(Instant.MAX);
            }
        }
        try {
            return Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static JsonPrimitive primitive(JsonObject object, String name) {
        if (!(object.get(name) instanceof JsonPrimitive value)) {
            throw new IllegalArgumentException("Falta el valor " + name);
        }
        return value;
    }

    private static int length(JsonObject object, String name) {
        JsonPrimitive value = primitive(object, name);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Longitud inválida");
        }
        int result = value.getAsBigDecimal().intValueExact();
        if (result < 0) {
            throw new IllegalArgumentException("Longitud negativa");
        }
        return result;
    }

    private static double positiveNumber(JsonObject object, String name) {
        JsonPrimitive value = primitive(object, name);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Coeficiente inválido");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result <= 0) {
            throw new IllegalArgumentException("Coeficiente inválido");
        }
        return result;
    }

    private static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        return path -> {
            HttpRequest request = HttpRequest.newBuilder(BASE_URI.resolve(path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "UrielBotter/BetterWaking")
                    .GET()
                    .build();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(HTTP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body(), response.headers().firstValue("Retry-After"));
            } catch (TimeoutException e) {
                throw new HttpTimeoutException("La consulta de cola superó el tiempo de espera");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException failure) {
                    throw failure;
                }
                throw new IOException("Falló el transporte HTTP", e.getCause());
            } finally {
                pending.cancel(true);
            }
        };
    }

    record Snapshot(Instant observedAt, int regular, int prio, double factor, double pow) {
        Optional<Duration> estimatedWait(boolean priority) {
            if (priority) {
                // The published equation was fitted on regular players. An empty priority lane gives a lower bound.
                return prio == 0 ? Optional.of(Duration.ZERO) : Optional.empty();
            }
            return regularEta((long) regular + 1);
        }

        Optional<Duration> regularEta(long position) {
            if (position < 0) {
                return Optional.empty();
            }
            double seconds = Math.ceil(factor * Math.pow(position, pow));
            if (!Double.isFinite(seconds) || seconds >= Long.MAX_VALUE) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds((long) seconds));
        }
    }

    @FunctionalInterface
    interface Transport {
        Response get(String path) throws IOException, InterruptedException;
    }

    record Response(int statusCode, String body, Optional<String> retryAfter) {}

    private record Equation(double factor, double pow, Instant expiresAt) {}

    private static final class HttpFailure extends IOException {
        private final int statusCode;
        private final Optional<String> retryAfter;

        private HttpFailure(int statusCode, Optional<String> retryAfter) {
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }
    }
}
