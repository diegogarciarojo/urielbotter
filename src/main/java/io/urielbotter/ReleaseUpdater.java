package io.urielbotter;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

final class ReleaseUpdater {
    static final int RESTART_EXIT_CODE = 75;
    private static final String REPO = "diegogarciarojo/urielbotter";
    private static final String ASSET_NAME = "urielbotter.jar";
    private static final int MAX_RELEASE_BYTES = 1024 * 1024;
    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private final Path jar;
    private final Version installedVersion;
    private final URI latestReleaseUrl;
    private final URI downloadsUrl;

    static ReleaseUpdater fromLauncher() {
        if (!Boolean.getBoolean("urielbotter.launcher")) {
            return null;
        }
        var source = Main.class.getProtectionDomain().getCodeSource();
        String version = Main.class.getPackage().getImplementationVersion();
        if (source == null || Version.parse(version) == null) {
            return null;
        }
        try {
            Path jar = Path.of(source.getLocation().toURI()).toAbsolutePath();
            return Files.isRegularFile(jar)
                    ? new ReleaseUpdater(jar, version, URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"),
                            URI.create("https://github.com/" + REPO + "/releases/download/"))
                    : null;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    ReleaseUpdater(Path jar, String installedVersion, URI latestReleaseUrl, URI downloadsUrl) {
        this.jar = jar;
        this.installedVersion = Version.parse(installedVersion);
        this.latestReleaseUrl = latestReleaseUrl;
        this.downloadsUrl = downloadsUrl;
    }

    Optional<String> installLatest() throws IOException {
        Release release = fetchLatest();
        String tag = release.tag_name();
        Version version = tag != null && tag.startsWith("v") ? Version.parse(tag.substring(1)) : null;
        if (release.draft() || release.prerelease() || version == null || version.compareTo(installedVersion) <= 0) {
            return Optional.empty();
        }
        Asset asset = (release.assets() == null ? List.<Asset>of() : release.assets()).stream()
                .filter(candidate -> candidate != null && ASSET_NAME.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new IOException("Falta " + ASSET_NAME + " en la release " + tag + "."));
        if (asset.size() <= 0 || asset.size() > MAX_JAR_BYTES
                || asset.digest() == null || !asset.digest().matches("sha256:[a-f0-9]{64}")) {
            throw new IOException("La release " + tag + " no tiene un tamaño y SHA-256 válidos para el JAR.");
        }

        Path candidate = Files.createTempFile(jar.getParent(), ".urielbotter-update-", ".tmp");
        try {
            MessageDigest sha256 = sha256();
            try (var output = new DigestOutputStream(Files.newOutputStream(candidate), sha256)) {
                download(downloadsUrl.resolve(tag + "/" + ASSET_NAME), "application/octet-stream", output, asset.size());
            }
            String digest = "sha256:" + HexFormat.of().formatHex(sha256.digest());
            if (Files.size(candidate) != asset.size() || !digest.equals(asset.digest())) {
                throw new IOException("El JAR descargado no coincide con el tamaño o el SHA-256 de GitHub.");
            }
            verifyJar(candidate, tag.substring(1));
            Files.move(candidate, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(candidate);
        }
        return Optional.of(tag);
    }

    private Release fetchLatest() throws IOException {
        var body = new ByteArrayOutputStream();
        download(latestReleaseUrl, "application/vnd.github+json", body, MAX_RELEASE_BYTES);
        try {
            Release release = GSON.fromJson(body.toString(StandardCharsets.UTF_8), Release.class);
            if (release == null || release.tag_name() == null) {
                throw new IOException("La respuesta de GitHub no contiene una release.");
            }
            return release;
        } catch (JsonParseException e) {
            throw new IOException("No se pudo leer la release de GitHub.", e);
        }
    }

    private static void download(URI url, String accept, OutputStream output, long limit) throws IOException {
        var connection = (HttpURLConnection) url.toURL().openConnection();
        connection.setRequestProperty("User-Agent", "UrielBotter");
        connection.setRequestProperty("Accept", accept);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub respondió HTTP " + status + ".");
            }
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                long remaining = limit;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    remaining -= count;
                    if (remaining < 0) {
                        throw new IOException("La descarga supera el tamaño esperado.");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void verifyJar(Path candidate, String version) throws IOException {
        try (var file = new JarFile(candidate.toFile())) {
            var manifest = file.getManifest();
            String mainClass = Main.class.getName();
            if (manifest == null
                    || !mainClass.equals(manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS))
                    || !version.equals(manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION))
                    || file.getJarEntry(mainClass.replace('.', '/') + ".class") == null) {
                throw new IOException("El JAR descargado no corresponde a UrielBotter " + version + ".");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Release(String tag_name, boolean draft, boolean prerelease, List<Asset> assets) {}

    private record Asset(String name, long size, String digest) {}

    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static final String NUMBER = "(0|[1-9][0-9]{0,8})";
        private static final Pattern FORMAT = Pattern.compile(NUMBER + "\\." + NUMBER + "\\." + NUMBER);
        private static final Comparator<Version> ORDER = Comparator.comparingInt(Version::major)
                .thenComparingInt(Version::minor).thenComparingInt(Version::patch);

        static Version parse(String value) {
            if (value == null) {
                return null;
            }
            var matcher = FORMAT.matcher(value);
            return matcher.matches() ? new Version(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))) : null;
        }

        @Override
        public int compareTo(Version other) {
            return ORDER.compare(this, other);
        }
    }
}
