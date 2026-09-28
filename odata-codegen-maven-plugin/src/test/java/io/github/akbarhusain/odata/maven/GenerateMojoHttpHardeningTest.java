package io.github.akbarhusain.odata.maven;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMojoHttpHardeningTest {

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

    @Test
    void gzipResponseIsDecompressedAndIdentityIsRequested(@TempDir Path tempDir) throws Exception {
        List<String> acceptEncoding = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/metadata", exchange -> {
            acceptEncoding.add(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] body = gzip(METADATA.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            GenerateMojo mojo = newMojo("http://localhost:" + server.getAddress().getPort() + "/metadata",
                    tempDir.resolve("out"), new Properties());
            mojo.execute();
            assertTrue(Files.exists(tempDir.resolve("out/com/example/test/entity/Person.java")));
            assertEquals(List.of("identity"), acceptEncoding);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredAuthenticationIsNotForwardedAcrossOrigins(@TempDir Path tempDir) throws Exception {
        List<String> firstAuth = new CopyOnWriteArrayList<>();
        List<String> secondAuth = new CopyOnWriteArrayList<>();
        HttpServer target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/metadata", exchange -> {
            secondAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            writeMetadata(exchange);
        });
        target.start();
        HttpServer source = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        source.createContext("/start", exchange -> {
            firstAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location",
                    "http://localhost:" + target.getAddress().getPort() + "/metadata");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();
        try {
            Properties headers = new Properties();
            headers.setProperty("Authorization", "Bearer secret");
            GenerateMojo mojo = newMojo("http://localhost:" + source.getAddress().getPort() + "/start",
                    tempDir.resolve("out"), headers);
            mojo.execute();
            assertEquals(List.of("Bearer secret"), firstAuth);
            assertEquals(1, secondAuth.size());
            assertNull(secondAuth.get(0));
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void sameOriginMultiHopRedirectsKeepAuthentication(@TempDir Path tempDir) throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/start", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "/middle");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/middle", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "/metadata");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/metadata", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            writeMetadata(exchange);
        });
        server.start();
        try {
            Properties headers = new Properties();
            headers.setProperty("Authorization", "Bearer same-origin");
            newMojo("http://localhost:" + server.getAddress().getPort() + "/start",
                    tempDir.resolve("out"), headers).execute();
            assertEquals(List.of("Bearer same-origin", "Bearer same-origin", "Bearer same-origin"), seen);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metadataUrlLogsRedactUserinfoAndQuery(@TempDir Path tempDir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/metadata", exchange -> writeMetadata(exchange));
        server.start();
        try {
            List<String> logs = new ArrayList<>();
            Log log = new SystemStreamLog() {
                @Override
                public void info(CharSequence message) {
                    logs.add(String.valueOf(message));
                }

                @Override
                public void warn(CharSequence message) {
                    logs.add(String.valueOf(message));
                }
            };
            GenerateMojo mojo = newMojo("http://localhost:" + server.getAddress().getPort()
                    + "/metadata?token=secret-value", tempDir.resolve("out"), new Properties());
            mojo.setLog(log);
            mojo.execute();
            String text = String.join("\n", logs);
            assertFalse(text.contains("secret-value"), text);
            assertFalse(text.contains("token="), text);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void userinfoAndQueryAreRedactedByTheUriFormatter() throws Exception {
        Method method = GenerateMojo.class.getDeclaredMethod("redactUri", java.net.URI.class);
        method.setAccessible(true);
        String value = (String) method.invoke(null,
                java.net.URI.create("https://user:password@example.test/path?token=secret#fragment"));
        assertFalse(value.contains("user"));
        assertFalse(value.contains("password"));
        assertFalse(value.contains("token"));
        assertFalse(value.contains("secret"));
    }

    private static GenerateMojo newMojo(String url, Path output, Properties headers) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        set(mojo, "metadataUrl", url);
        set(mojo, "outputDirectory", output.toFile());
        set(mojo, "basePackage", "com.example.test");
        set(mojo, "metadataHeaders", headers);
        set(mojo, "project", new MavenProject());
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field javaField = GenerateMojo.class.getDeclaredField(field);
        javaField.setAccessible(true);
        javaField.set(target, value);
    }

    private static void writeMetadata(HttpExchange exchange) throws IOException {
        byte[] body = METADATA.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }
}
