package io.urielbotter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseUpdaterTest {
    @TempDir
    Path directory;
    private final AtomicInteger downloads = new AtomicInteger();
    private HttpServer server;
    private Path installed;
    private byte[] original;
    private ReleaseUpdater updater;

    @BeforeEach
    void setUp() throws Exception {
        original = jar("1.0.2");
        installed = Files.write(directory.resolve("urielbotter.jar"), original);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        updater = new ReleaseUpdater(installed, "1.0.2", base.resolve("/latest"), base.resolve("/download/"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void installsVerifiedReleaseAndLeavesConfigInPlace() throws Exception {
        byte[] candidate = jar("1.0.10");
        publish("v1.0.10", candidate, sha256(candidate), false);
        Path config = Files.writeString(directory.resolve("config.json"), "{\"example\":true}");

        assertEquals(Optional.of("v1.0.10"), updater.installLatest());
        assertArrayEquals(candidate, Files.readAllBytes(installed));
        assertEquals("{\"example\":true}", Files.readString(config));
        assertEquals(1, downloads.get());
        assertNoStagedJar();
    }

    @Test
    void skipsAnOlderReleaseWithoutDownloading() throws Exception {
        byte[] candidate = jar("1.0.1");
        publish("v1.0.1", candidate, sha256(candidate), false);

        assertTrue(updater.installLatest().isEmpty());
        assertEquals(0, downloads.get());
        assertArrayEquals(original, Files.readAllBytes(installed));
    }

    @Test
    void skipsAPrereleaseWithoutDownloading() throws Exception {
        byte[] candidate = jar("1.1.0");
        publish("v1.1.0", candidate, sha256(candidate), true);

        assertTrue(updater.installLatest().isEmpty());
        assertEquals(0, downloads.get());
    }

    @Test
    void keepsInstalledJarWhenHashDoesNotMatch() throws Exception {
        publish("v1.0.3", jar("1.0.3"), sha256(original), false);

        assertThrows(IOException.class, updater::installLatest);
        assertEquals(1, downloads.get());
        assertArrayEquals(original, Files.readAllBytes(installed));
        assertNoStagedJar();
    }

    @Test
    void keepsInstalledJarWhenManifestDoesNotMatchRelease() throws Exception {
        byte[] candidate = jar("1.0.4");
        publish("v1.0.3", candidate, sha256(candidate), false);

        assertThrows(IOException.class, updater::installLatest);
        assertArrayEquals(original, Files.readAllBytes(installed));
        assertNoStagedJar();
    }

    private void publish(String tag, byte[] candidate, String hash, boolean prerelease) {
        byte[] metadata = """
                {"tag_name":"%s","draft":false,"prerelease":%s,
                "assets":[{"name":"urielbotter.jar","size":%s,"digest":"sha256:%s"}]}
                """.formatted(tag, prerelease, candidate.length, hash).getBytes(StandardCharsets.UTF_8);
        server.createContext("/latest", request -> respond(request, metadata));
        server.createContext("/download/" + tag + "/urielbotter.jar", request -> {
            downloads.incrementAndGet();
            respond(request, candidate);
        });
    }

    private void assertNoStagedJar() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void respond(HttpExchange request, byte[] body) throws IOException {
        try (request) {
            request.sendResponseHeaders(200, body.length);
            request.getResponseBody().write(body);
        }
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static byte[] jar(String version) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "io.urielbotter.Main");
        manifest.getMainAttributes().putValue("Implementation-Version", version);
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes, manifest);
                var mainClass = Main.class.getResourceAsStream("Main.class")) {
            jar.putNextEntry(new JarEntry("io/urielbotter/Main.class"));
            mainClass.transferTo(jar);
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
