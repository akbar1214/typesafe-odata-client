package io.github.akbarhusain.odata.maven;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M12: URL metadata is downloaded to a temp file that must be deleted on EVERY exit
 * path of {@code GenerateMojo.execute()} — success, up-to-date early return, and
 * failure. Behavioral check: run the full mojo against a local HTTP server and assert
 * no {@code odata-metadata-*} temp files are left behind in java.io.tmpdir.
 */
class GenerateMojoMediumTest {

    private static final String METADATA = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
              <edmx:DataServices>
                <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                  <EntityType Name="Person">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="Container">
                    <EntitySet Name="People" EntityType="TestNS.Person"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    @TempDir
    Path tempDir;

    private com.sun.net.httpserver.HttpServer startServer() throws IOException {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        byte[] body = METADATA.getBytes(StandardCharsets.UTF_8);
        server.createContext("/metadata", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private GenerateMojo newMojo(String url, File outDir) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        setField(mojo, "metadataUrl", url);
        setField(mojo, "outputDirectory", outDir);
        setField(mojo, "basePackage", "com.example.test");
        setField(mojo, "project", new MavenProject());
        return mojo;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var f = GenerateMojo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private List<Path> leftoverMetadataTemps() throws IOException {
        List<Path> found = new ArrayList<>();
        Path tmpdir = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmpdir)) {
            files.filter(p -> p.getFileName().toString().startsWith("odata-metadata-"))
                    .forEach(found::add);
        }
        return found;
    }

    @Test
    void m12_executeDeletesDownloadedTempFileOnSuccess() throws Exception {
        com.sun.net.httpserver.HttpServer server = startServer();
        try {
            File out = tempDir.resolve("out").toFile();
            newMojo("http://localhost:" + server.getAddress().getPort() + "/metadata", out).execute();

            assertTrue(Files.exists(out.toPath().resolve("com/example/test/entity/Person.java")),
                    "generation should have produced Person.java");

            assertEquals(0, leftoverMetadataTemps().size(),
                    "M12: downloaded metadata temp file must be deleted after a successful run");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void m12_executeDeletesTempFileOnUpToDateEarlyReturn() throws Exception {
        com.sun.net.httpserver.HttpServer server = startServer();
        try {
            File out = tempDir.resolve("out").toFile();
            GenerateMojo mojo = newMojo("http://localhost:" + server.getAddress().getPort() + "/metadata", out);
            mojo.execute();
            // Second run: marker matches -> execute() returns early BEFORE parsing,
            // but the temp file was already downloaded for hashing
            mojo.execute();

            assertEquals(0, leftoverMetadataTemps().size(),
                    "M12: the up-to-date early-return path must also delete the downloaded temp file");
        } finally {
            server.stop(0);
        }
    }
}
