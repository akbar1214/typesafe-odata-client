package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMojoManifestTransactionTest {

    private static final String FOO = metadata("Foo");
    private static final String BAR = metadata("Bar");

    @Test
    void staleDeletionFailureLeavesPreviousMarkerUntouched(@TempDir Path tempDir) throws Exception {
        Path metadata = tempDir.resolve("metadata.xml");
        Files.writeString(metadata, FOO, StandardCharsets.UTF_8);
        File output = tempDir.resolve("out").toFile();
        GenerateMojo mojo = newMojo(metadata, output);
        mojo.execute();

        Path stale = output.toPath().resolve("com/example/test/entity/Foo.java");
        Files.delete(stale);
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("child.txt"), "keep");
        Path marker = marker(output.toPath());
        String previous = Files.readString(marker);

        Files.writeString(metadata, BAR, StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> newMojo(metadata, output).execute());
        assertEquals(previous, Files.readString(marker));
    }

    @Test
    void failedPublicationLeavesPreviousCompleteOutput(@TempDir Path tempDir) throws Exception {
        Path metadata = tempDir.resolve("metadata.xml");
        Files.writeString(metadata, FOO, StandardCharsets.UTF_8);
        File output = tempDir.resolve("out").toFile();
        newMojo(metadata, output).execute();
        Map<String, String> before = snapshotJavaFiles(output.toPath());
        Path marker = marker(output.toPath());
        String beforeMarker = Files.readString(marker);
        Files.writeString(metadata, BAR, StandardCharsets.UTF_8);

        assertThrows(MojoExecutionException.class, () -> newFailingMojo(metadata, output).execute());
        assertEquals(before, snapshotJavaFiles(output.toPath()));
        assertEquals(beforeMarker, Files.readString(marker));
    }

    private static Map<String, String> snapshotJavaFiles(Path output) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                    .toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.put(output.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"),
                            Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    private static final class FailingPublicationMojo extends GenerateMojo {
        private int publicationCalls;

        @Override
        protected void publishFile(Path source, Path target, Path outputDir) throws IOException {
            if (++publicationCalls == 2) {
                throw new IOException("injected publication failure");
            }
            super.publishFile(source, target, outputDir);
        }
    }

    private static GenerateMojo newFailingMojo(Path metadata, File output) throws Exception {
        GenerateMojo mojo = new FailingPublicationMojo();
        set(mojo, "metadataFile", metadata.toFile());
        set(mojo, "outputDirectory", output);
        set(mojo, "basePackage", "com.example.test");
        set(mojo, "project", new MavenProject());
        return mojo;
    }

    private static GenerateMojo newMojo(Path metadata, File output) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        set(mojo, "metadataFile", metadata.toFile());
        set(mojo, "outputDirectory", output);
        set(mojo, "basePackage", "com.example.test");
        set(mojo, "project", new MavenProject());
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        var reflectionField = GenerateMojo.class.getDeclaredField(field);
        reflectionField.setAccessible(true);
        reflectionField.set(target, value);
    }

    private static Path marker(Path output) throws Exception {
        try (Stream<Path> files = Files.list(output)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".odata-generation-marker-"))
                    .findFirst().orElseThrow();
        }
    }

    private static String metadata(String name) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
                  <edmx:DataServices>
                    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="TestNS">
                      <EntityType Name="%s">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="%ss" EntityType="TestNS.%s"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """.formatted(name, name, name);
    }
}
