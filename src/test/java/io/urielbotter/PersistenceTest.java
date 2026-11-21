package io.urielbotter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceTest {
    @TempDir
    Path directory;

    @Test
    void writesAndLoadsPrivateConfiguration() throws IOException {
        Path path = directory.resolve("config.json");
        BotConfig config = new BotConfig("sample-token", "123", "456");

        config.save(path);

        assertEquals(config, BotConfig.load(path));
        assertFalse(config.toString().contains(config.token()));
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        }
    }

    @Test
    void rejectsInvalidIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new BotConfig("sample-token", "123", "123"));
        assertFalse(BotConfig.validId("0"));
        assertFalse(BotConfig.validId("18446744073709551616"));
        assertFalse(BotConfig.validId("123.0"));
        assertTrue(BotConfig.validId("18446744073709551615"));
    }

    @Test
    void createsEmptyBlacklistAndPersistsUpdates() throws IOException {
        Path path = directory.resolve("ignored_channels.json");
        Blacklist blacklist = Blacklist.open(path);
        assertEquals("[]", Files.readString(path).strip());

        assertTrue(blacklist.add("111111111111111111"));
        assertFalse(blacklist.add("111111111111111111"));
        assertTrue(Blacklist.open(path).contains("111111111111111111"));
        assertTrue(blacklist.remove("111111111111111111"));
        assertFalse(blacklist.remove("111111111111111111"));
        assertFalse(Blacklist.open(path).contains("111111111111111111"));
    }

    @Test
    void keepsExactIdsFromThePythonBlacklist() throws IOException {
        Path path = directory.resolve("ignored_channels.json");
        Files.writeString(path, "[123456789123456789, 18446744073709551615]");

        Blacklist blacklist = Blacklist.open(path);
        assertTrue(blacklist.contains("123456789123456789"));
        assertTrue(blacklist.add("222"));
        Blacklist reloaded = Blacklist.open(path);
        assertTrue(reloaded.contains("18446744073709551615"));
        assertTrue(reloaded.contains("222"));
    }

    @Test
    void keepsThePreviousStateWhenAnUpdateCannotBeWritten() throws IOException {
        Path path = directory.resolve("ignored_channels.json");
        Blacklist blacklist = Blacklist.open(path);
        assertTrue(blacklist.add("111"));
        Files.delete(path);
        Files.createDirectory(path);

        assertThrows(IOException.class, () -> blacklist.add("222"));
        assertTrue(blacklist.contains("111"));
        assertFalse(blacklist.contains("222"));
    }

    @Test
    void preservesInvalidBlacklistForRecovery() throws IOException {
        Path path = directory.resolve("ignored_channels.json");
        String invalid = "[true]";
        Files.writeString(path, invalid);

        assertThrows(IllegalArgumentException.class, () -> Blacklist.open(path));
        assertEquals(invalid, Files.readString(path));
    }
}
