package io.urielbotter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

final class JsonFiles {
    private static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .setPrettyPrinting()
            .create();

    private JsonFiles() {}

    static JsonElement read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            JsonElement content = GSON.fromJson(reader, JsonElement.class);
            if (content == null) {
                throw new IllegalArgumentException("Archivo JSON vacío.");
            }
            return content;
        }
    }

    static void write(Path path, JsonElement content) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Path temporaryFile = directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(directory, "urielbotter-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, "urielbotter-", ".tmp");

        try {
            Files.writeString(temporaryFile, GSON.toJson(content) + System.lineSeparator());
            try {
                Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
