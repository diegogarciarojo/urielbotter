package io.urielbotter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

record CoordinatorConfig(boolean enabled, String commandPrefix, int targetCoverage, Duration margin,
                         Duration pollInterval, Map<String, String> proxyBotIds) {
    CoordinatorConfig {
        if (!commandPrefix.matches("[\\x21-\\x7e]{1,16}") || targetCoverage < 1 || targetCoverage > 1_000
                || margin.isNegative() || margin.compareTo(Duration.ofHours(1)) > 0
                || pollInterval.compareTo(Duration.ofMinutes(1)) < 0
                || pollInterval.compareTo(Duration.ofMinutes(4)) > 0
                || (enabled && proxyBotIds.isEmpty())
                || !proxyBotIds.entrySet().stream().allMatch(binding ->
                        BotConfig.validId(binding.getKey()) && BotConfig.validId(binding.getValue()))) {
            throw new IllegalArgumentException("Configuración de BetterWaking inválida.");
        }
        proxyBotIds = Map.copyOf(proxyBotIds);
    }

    static CoordinatorConfig load(Path path) throws IOException {
        JsonElement document;
        try {
            document = JsonFiles.read(path);
        } catch (NoSuchFileException absent) {
            document = new JsonObject();
        }
        JsonObject object = document.getAsJsonObject();
        Map<String, String> botIds = new LinkedHashMap<>();
        JsonElement bindings = object.get("proxyBotIds");
        if (bindings != null) {
            for (var binding : bindings.getAsJsonObject().entrySet()) {
                botIds.put(binding.getKey(), requireString(binding.getValue()));
            }
        }
        JsonElement enabled = object.get("enabled");
        JsonElement prefix = object.get("commandPrefix");
        return new CoordinatorConfig(
                enabled != null && requireBoolean(enabled),
                prefix == null ? "." : requireString(prefix),
                intValue(object, "targetCoverage", 1),
                Duration.ofSeconds(intValue(object, "marginSeconds", 300)),
                Duration.ofSeconds(intValue(object, "pollSeconds", 120)),
                botIds);
    }

    private static String requireString(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Se esperaba un texto.");
        }
        return value.getAsString();
    }

    private static boolean requireBoolean(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Se esperaba un booleano.");
        }
        return value.getAsBoolean();
    }

    private static int intValue(JsonObject object, String field, int defaultValue) {
        JsonElement value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Se esperaba un entero.");
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
