package com.modernodata.maven;

import com.modernodata.core.generator.Generator;
import com.modernodata.core.model.CsdlModel;
import com.modernodata.core.parser.StaxCsdlParser;
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
import java.util.HashMap;
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
    private Map<String, String> schemaPackages = new HashMap<>();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (metadataUrl == null && metadataFile == null) {
            throw new MojoExecutionException("Either metadataUrl or metadataFile must be specified");
        }

        try {
            CsdlModel model = parseMetadata();
            Path outputDir = outputDirectory.toPath();
            Files.createDirectories(outputDir);

            Map<String, String> packages = new HashMap<>(schemaPackages);
            if (basePackage != null && !packages.isEmpty()) {
                // If basePackage is set but schemaPackages is empty, use basePackage for all schemas
                // This is handled by the Generator's default behavior
            }

            Generator generator = new Generator(outputDir, packages);
            generator.generate(model);

            // Add generated sources to Maven project
            project.addCompileSourceRoot(outputDir.toFile().getAbsolutePath());

            getLog().info("OData client generated successfully in " + outputDir);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OData client: " + e.getMessage(), e);
        }
    }

    private CsdlModel parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        InputStream is;

        if (metadataFile != null) {
            if (!metadataFile.exists()) {
                throw new MojoFailureException("Metadata file not found: " + metadataFile.getAbsolutePath());
            }
            getLog().info("Parsing metadata from file: " + metadataFile.getAbsolutePath());
            is = new FileInputStream(metadataFile);
        } else {
            getLog().info("Downloading metadata from: " + metadataUrl);
            is = downloadMetadata(metadataUrl);
        }

        try (is) {
            return parser.parse(is);
        }
    }

    private InputStream downloadMetadata(String url) throws Exception {
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

        // Cache to a temp file so we can return an InputStream
        Path tempFile = Files.createTempFile("odata-metadata-", ".xml");
        Files.copy(response.body(), tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new BufferedInputStream(new FileInputStream(tempFile.toFile()));
    }
}
