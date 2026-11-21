package io.urielbotter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

record BotConfig(String token, String commandChannelId, String targetCategoryId) {
    BotConfig {
        if (!validToken(token) || !validId(commandChannelId) || !validId(targetCategoryId)
                || commandChannelId.equals(targetCategoryId)) {
            throw new IllegalArgumentException("Token o IDs de Discord inválidos.");
        }
    }

    static BotConfig load(Path path) throws IOException {
        JsonElement document = JsonFiles.read(path);
        if (!document.isJsonObject()) {
            throw new IllegalArgumentException("Se esperaba un objeto JSON.");
        }
        JsonObject config = document.getAsJsonObject();
        return new BotConfig(
                string(config, "token"),
                string(config, "commandChannelId"),
                string(config, "targetCategoryId"));
    }

    void save(Path path) throws IOException {
        JsonObject config = new JsonObject();
        config.addProperty("token", token);
        config.addProperty("commandChannelId", commandChannelId);
        config.addProperty("targetCategoryId", targetCategoryId);
        JsonFiles.write(path, config);
    }

    static boolean validId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) {
            return false;
        }
        try {
            Long.parseUnsignedLong(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.chars().noneMatch(Character::isWhitespace);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    @Override
    public String toString() {
        return "BotConfig[commandChannelId=" + commandChannelId + ", targetCategoryId=" + targetCategoryId + "]";
    }
}
