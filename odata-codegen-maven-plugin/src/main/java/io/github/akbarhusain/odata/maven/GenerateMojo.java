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

    @Parameter(property = "odata.forceRegenerate", defaultValue = "false")
    private boolean forceRegenerate;

    @Parameter(property = "odata.generateWithMethods", defaultValue = "false")
    private boolean generateWithMethods;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    private static final String MARKER_FILE = ".odata-generation-marker";

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
            String currentHash = hashFile(metadataPath);

            if (!forceRegenerate && isUpToDate(outputDir, currentHash)) {
                getLog().info("OData client is up-to-date; skipping generation (metadata hash unchanged). Use odata.forceRegenerate=true to override.");
                project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());
                return;
            }

            CsdlModel model = parseMetadata(metadataPath);

            Map<String, String> packages = new HashMap<>();
            for (SchemaMapping mapping : schemaPackages) {
                packages.put(mapping.getNamespace(), mapping.getPackageName());
            }

            Generator generator = new Generator(outputDir, packages, basePackage);
            generator.withGenerateWithMethods(generateWithMethods);
            generator.generate(model);

            writeMarker(outputDir, currentHash);

            // Add generated sources to Maven project
            project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());

            getLog().info("OData client generated successfully in " + outputDir);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OData client: " + e.getMessage(), e);
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/xml, application/json")
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            // Follow redirects
            String location = response.headers().firstValue("Location").orElse(null);
            if (location != null) {
                getLog().info("Following redirect to: " + location);
                HttpRequest redirectRequest = HttpRequest.newBuilder()
                        .uri(URI.create(location))
                        .header("Accept", "application/xml, application/json")
                        .build();
                response = client.send(redirectRequest, HttpResponse.BodyHandlers.ofInputStream());
            }
        }

        if (response.statusCode() != 200) {
            throw new MojoFailureException("Failed to download metadata: HTTP " + response.statusCode());
        }

        // Cache to a temp file so we can hash and parse it reliably
        Path tempFile = Files.createTempFile("odata-metadata-", ".xml");
        Files.copy(response.body(), tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean isUpToDate(Path outputDir, String currentHash) throws Exception {
        Path marker = outputDir.resolve(MARKER_FILE);
        if (!Files.exists(marker)) {
            return false;
        }
        String previousHash = Files.readString(marker).trim();
        if (!currentHash.equals(previousHash)) {
            return false;
        }
        // Ensure there is at least one generated Java file; an empty directory with a stale marker is not up-to-date.
        try (var stream = Files.find(outputDir, Integer.MAX_VALUE,
                (path, attrs) -> path.toString().endsWith(".java"))) {
            return stream.findAny().isPresent();
        }
    }

    private void writeMarker(Path outputDir, String hash) throws Exception {
        Path marker = outputDir.resolve(MARKER_FILE);
        Files.writeString(marker, hash);
    }
}
