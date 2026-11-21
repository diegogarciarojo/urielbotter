package io.urielbotter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

final class Blacklist {
    private final Path path;
    private Set<String> channels;

    private Blacklist(Path path, Set<String> channels) {
        this.path = path;
        this.channels = channels;
    }

    static Blacklist open(Path path) throws IOException {
        Set<String> channels = new LinkedHashSet<>();
        Blacklist blacklist = new Blacklist(path, channels);
        try {
            JsonElement document = JsonFiles.read(path);
            if (!document.isJsonArray()) {
                throw new IllegalArgumentException("Se esperaba una lista de IDs.");
            }
            for (JsonElement value : document.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !BotConfig.validId(value.getAsString())) {
                    throw new IllegalArgumentException("La lista contiene un ID de canal inválido.");
                }
                channels.add(value.getAsString());
            }
        } catch (NoSuchFileException e) {
            blacklist.save(channels);
        }
        return blacklist;
    }

    synchronized boolean contains(String channelId) {
        return channels.contains(channelId);
    }

    synchronized boolean add(String channelId) throws IOException {
        Set<String> updated = new LinkedHashSet<>(channels);
        if (!updated.add(channelId)) {
            return false;
        }
        save(updated);
        channels = updated;
        return true;
    }

    synchronized boolean remove(String channelId) throws IOException {
        Set<String> updated = new LinkedHashSet<>(channels);
        if (!updated.remove(channelId)) {
            return false;
        }
        save(updated);
        channels = updated;
        return true;
    }

    private void save(Set<String> updated) throws IOException {
        JsonArray document = new JsonArray();
        updated.forEach(document::add);
        JsonFiles.write(path, document);
    }
}
