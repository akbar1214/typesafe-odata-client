package io.github.akbarhusain.odata.maven;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(property = "odata.metadataUrl")
    private String metadataUrl;

    @Parameter(property = "odata.metadataFile")
    private File metadataFile;

    @Parameter(property = "odata.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/odata")
    private File outputDirectory;

    @Parameter(property = "odata.basePackage")
    private String basePackage;

    @Parameter
    private List<SchemaMapping> schemaPackages = new ArrayList<>();

    @Parameter(property = "odata.skip", defaultValue = "false")
    private boolean skip;

    @Parameter
    private Properties metadataHeaders;

    @Parameter(property = "odata.forceRegenerate", defaultValue = "false")
    private boolean forceRegenerate;

    @Parameter(property = "odata.generateWithMethods", defaultValue = "false")
    private boolean generateWithMethods;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    private static final String MARKER_FILE = ".odata-generation-marker";
    private static final String MANIFEST_VERSION = "manifest-v3";
    private static final Set<String> HARD_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while", "_"
    );

    private record ManifestEntry(String relativePath, long size, String digest) {}

    private record MarkerManifest(List<ManifestEntry> entries, List<String> legacyPaths, boolean complete) {}

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("OData code generation skipped (odata.skip=true)");
            return;
        }

        validateConfiguration();
        Path outputDir = outputDirectory.toPath().toAbsolutePath().normalize();
        Path metadataPath = null;
        boolean downloaded = metadataFile == null;
        try {
            prepareOutputRoot(outputDir);
            metadataPath = resolveMetadataPath();
            String currentHash = computeMarkerHash(metadataPath);

            if (!forceRegenerate && isUpToDate(outputDir, currentHash)) {
                getLog().info("OData client is up-to-date; skipping generation (metadata and config unchanged). Use odata.forceRegenerate=true to override.");
                project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());
                return;
            }

            MarkerManifest previousManifest = readMarkerManifest(outputDir);
            CsdlModel model = parseMetadata(metadataPath);
            Map<String, String> packages = schemaPackageMap();
            Path staging = Files.createTempDirectory(stagingParent(outputDir), ".odata-generation-");
            try {
                Generator generator = new Generator(staging, packages, basePackage);
                generator.withGenerateWithMethods(generateWithMethods);
                generator.generate(model);

                List<Path> generatedFiles = generator.writtenFiles();
                List<ManifestEntry> entries = manifestEntries(staging, generatedFiles);
                Path publication = Files.createTempDirectory(stagingParent(outputDir), ".odata-generation-publish-");
                try {
                    copyOutputTree(outputDir, publication);
                    deleteStaleFiles(publication, previousManifest, entries);
                    publishGeneratedFiles(staging, publication, generatedFiles);
                    writeMarker(publication, currentHash, entries);
                    publishOutputDirectory(publication, outputDir);
                    publication = null;
                } finally {
                    deleteTree(publication);
                }
            } finally {
                deleteTree(staging);
            }

            project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());
            getLog().info("OData client generated successfully in " + outputDir);
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OData client", e);
        } finally {
            if (downloaded && metadataPath != null) {
                try {
                    Files.deleteIfExists(metadataPath);
                } catch (IOException e) {
                    getLog().warn("Could not delete temporary metadata file: " + metadataPath, e);
                }
            }
        }
    }

    private void validateConfiguration() throws MojoFailureException {
        if (metadataUrl == null && metadataFile == null) {
            throw new MojoFailureException("Either metadataUrl or metadataFile must be specified");
        }
        if (outputDirectory == null) {
            throw new MojoFailureException("outputDirectory must be specified");
        }
        if (metadataFile != null) {
            if (!metadataFile.exists()) {
                throw new MojoFailureException("Metadata file not found: " + metadataFile.getAbsolutePath());
            }
            if (metadataFile.isDirectory()) {
                throw new MojoFailureException("Metadata file is a directory, not a file: " + metadataFile.getAbsolutePath());
            }
            if (!metadataFile.isFile()) {
                throw new MojoFailureException("Metadata source is not a regular file: " + metadataFile.getAbsolutePath());
            }
        } else {
            if (metadataUrl == null || metadataUrl.isBlank()) {
                throw new MojoFailureException("metadataUrl must not be blank");
            }
            validateHttpUri(metadataUrl);
        }
        if (basePackage != null) {
            if (basePackage.isBlank()) {
                throw new MojoFailureException("basePackage must not be blank");
            }
            validatePackageName(basePackage);
        }
        normalizedMappings();
    }

    private Path resolveMetadataPath() throws Exception {
        if (metadataFile != null) {
            if (metadataUrl != null) {
                getLog().warn("Both metadataUrl and metadataFile are configured; using metadataFile ("
                        + metadataFile.getAbsolutePath() + ") and ignoring metadataUrl ("
                        + redactUrlText(metadataUrl) + ")");
            }
            getLog().info("Parsing metadata from file: " + metadataFile.getAbsolutePath());
            return metadataFile.toPath();
        }

        getLog().info("Downloading metadata from: " + redactUrlText(metadataUrl));
        return downloadMetadata(metadataUrl);
    }

    private CsdlModel parseMetadata(Path metadataPath) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = new BufferedInputStream(Files.newInputStream(metadataPath))) {
            return parser.parse(is);
        }
    }

    Path createMetadataTempFile() throws IOException {
        return Files.createTempFile("odata-metadata-", ".xml");
    }

    private Path downloadMetadata(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI initial = validateHttpUri(url);
        URI current = initial;
        boolean forwardConfiguredHeaders = true;

        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/xml")
                    .header("Accept-Encoding", "identity");
            if (forwardConfiguredHeaders && metadataHeaders != null) {
                for (String name : new TreeSet<>(metadataHeaders.stringPropertyNames())) {
                    if (!"Accept-Encoding".equalsIgnoreCase(name)) {
                        requestBuilder.header(name, metadataHeaders.getProperty(name));
                    }
                }
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            Path tempFile = null;

            try (InputStream responseBody = response.body()) {
                int status = response.statusCode();
                boolean isRedirect = status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308;
                if (isRedirect) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null || location.isBlank()) {
                        throw new MojoFailureException("Redirect without Location header: HTTP " + status);
                    }
                    URI next;
                    try {
                        next = resolveRedirectUri(current, location);
                    } catch (IllegalArgumentException e) {
                        throw new MojoFailureException("Invalid redirect URL", e);
                    }
                    validateHttpUri(next);
                    if (!sameOrigin(initial, next) || isDowngrade(current, next)) {
                        forwardConfiguredHeaders = false;
                    }
                    current = next;
                    getLog().info("Following redirect to: " + redactUri(current));
                    continue;
                }

                if (status != 200) {
                    throw new MojoFailureException("Failed to download metadata: HTTP " + status);
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
                    throw new MojoFailureException("Metadata endpoint returned application/json, but this "
                            + "generator only supports CSDL XML. Configure the service to serve XML metadata "
                            + "($metadata with Accept: application/xml).");
                }

                String encoding = response.headers().firstValue("Content-Encoding")
                        .orElse("identity").trim().toLowerCase(Locale.ROOT);
                InputStream decodedBody;
                if (encoding.isEmpty() || encoding.equals("identity")) {
                    decodedBody = responseBody;
                } else if (encoding.equals("gzip")) {
                    decodedBody = new GZIPInputStream(responseBody);
                } else {
                    throw new MojoFailureException("Unsupported metadata Content-Encoding: " + encoding);
                }

                tempFile = createMetadataTempFile();
                try (InputStream input = decodedBody) {
                    Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                }
                throw e;
            }
            return tempFile;
        }

        throw new MojoFailureException("Too many redirects downloading metadata from: " + redactUri(initial));
    }

    static URI resolveRedirectUri(URI current, String location) {
        URI loc = URI.create(location);
        return loc.isAbsolute() ? loc : current.resolve(loc);
    }

    private static URI validateHttpUri(String value) throws MojoFailureException {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("Invalid metadata URL", e);
        }
        return validateHttpUri(uri);
    }

    private static URI validateHttpUri(URI uri) throws MojoFailureException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new MojoFailureException("Metadata URL must use http or https and include a host");
        }
        return uri;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isDowngrade(URI first, URI second) {
        return "https".equalsIgnoreCase(first.getScheme()) && "http".equalsIgnoreCase(second.getScheme());
    }

    private static String redactUri(URI uri) {
        try {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                return "<redacted-uri>";
            }
            StringBuilder value = new StringBuilder(scheme).append("://").append(host);
            if (uri.getPort() >= 0) {
                value.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                value.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                value.append("?redacted");
            }
            if (uri.getRawFragment() != null) {
                value.append("#redacted");
            }
            return value.toString();
        } catch (RuntimeException e) {
            return "<redacted-uri>";
        }
    }

    private static String redactUrlText(String value) {
        try {
            return redactUri(URI.create(value));
        } catch (RuntimeException e) {
            return "<redacted-uri>";
        }
    }

    private String markerFileName() throws Exception {
        // Keyed by source identity + configuration so concurrent executions that share an
        // output directory keep independent stale-file manifests. Consequence: changing
        // the configuration orphans the previous marker (old files are not auto-deleted);
        // see the maven-plugin docs.
        String identity = sourceIdentity() + "\n" + canonicalConfiguration(false);
        return MARKER_FILE + "-" + shortHash(identity);
    }

    private String sourceIdentity() {
        if (metadataFile != null) {
            return metadataFile.toPath().toAbsolutePath().normalize().toString();
        }
        return metadataUrl;
    }

    private String canonicalConfiguration(boolean includeDynamicValues) throws Exception {
        StringBuilder config = new StringBuilder();
        config.append("basePackage=").append(basePackage == null ? "" : basePackage.trim()).append('\n');
        config.append("generateWithMethods=").append(generateWithMethods).append('\n');
        config.append("outputDirectory=").append(outputDirectory == null ? "" : outputDirectory.getAbsolutePath()).append('\n');
        if (includeDynamicValues) {
            config.append("pluginVersion=").append(pluginVersion == null ? "" : pluginVersion).append('\n');
        }
        for (SchemaMapping mapping : normalizedMappings()) {
            config.append("schema=").append(mapping.getNamespace()).append('=')
                    .append(mapping.getPackageName()).append('\n');
        }
        if (metadataHeaders != null) {
            for (String name : new TreeSet<>(metadataHeaders.stringPropertyNames())) {
                config.append("header=").append(name);
                if (includeDynamicValues) {
                    config.append('=').append(metadataHeaders.getProperty(name));
                }
                config.append('\n');
            }
        }
        if (includeDynamicValues) {
            config.append("coreImplementation=").append(coreImplementationFingerprint()).append('\n');
        }
        return config.toString();
    }

    private String computeMarkerHash(Path metadataPath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(hashFileFollowLinks(metadataPath, "MD5").getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(canonicalConfiguration(true).getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest.digest());
    }

    private String coreImplementationFingerprint() throws Exception {
        CodeSource codeSource = Generator.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return resourceFingerprint();
        }
        Path location = Path.of(codeSource.getLocation().toURI());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (Files.isDirectory(location)) {
            List<Path> classes;
            try (Stream<Path> files = Files.walk(location)) {
                classes = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.toString().endsWith(".class"))
                        .sorted(Comparator.comparing(path -> location.relativize(path).toString()))
                        .toList();
            }
            for (Path file : classes) {
                updateDigest(digest, location.relativize(file).toString().replace(File.separatorChar, '/'));
                digest.update(Files.readAllBytes(file));
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                List<JarEntry> entries = new ArrayList<>();
                Enumeration<JarEntry> enumeration = jar.entries();
                while (enumeration.hasMoreElements()) {
                    JarEntry entry = enumeration.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")
                            && entry.getName().startsWith("io/github/akbarhusain/odata/core/")) {
                        entries.add(entry);
                    }
                }
                entries.sort(Comparator.comparing(JarEntry::getName));
                for (JarEntry entry : entries) {
                    updateDigest(digest, entry.getName());
                    try (InputStream input = jar.getInputStream(entry)) {
                        digest.update(input.readAllBytes());
                    }
                }
            }
        }
        String version = Generator.class.getPackage().getImplementationVersion();
        if (version != null) {
            updateDigest(digest, version);
        }
        return bytesToHex(digest.digest());
    }

    private static String resourceFingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Generator.class.getResourceAsStream("Generator.class")) {
            if (input != null) {
                digest.update(input.readAllBytes());
            } else {
                digest.update(Generator.class.getName().getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytesToHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hashFileFollowLinks(Path path, String algorithm) throws Exception {
        return hashFile(path, algorithm, true);
    }

    private static String hashFile(Path path, String algorithm) throws Exception {
        return hashFile(path, algorithm, false);
    }

    private static String hashFile(Path path, String algorithm, boolean followLinks) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        LinkOption[] options = followLinks
                ? new LinkOption[0]
                : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
        try (InputStream is = new BufferedInputStream(Files.newInputStream(path, options))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return bytesToHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    private boolean isUpToDate(Path outputDir, String currentHash) throws Exception {
        Path marker = outputDir.resolve(markerFileName());
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String markerText = Files.readString(marker);
        String[] lines = markerText.split("\\R", 2);
        if (lines.length == 0 || !currentHash.equals(lines[0].trim())) {
            return false;
        }
        MarkerManifest manifest = readMarkerManifest(outputDir);
        if (!manifest.complete() || manifest.entries().isEmpty()) {
            return false;
        }
        for (ManifestEntry entry : manifest.entries()) {
            Path file;
            try {
                file = resolveManifestPath(outputDir, entry.relativePath());
            } catch (IOException e) {
                return false;
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                return false;
            }
            if (Files.isSymbolicLink(file) || !attributes.isRegularFile()
                    || attributes.size() != entry.size()) {
                return false;
            }
            if (!hashFile(file, "SHA-256").equals(entry.digest())) {
                return false;
            }
        }
        return true;
    }

    private void writeMarker(Path outputDir, String hash, List<ManifestEntry> entries) throws Exception {
        StringBuilder content = new StringBuilder(hash).append('\n').append(MANIFEST_VERSION).append('\n')
                .append(entries.size()).append('\n');
        for (ManifestEntry entry : entries) {
            content.append(entry.relativePath()).append('\t').append(entry.size()).append('\t')
                    .append(entry.digest()).append('\n');
        }
        Path marker = outputDir.resolve(markerFileName());
        Path temporary = Files.createTempFile(outputDir, ".odata-generation-marker-tmp-", ".tmp");
        try {
            Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            replaceMarker(temporary, marker, outputDir);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void replaceMarker(Path temporary, Path marker, Path outputDir) throws IOException {
        try {
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Path backup = null;
            try {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    backup = Files.createTempFile(outputDir, ".odata-generation-marker-old-", ".tmp");
                    Files.delete(backup);
                    Files.move(marker, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                if (backup != null && !Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(backup, marker, StandardCopyOption.REPLACE_EXISTING);
                }
                throw failure;
            } finally {
                if (backup != null) {
                    Files.deleteIfExists(backup);
                }
            }
        }
    }

    private static boolean isUsableMarker(Path marker) {
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker);
    }

    private MarkerManifest readMarkerManifest(Path outputDir) throws Exception {
        Path marker = outputDir.resolve(markerFileName());
        if (!isUsableMarker(marker)) {
            Path legacy = outputDir.resolve(MARKER_FILE + "-" + shortHash(sourceIdentity()));
            if (isUsableMarker(legacy)) {
                marker = legacy;
            } else {
                return new MarkerManifest(List.of(), List.of(), false);
            }
        }
        String[] lines = Files.readString(marker).split("\\R", -1);
        if (lines.length < 3) {
            return new MarkerManifest(List.of(), List.of(), false);
        }
        if (!MANIFEST_VERSION.equals(lines[1])) {
            List<String> legacy = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (isSafeManifestPath(lines[i])) {
                    legacy.add(lines[i]);
                }
            }
            return new MarkerManifest(List.of(), legacy, false);
        }

        int expectedEntries;
        try {
            expectedEntries = Integer.parseInt(lines[2]);
        } catch (RuntimeException e) {
            return new MarkerManifest(List.of(), List.of(), false);
        }
        if (expectedEntries < 0) {
            return new MarkerManifest(List.of(), List.of(), false);
        }
        List<ManifestEntry> entries = new ArrayList<>();
        boolean complete = true;
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] parts = lines[i].split("\\t", -1);
            if (parts.length != 3) {
                complete = false;
                continue;
            }
            try {
                long size = Long.parseLong(parts[1]);
                if (size < 0 || !parts[2].matches("[0-9a-fA-F]{64}")) {
                    complete = false;
                    continue;
                }
                if (!isSafeManifestPath(parts[0])) {
                    complete = false;
                    continue;
                }
                entries.add(new ManifestEntry(parts[0], size, parts[2].toLowerCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                complete = false;
            }
        }
        if (entries.size() != expectedEntries) {
            complete = false;
        }
        return new MarkerManifest(List.copyOf(entries), List.of(), complete);
    }

    private List<ManifestEntry> manifestEntries(Path staging, List<Path> generatedFiles) throws Exception {
        List<ManifestEntry> entries = new ArrayList<>();
        for (Path file : generatedFiles) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated source is not a regular file: " + file);
            }
            Path relative = staging.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
            if (relative.isAbsolute() || relative.normalize().startsWith("..")
                    || relative.toString().isEmpty()) {
                throw new IOException("Generated source escapes staging directory: " + file);
            }
            String path = relative.toString().replace(File.separatorChar, '/');
            entries.add(new ManifestEntry(path, Files.size(file), hashFile(file, "SHA-256")));
        }
        entries.sort(Comparator.comparing(ManifestEntry::relativePath));
        return entries;
    }

    private void publishGeneratedFiles(Path staging, Path outputDir, List<Path> generatedFiles) throws Exception {
        for (Path source : generatedFiles) {
            Path relative = staging.toAbsolutePath().normalize()
                    .relativize(source.toAbsolutePath().normalize());
            if (relative.isAbsolute() || relative.normalize().startsWith("..")
                    || relative.toString().isEmpty()) {
                throw new IOException("Generated source escapes staging directory: " + source);
            }
            Path target = outputDir.resolve(relative).normalize();
            ensureSafePath(outputDir, relative);
            createSafeDirectories(outputDir, target.getParent());
            if (Files.isSymbolicLink(target)) {
                throw new IOException("Refusing to replace symlink: " + target);
            }
            publishFile(source, target, outputDir);
        }
    }

    protected void publishFile(Path source, Path target, Path outputDir) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".odata-generated-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            replaceFile(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copyOutputTree(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Output directory is a symlink: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (path.equals(source)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Output path escapes publication directory: " + path);
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Refusing symlink in output directory: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IOException("Unsupported output path: " + path);
                }
            }
        }
    }

    private void publishOutputDirectory(Path publication, Path outputDir) throws IOException {
        Path backup = Files.createTempDirectory(stagingParent(outputDir), ".odata-generation-old-");
        Files.delete(backup);
        boolean publicationMoved = false;
        try {
            moveDirectory(outputDir, backup, false);
            try {
                moveDirectory(publication, outputDir, true);
                publicationMoved = true;
            } catch (IOException failure) {
                try {
                    moveDirectory(backup, outputDir, true);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                    getLog().error("Could not restore previous generated output from " + backup, restoreFailure);
                }
                throw failure;
            }
        } finally {
            if (publicationMoved) {
                deleteTree(backup);
            }
        }
    }

    private void moveDirectory(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private void replaceFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteStaleFiles(Path outputDir, MarkerManifest previous, List<ManifestEntry> current)
            throws Exception {
        if (previous.legacyPaths().isEmpty() && previous.entries().isEmpty()) {
            return;
        }
        Set<String> currentRelative = new HashSet<>();
        Map<String, Path> currentByCase = new HashMap<>();
        for (ManifestEntry entry : current) {
            currentRelative.add(entry.relativePath());
            currentByCase.putIfAbsent(entry.relativePath().toLowerCase(Locale.ROOT),
                    outputDir.resolve(entry.relativePath()).normalize());
        }
        List<String> previousPaths = new ArrayList<>();
        previousPaths.addAll(previous.legacyPaths());
        for (ManifestEntry entry : previous.entries()) {
            previousPaths.add(entry.relativePath());
        }
        for (String relative : previousPaths) {
            if (relative == null || relative.isBlank() || !relative.endsWith(".java")) {
                continue;
            }
            Path stale = resolveManifestPath(outputDir, relative);
            if (currentRelative.contains(relative)) {
                continue;
            }
            Path replacement = currentByCase.get(relative.toLowerCase(Locale.ROOT));
            if (replacement != null && Files.exists(stale, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(replacement, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(stale, replacement)) {
                continue;
            }
            if (Files.isSymbolicLink(stale)) {
                throw new IOException("Refusing to delete symlink: " + relative);
            }
            if (!Files.exists(stale, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(stale, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to delete non-file generated path: " + relative);
            }
            if (Files.deleteIfExists(stale)) {
                getLog().info("Deleted stale generated file: " + relative);
            }
        }
    }

    private static boolean isSafeManifestPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.contains("\\") || !value.endsWith(".java")) {
            return false;
        }
        Path relative = Path.of(value);
        return !relative.isAbsolute() && !relative.normalize().startsWith("..");
    }

    private Path resolveManifestPath(Path outputDir, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0
                || relative.contains("\\")) {
            throw new IOException("Invalid generated file manifest path");
        }
        Path path = Path.of(relative);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IOException("Generated file manifest path escapes output directory: " + relative);
        }
        Path normalized = path.normalize();
        Path target = outputDir.resolve(normalized).normalize();
        if (!target.startsWith(outputDir)) {
            throw new IOException("Generated file manifest path escapes output directory: " + relative);
        }
        ensureSafePath(outputDir, normalized);
        return target;
    }

    private void ensureSafePath(Path outputDir, Path relative) throws IOException {
        if (Files.isSymbolicLink(outputDir)) {
            throw new IOException("Output directory is a symlink: " + outputDir);
        }
        Path current = outputDir;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing symlink path: " + current);
            }
        }
    }

    private void createSafeDirectories(Path outputDir, Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        Path relative = outputDir.relativize(directory);
        Path current = outputDir;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing symlink directory: " + current);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated parent is not a directory: " + current);
            }
        }
    }

    private void prepareOutputRoot(Path outputDir) throws IOException {
        if (Files.isSymbolicLink(outputDir)) {
            throw new IOException("Output directory is a symlink: " + outputDir);
        }
        if (Files.exists(outputDir, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output path is not a directory: " + outputDir);
        }
        Files.createDirectories(outputDir);
        if (Files.isSymbolicLink(outputDir) || !Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output path is not a safe directory: " + outputDir);
        }
    }

    private Path stagingParent(Path outputDir) {
        Path parent = outputDir.getParent();
        return parent == null ? Path.of(".") : parent;
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                try {
                    // Files.delete removes a symlink itself (never its target), so symlinks
                    // can be deleted too; a single failure must not abandon the remaining tree.
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    getLog().warn("Could not remove " + path + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            getLog().warn("Could not remove generation staging directory: " + root, e);
        }
    }

    private Map<String, String> schemaPackageMap() throws MojoFailureException {
        Map<String, String> packages = new LinkedHashMap<>();
        for (SchemaMapping mapping : normalizedMappings()) {
            packages.put(mapping.getNamespace(), mapping.getPackageName());
        }
        return packages;
    }

    private List<SchemaMapping> normalizedMappings() throws MojoFailureException {
        if (schemaPackages == null || schemaPackages.isEmpty()) {
            return List.of();
        }
        List<SchemaMapping> mappings = new ArrayList<>();
        Set<String> namespaces = new HashSet<>();
        for (SchemaMapping mapping : schemaPackages) {
            if (mapping == null) {
                throw new MojoFailureException("schemaPackages must not contain null mappings");
            }
            String namespace = mapping.getNamespace() == null ? "" : mapping.getNamespace().trim();
            String packageName = mapping.getPackageName() == null ? "" : mapping.getPackageName().trim();
            if (namespace.isBlank()) {
                throw new MojoFailureException("Schema mapping namespace must not be blank");
            }
            validateNamespace(namespace);
            if (packageName.isBlank()) {
                throw new MojoFailureException("Schema mapping packageName must not be blank for namespace " + namespace);
            }
            validatePackageName(packageName);
            if (!namespaces.add(namespace)) {
                throw new MojoFailureException("Duplicate schema mapping for namespace: " + namespace);
            }
            mappings.add(new SchemaMapping(namespace, packageName));
        }
        mappings.sort(Comparator.comparing(SchemaMapping::getNamespace)
                .thenComparing(SchemaMapping::getPackageName));
        return mappings;
    }

    private void validateNamespace(String namespace) throws MojoFailureException {
        if (namespace.contains("..") || namespace.startsWith(".") || namespace.endsWith(".")) {
            throw new MojoFailureException("Malformed schema namespace: " + namespace);
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '/' || c == '\\' || c == ':') {
                throw new MojoFailureException("Malformed schema namespace: " + namespace);
            }
        }
    }

    private void validatePackageName(String packageName) throws MojoFailureException {
        if (packageName.isBlank() || packageName.contains("/") || packageName.contains("\\")
                || packageName.contains(":") || packageName.startsWith(".") || packageName.endsWith(".")) {
            throw new MojoFailureException("Malformed schema packageName: " + packageName);
        }
        for (String part : packageName.split("\\.", -1)) {
            if (part.isEmpty() || HARD_KEYWORDS.contains(part)
                    || !Character.isJavaIdentifierStart(part.charAt(0))) {
                throw new MojoFailureException("Malformed schema packageName: " + packageName);
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    throw new MojoFailureException("Malformed schema packageName: " + packageName);
                }
            }
        }
    }
}
