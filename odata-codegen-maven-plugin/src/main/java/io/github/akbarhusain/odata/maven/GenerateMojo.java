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
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
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

    /**
     * Extra HTTP headers sent when downloading {@code metadataUrl} — e.g.
     * {@code <metadataHeaders><Authorization>Bearer ...</Authorization></metadataHeaders>}
     * for non-public metadata endpoints.
     */
    @Parameter
    private java.util.Properties metadataHeaders;

    @Parameter(property = "odata.forceRegenerate", defaultValue = "false")
    private boolean forceRegenerate;

    @Parameter(property = "odata.generateWithMethods", defaultValue = "false")
    private boolean generateWithMethods;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    private static final String MARKER_FILE = ".odata-generation-marker";

    /**
     * Marker file keyed by the metadata SOURCE identity (URL or file path): multiple
     * executions sharing one outputDirectory would otherwise invalidate each other's
     * single marker and fully regenerate on every build. The source identity is stable
     * across metadata content changes (so the stale-file manifest is found when the
     * metadata changes) and distinct per execution without relying on Maven expression
     * injection.
     */
    private String markerFileName() throws Exception {
        String source = metadataUrl != null ? metadataUrl : metadataFile.getAbsolutePath();
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        String key = bytesToHex(digest.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return MARKER_FILE + "-" + key.substring(0, 12);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("OData code generation skipped (odata.skip=true)");
            return;
        }

        if (metadataUrl == null && metadataFile == null) {
            throw new MojoExecutionException("Either metadataUrl or metadataFile must be specified");
        }

        try {
            Path outputDir = outputDirectory.toPath();
            Files.createDirectories(outputDir);

            Path metadataPath = resolveMetadataPath();
            String currentHash = computeMarkerHash(metadataPath);

            if (!forceRegenerate && isUpToDate(outputDir, currentHash)) {
                getLog().info("OData client is up-to-date; skipping generation (metadata and config unchanged). Use odata.forceRegenerate=true to override.");
                project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());
                return;
            }

            // Manifest of the previous run (may be empty) — used to delete files that
            // disappeared from the metadata or moved after a package remap
            java.util.List<String> previousFiles = readMarkerManifest(outputDir);

            CsdlModel model = parseMetadata(metadataPath);

            Map<String, String> packages = new HashMap<>();
            for (SchemaMapping mapping : schemaPackages) {
                packages.put(mapping.getNamespace(), mapping.getPackageName());
            }

            Generator generator = new Generator(outputDir, packages, basePackage);
            generator.withGenerateWithMethods(generateWithMethods);
            generator.generate(model);

            writeMarker(outputDir, currentHash, generator.writtenFiles());
            deleteStaleFiles(outputDir, previousFiles, generator.writtenFiles());

            // Add generated sources to Maven project
            project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());

            getLog().info("OData client generated successfully in " + outputDir);
        } catch (MojoExecutionException | MojoFailureException e) {
            // deliberate failures (missing file, HTTP error, too many redirects) keep
            // their own message and failure semantics instead of being re-wrapped
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OData client", e);
        }
    }

    private Path resolveMetadataPath() throws Exception {
        if (metadataFile != null) {
            if (!metadataFile.exists()) {
                throw new MojoFailureException("Metadata file not found: " + metadataFile.getAbsolutePath());
            }
            getLog().info("Parsing metadata from file: " + metadataFile.getAbsolutePath());
            return metadataFile.toPath();
        }

        getLog().info("Downloading metadata from: " + metadataUrl);
        return downloadMetadata(metadataUrl);
    }

    private CsdlModel parseMetadata(Path metadataPath) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = new BufferedInputStream(new FileInputStream(metadataPath.toFile()))) {
            return parser.parse(is);
        }
    }

    private Path downloadMetadata(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI current = URI.create(url);

        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Accept", "application/xml");
            if (metadataHeaders != null) {
                for (String name : metadataHeaders.stringPropertyNames()) {
                    requestBuilder.header(name, metadataHeaders.getProperty(name));
                }
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new MojoFailureException(
                            "Redirect without Location header: HTTP " + response.statusCode());
                }
                current = resolveRedirectUri(current, location);
                getLog().info("Following redirect to: " + current);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new MojoFailureException("Failed to download metadata: HTTP " + response.statusCode());
            }

            // Cache to a temp file so we can hash and parse it reliably.
            Path tempFile = Files.createTempFile("odata-metadata-", ".xml");
            tempFile.toFile().deleteOnExit();
            Files.copy(response.body(), tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }

        throw new MojoFailureException("Too many redirects downloading metadata from: " + url);
    }

    /**
     * Resolves a redirect {@code Location} header against the current URI.
     * Location may be absolute, root-relative, or relative to the current path.
     */
    static URI resolveRedirectUri(URI current, String location) {
        URI loc = URI.create(location);
        return loc.isAbsolute() ? loc : current.resolve(loc);
    }

    private String hashFile(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        try (InputStream is = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    /**
     * Marker hash folds in generation configuration so that changing config
     * (basePackage, generateWithMethods, schemaPackages, outputDirectory) after a
     * prior run invalidates the marker and forces regeneration.
     */
    private String computeMarkerHash(Path metadataPath) throws Exception {
        StringBuilder config = new StringBuilder();
        config.append("basePackage=").append(basePackage == null ? "" : basePackage).append('\n');
        config.append("generateWithMethods=").append(generateWithMethods).append('\n');
        config.append("outputDirectory=").append(outputDirectory == null ? "" : outputDirectory.getAbsolutePath()).append('\n');
        config.append("pluginVersion=").append(pluginVersion == null ? "" : pluginVersion).append('\n');
        if (metadataHeaders != null) {
            for (String name : metadataHeaders.stringPropertyNames()) {
                config.append("header=").append(name).append('\n');
            }
        }
        for (SchemaMapping mapping : schemaPackages) {
            config.append("schema=").append(mapping.getNamespace()).append('=')
                    .append(mapping.getPackageName()).append('\n');
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        digest.update(hashFile(metadataPath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean isUpToDate(Path outputDir, String currentHash) throws Exception {
        Path marker = outputDir.resolve(markerFileName());
        if (!Files.exists(marker)) {
            return false;
        }
        String previousHash = Files.readString(marker).split("\\R", 2)[0].trim();
        if (!currentHash.equals(previousHash)) {
            return false;
        }
        // Ensure there is at least one generated Java file; an empty directory with a stale marker is not up-to-date.
        try (var stream = Files.find(outputDir, Integer.MAX_VALUE,
                (path, attrs) -> path.toString().endsWith(".java"))) {
            return stream.findAny().isPresent();
        }
    }

    /**
     * Marker format: first line is the hash, following lines are the generated file paths
     * relative to the output directory (the manifest for stale-file cleanup).
     */
    private void writeMarker(Path outputDir, String hash, List<Path> generatedFiles) throws Exception {
        StringBuilder content = new StringBuilder(hash).append('\n');
        Path normalized = outputDir.toAbsolutePath().normalize();
        for (Path file : generatedFiles) {
            content.append(normalized.relativize(file.toAbsolutePath().normalize())).append('\n');
        }
        Files.writeString(outputDir.resolve(markerFileName()), content.toString());
    }

    private List<String> readMarkerManifest(Path outputDir) throws Exception {
        Path marker = outputDir.resolve(markerFileName());
        if (!Files.exists(marker)) {
            return List.of();
        }
        String[] lines = Files.readString(marker).split("\\R");
        // legacy markers (hash only, no manifest) delete nothing
        return lines.length <= 1 ? List.of() : List.of(lines).subList(1, lines.length);
    }

    private void deleteStaleFiles(Path outputDir, List<String> previous, List<Path> current) throws Exception {
        if (previous.isEmpty()) {
            return;
        }
        java.util.Set<String> currentRelative = new java.util.HashSet<>();
        Path normalized = outputDir.toAbsolutePath().normalize();
        for (Path file : current) {
            currentRelative.add(normalized.relativize(file.toAbsolutePath().normalize()).toString());
        }
        for (String relative : previous) {
            if (relative.isBlank() || !relative.endsWith(".java") || currentRelative.contains(relative)) {
                continue;
            }
            Path stale = normalized.resolve(relative).normalize();
            if (!stale.startsWith(normalized)) {
                continue; // never follow a manifest entry outside the output directory
            }
            if (Files.deleteIfExists(stale)) {
                getLog().info("Deleted stale generated file: " + relative);
            }
        }
    }
}
