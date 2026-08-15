package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMojoIncrementalTest {

    @TempDir
    Path tempDir;

    private GenerateMojo createMojo(File metadataFile, File outputDir) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        setField(mojo, "metadataFile", metadataFile);
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "basePackage", "com.example.test");
        setField(mojo, "project", new MavenProject());
        setField(mojo, "skip", false);
        setField(mojo, "forceRegenerate", false);
        return mojo;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = GenerateMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private File writeMetadata(String content) throws Exception {
        Path file = tempDir.resolve("metadata.xml");
        Files.writeString(file, content);
        return file.toFile();
    }

    private int countGeneratedFiles(File outputDir) throws Exception {
        try (var stream = Files.find(outputDir.toPath(), Integer.MAX_VALUE,
                (path, attrs) -> path.toString().endsWith(".java"))) {
            return (int) stream.count();
        }
    }

    private File findAnyGeneratedFile(File outputDir) throws Exception {
        try (var stream = Files.find(outputDir.toPath(), Integer.MAX_VALUE,
                (path, attrs) -> path.toString().endsWith(".java"))) {
            return stream.findAny()
                    .map(Path::toFile)
                    .orElseThrow(() -> new AssertionError("No generated Java files found in " + outputDir));
        }
    }

    @Test
    void skipFlagBypassesGeneration() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-skip").toFile();

        GenerateMojo mojo = createMojo(metadata, outputDir);
        setField(mojo, "skip", true);
        mojo.execute();

        assertFalse(outputDir.exists(), "Skip should not create output directory");
    }

    @Test
    void firstRunGeneratesFilesAndMarker() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-first").toFile();

        GenerateMojo mojo = createMojo(metadata, outputDir);
        mojo.execute();

        assertTrue(outputDir.exists(), "Output directory should be created");
        assertTrue(java.util.Arrays.stream(outputDir.listFiles())
                        .anyMatch(f -> f.getName().startsWith(".odata-generation-marker")),
                "Marker file should be created");
        assertTrue(countGeneratedFiles(outputDir) > 0, "Java files should be generated");
    }

    @Test
    void unchangedMetadataSkipsRegeneration() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-unchanged").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        first.execute();
        int firstCount = countGeneratedFiles(outputDir);

        // Delete a generated file to detect whether the second run actually re-runs generation.
        File generated = findAnyGeneratedFile(outputDir);
        assertTrue(generated.delete(), "Should be able to delete a generated file for the test");

        GenerateMojo second = createMojo(metadata, outputDir);
        second.execute();

        assertEquals(firstCount - 1, countGeneratedFiles(outputDir),
                "Second run should skip regeneration when metadata is unchanged");
    }

    @Test
    void changedMetadataForcesRegeneration() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-changed").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        first.execute();

        // Modify metadata
        Files.writeString(metadata.toPath(), """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                        <Property Name="Age" Type="Edm.Int32"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        GenerateMojo second = createMojo(metadata, outputDir);
        second.execute();

        assertTrue(countGeneratedFiles(outputDir) > 0, "Regeneration should produce files");
    }

    @Test
    void changedConfigForcesRegenerationEvenWhenMetadataUnchanged() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-config").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        setField(first, "generateWithMethods", false);
        first.execute();
        int firstCount = countGeneratedFiles(outputDir);

        // Metadata is unchanged; only the generateWithMethods config changes.
        File generated = findAnyGeneratedFile(outputDir);
        assertTrue(generated.delete(), "Should be able to delete a generated file for the test");

        GenerateMojo second = createMojo(metadata, outputDir);
        setField(second, "generateWithMethods", true);
        second.execute();

        assertEquals(firstCount, countGeneratedFiles(outputDir),
                "Changing generateWithMethods should invalidate the marker and force regeneration");
    }

    @Test
    void changedPluginVersionForcesRegenerationEvenWhenMetadataUnchanged() throws Exception {
        // H8: upgrading the plugin must not silently skip regeneration when the
        // metadata and config are unchanged.
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-version").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        setField(first, "pluginVersion", "1.0.0");
        first.execute();
        int firstCount = countGeneratedFiles(outputDir);

        // Metadata is unchanged; only the plugin version changes.
        File generated = findAnyGeneratedFile(outputDir);
        assertTrue(generated.delete(), "Should be able to delete a generated file for the test");

        GenerateMojo second = createMojo(metadata, outputDir);
        setField(second, "pluginVersion", "1.0.1");
        second.execute();

        assertEquals(firstCount, countGeneratedFiles(outputDir),
                "Changing plugin version should invalidate the marker and force regeneration");
    }

    @Test
    void resolveRedirectUriHandlesRelativeAndAbsoluteLocations() throws Exception {
        // H8: redirect Location headers may be relative to the current URI.
        URI base = new URI("https://services.odata.org/V4/TripPinService/$metadata");
        assertEquals(new URI("https://services.odata.org/V4/TripPinService/redirected"),
                GenerateMojo.resolveRedirectUri(base, "/V4/TripPinService/redirected"));
        assertEquals(new URI("https://services.odata.org/V4/TripPinService/relative"),
                GenerateMojo.resolveRedirectUri(base, "relative"));
        assertEquals(new URI("https://other.example.com/$metadata"),
                GenerateMojo.resolveRedirectUri(base, "https://other.example.com/$metadata"));
    }

    @Test
    void forceRegenerateOverridesMarker() throws Exception {
        File metadata = writeMetadata("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Name" Type="Edm.String"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="TestNS.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);
        File outputDir = tempDir.resolve("out-force").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        first.execute();
        int firstCount = countGeneratedFiles(outputDir);

        // Delete a generated file
        File generated = findAnyGeneratedFile(outputDir);
        assertTrue(generated.delete());

        GenerateMojo second = createMojo(metadata, outputDir);
        setField(second, "forceRegenerate", true);
        second.execute();

        assertEquals(firstCount, countGeneratedFiles(outputDir),
                "forceRegenerate=true should regenerate all files even when marker matches");
    }

    // ------------------------------------------------------------------
    // Round-3 findings M25-M28
    // ------------------------------------------------------------------

    private static final String ONE_ENTITY_METADATA = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
              <edmx:DataServices>
                <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                  <EntityType Name="Alpha">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="Container">
                    <EntitySet Name="Alphas" EntityType="TestNS.Alpha"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    private static final String TWO_ENTITY_METADATA = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
              <edmx:DataServices>
                <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                  <EntityType Name="Alpha">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityType Name="Beta">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="Container">
                    <EntitySet Name="Alphas" EntityType="TestNS.Alpha"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    @Test
    void m28RemovedEntityFilesAreDeletedOnRegeneration() throws Exception {
        File metadata = writeMetadata(TWO_ENTITY_METADATA);
        File outputDir = tempDir.resolve("out-m28").toFile();

        GenerateMojo first = createMojo(metadata, outputDir);
        first.execute();
        assertTrue(countFiles(outputDir.toPath(), "Alpha.java") >= 1, "Alpha generated");
        assertTrue(countFiles(outputDir.toPath(), "Beta.java") >= 1, "Beta generated");

        // Entity removed from the metadata -> its generated files must not linger
        Files.writeString(metadata.toPath(), ONE_ENTITY_METADATA);
        GenerateMojo second = createMojo(metadata, outputDir);
        second.execute();

        assertEquals(0, countFiles(outputDir.toPath(), "Beta.java"),
                "stale files for removed entities must be deleted");
        assertTrue(countFiles(outputDir.toPath(), "Alpha.java") >= 1, "current files remain");
    }

    private int countFiles(Path dir, String suffix) throws Exception {
        try (var stream = Files.find(dir, Integer.MAX_VALUE,
                (path, attrs) -> path.getFileName().toString().endsWith(suffix))) {
            return (int) stream.count();
        }
    }

    @Test
    void m27SharedOutputDirExecutionsKeepSeparateMarkers() throws Exception {
        File metadataA = tempDir.resolve("metadata-a.xml").toFile();
        Files.writeString(metadataA.toPath(), ONE_ENTITY_METADATA.replace("Alpha", "Gamma").replace("Alphas", "Gammas").replace("TestNS", "NsA"));
        File metadataB = tempDir.resolve("metadata-b.xml").toFile();
        Files.writeString(metadataB.toPath(), ONE_ENTITY_METADATA.replace("Alpha", "Delta").replace("Alphas", "Deltas").replace("TestNS", "NsB"));
        File outputDir = tempDir.resolve("out-m27").toFile();

        GenerateMojo first = createMojo(metadataA, outputDir);
        first.execute();

        GenerateMojo second = createMojo(metadataB, outputDir);
        second.execute();

        // Two distinct config/metadata hashes -> two distinct markers, each still valid
        java.util.List<Path> markers;
        try (var stream = Files.list(outputDir.toPath())) {
            markers = stream.filter(f -> f.getFileName().toString().startsWith(".odata-generation-marker-")).toList();
        }
        assertEquals(2, markers.size(), "each execution (config hash) keeps its own marker: " + markers);
        Path firstMarker = markers.get(0);
        Path secondMarker = markers.get(1);

        // Re-running each execution must be up-to-date (its marker was not clobbered)
        long firstMtime = Files.getLastModifiedTime(firstMarker).toMillis();
        long secondMtime = Files.getLastModifiedTime(secondMarker).toMillis();
        Thread.sleep(50);
        first.execute();
        second.execute();
        assertEquals(firstMtime, Files.getLastModifiedTime(firstMarker).toMillis(),
                "sharing an output directory must not invalidate other executions' markers");
        assertEquals(secondMtime, Files.getLastModifiedTime(secondMarker).toMillis(),
                "sharing an output directory must not invalidate other executions' markers");
        assertTrue(countFiles(outputDir.toPath(), "Gamma.java") >= 1);
        assertTrue(countFiles(outputDir.toPath(), "Delta.java") >= 1);
    }

    @Test
    void m25AndM26MetadataDownloadWithAuthHeadersKeepsFailureSemantics() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        String[] seenAuth = new String[1];
        server.createContext("/metadata", exchange -> {
            seenAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer secret-token".equals(seenAuth[0])) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = ONE_ENTITY_METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            File outputDir = tempDir.resolve("out-m26").toFile();

            // Without the header: 404 -> MojoFailureException with the original message (M25),
            // not the generic re-wrapped one
            GenerateMojo noAuth = new GenerateMojo();
            setField(noAuth, "metadataUrl", "http://localhost:" + server.getAddress().getPort() + "/metadata");
            setField(noAuth, "outputDirectory", outputDir);
            setField(noAuth, "basePackage", "com.example.test");
            setField(noAuth, "project", new MavenProject());
            setField(noAuth, "skip", false);
            setField(noAuth, "forceRegenerate", false);
            MojoFailureException failure = assertThrows(MojoFailureException.class, noAuth::execute);
            assertTrue(failure.getMessage().contains("HTTP 404"),
                    "failure must keep its own message, not be re-wrapped: " + failure.getMessage());

            // With the header: download succeeds (M26)
            java.util.Properties headers = new java.util.Properties();
            headers.setProperty("Authorization", "Bearer secret-token");
            GenerateMojo withAuth = new GenerateMojo();
            setField(withAuth, "metadataUrl", "http://localhost:" + server.getAddress().getPort() + "/metadata");
            setField(withAuth, "outputDirectory", outputDir);
            setField(withAuth, "basePackage", "com.example.test");
            setField(withAuth, "project", new MavenProject());
            setField(withAuth, "skip", false);
            setField(withAuth, "forceRegenerate", false);
            setField(withAuth, "metadataHeaders", headers);
            withAuth.execute();
            assertEquals("Bearer secret-token", seenAuth[0], "configured headers must reach the request");
            assertTrue(countGeneratedFiles(outputDir) >= 1, "metadata downloaded with auth and generated");
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // Round-3 findings L26-L28
    // ------------------------------------------------------------------

    @Test
    void l26MojoIsMarkedThreadSafe() throws Exception {
        // plugin annotations are CLASS-retention, so assert on the generated descriptor
        Path descriptor = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.exists(descriptor), "plugin descriptor not generated: " + descriptor);
        String xml = Files.readString(descriptor);
        int idx = xml.indexOf("<threadSafe>");
        assertTrue(idx >= 0, "descriptor must declare <threadSafe>");
        assertEquals("<threadSafe>true</threadSafe>",
                xml.substring(idx, xml.indexOf("</threadSafe>", idx) + "</threadSafe>".length()),
                "the mojo only touches per-module paths; parallel builds (-T) skip/warn otherwise");
    }

    @Test
    void l27JsonMetadataFailsWithClearMessage() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/metadata", exchange -> {
            byte[] body = "{\"odata.context\":\"https://example.com/$metadata#Edm\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            GenerateMojo mojo = new GenerateMojo();
            setField(mojo, "metadataUrl", "http://localhost:" + server.getAddress().getPort() + "/metadata");
            setField(mojo, "outputDirectory", tempDir.resolve("out-l27").toFile());
            setField(mojo, "basePackage", "com.example.test");
            setField(mojo, "project", new MavenProject());
            setField(mojo, "skip", false);
            MojoFailureException failure = assertThrows(MojoFailureException.class, mojo::execute);
            assertTrue(failure.getMessage().contains("CSDL XML"),
                    "error must explain the parser only supports XML: " + failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void l28BothMetadataSourcesConfiguredWarnsAndUsesFile() throws Exception {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        org.apache.maven.plugin.logging.Log capture =
                new org.apache.maven.plugin.logging.SystemStreamLog() {
                    @Override
                    public void warn(CharSequence msg) {
                        warnings.add(String.valueOf(msg));
                    }
                };
        File metadata = writeMetadata(ONE_ENTITY_METADATA);
        File outputDir = tempDir.resolve("out-l28").toFile();

        GenerateMojo mojo = createMojo(metadata, outputDir);
        setField(mojo, "metadataUrl", "https://example.com/metadata.xml");
        mojo.setLog(capture);
        mojo.execute();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("metadataUrl") && w.contains("metadataFile")),
                "configuring both must warn which one wins: " + warnings);
        assertTrue(countGeneratedFiles(outputDir) >= 1, "the file is used");
    }
}
