package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H9: 304 Not Modified treated as redirect.
 * GenerateMojo.downloadMetadata checks statusCode >=300 && <400, so 304 with no Location is treated as redirect
 * and throws "Redirect without Location". Correct: only 301,302,303,307,308 are redirects; 304 should be
 * handled as non-200 failure "Failed to download metadata: HTTP 304".
 */
class GenerateMojoRedirectTest {

    @TempDir
    Path tempDir;

    private void setField(Object target, String name, Object value) throws Exception {
        var f = GenerateMojo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void status304IsNotTreatedAsRedirect() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/metadata", exchange -> {
            // 304 without Location header (legitimate Not Modified)
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        server.start();
        try {
            GenerateMojo mojo = new GenerateMojo();
            File out = tempDir.resolve("out").toFile();
            setField(mojo, "metadataUrl", "http://localhost:" + server.getAddress().getPort() + "/metadata");
            setField(mojo, "outputDirectory", out);
            setField(mojo, "basePackage", "com.example.test");
            setField(mojo, "project", new MavenProject());

            Method dl = GenerateMojo.class.getDeclaredMethod("downloadMetadata", String.class);
            dl.setAccessible(true);
            MojoFailureException ex = assertThrows(MojoFailureException.class, () -> {
                try {
                    dl.invoke(mojo, "http://localhost:" + server.getAddress().getPort() + "/metadata");
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Exception e) throw e;
                    throw new RuntimeException(cause);
                }
            });

            // H9: current bug throws "Redirect without Location header: HTTP 304"
            // Expected after fix: "Failed to download metadata: HTTP 304" (or similar, NOT redirect)
            assertFalse(ex.getMessage().contains("Redirect without Location"),
                    "H9: 304 must NOT be treated as redirect. Got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("304"),
                    "failure should mention HTTP 304: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void trueRedirectsAreStillFollowed() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        String body = """
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
        server.createContext("/metadata", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            GenerateMojo mojo = new GenerateMojo();
            File out = tempDir.resolve("out2").toFile();
            setField(mojo, "metadataUrl", "http://localhost:" + server.getAddress().getPort() + "/metadata");
            setField(mojo, "outputDirectory", out);
            setField(mojo, "basePackage", "com.example.test");
            setField(mojo, "project", new MavenProject());

            Method dl = GenerateMojo.class.getDeclaredMethod("downloadMetadata", String.class);
            dl.setAccessible(true);
            Path result = (Path) dl.invoke(mojo, "http://localhost:" + server.getAddress().getPort() + "/metadata");
            assertTrue(java.nio.file.Files.exists(result), "redirect should be followed and temp file created");
            assertTrue(java.nio.file.Files.readString(result).contains("TestNS"));
        } finally {
            server.stop(0);
        }
    }
}
