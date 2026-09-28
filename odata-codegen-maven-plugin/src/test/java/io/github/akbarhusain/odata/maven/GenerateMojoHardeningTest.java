package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.akbarhusain.odata.core.generator.Generator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMojoHardeningTest {

    private static final String FOO = metadata("Foo");
    private static final String BAR = metadata("Bar");

    @Test
    void missingManifestFileForcesRegeneration(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path person = findJava(output, "Foo.java");
        Files.delete(person);
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        assertTrue(Files.isRegularFile(person, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void truncatedManifestFileForcesRegeneration(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path person = findJava(output, "Foo.java");
        String source = Files.readString(person);
        Files.writeString(person, source.substring(0, Math.max(1, source.length() / 2)));
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        assertEquals(source, Files.readString(person));
    }

    @Test
    void malformedManifestIsNotAnUpToDateMarker(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path marker = markers(output).get(0);
        Files.writeString(marker, Files.readString(marker).split("\\R", 2)[0]);
        Path person = findJava(output, "Foo.java");
        Files.delete(person);
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        assertTrue(Files.isRegularFile(person, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void truncatedManifestEntryListIsNotUpToDate(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path marker = markers(output).get(0);
        String[] lines = Files.readString(marker).split("\\R", -1);
        int last = lines.length - 1;
        while (last > 0 && lines[last].isBlank()) {
            last--;
        }
        Files.writeString(marker, String.join("\n", Arrays.copyOf(lines, last)) + "\n");
        Files.setLastModifiedTime(marker, FileTime.fromMillis(1000));

        newMojo(metadata, output, "com.example.test", List.of()).execute();
        assertTrue(Files.getLastModifiedTime(marker).toMillis() > 1000);
    }

    @Test
    void sameSourceDifferentBasePackagesKeepIndependentManifests(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.one", List.of()).execute();
        newMojo(metadata, output, "com.example.two", List.of()).execute();

        assertTrue(Files.isRegularFile(output.resolve("com/example/one/entity/Foo.java")));
        assertTrue(Files.isRegularFile(output.resolve("com/example/two/entity/Foo.java")));
        assertEquals(2, markers(output).size());
    }

    @Test
    void sameSourceDifferentSchemaMappingsKeepIndependentManifests(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example", List.of(new SchemaMapping("TestNS", "com.example.one"))).execute();
        newMojo(metadata, output, "com.example", List.of(new SchemaMapping("TestNS", "com.example.two"))).execute();

        assertTrue(Files.isRegularFile(output.resolve("com/example/one/entity/Foo.java")));
        assertTrue(Files.isRegularFile(output.resolve("com/example/two/entity/Foo.java")));
        assertEquals(2, markers(output).size());
    }

    @Test
    void failedForcedGenerationCannotPoisonLaterReuse(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();
        Path person = findJava(output, "Foo.java");
        Files.delete(person);
        Files.createDirectories(person);
        Files.writeString(person.resolve("blocker"), "blocked");
        Path marker = markers(output).get(0);
        String previousMarker = Files.readString(marker);

        GenerateMojo forced = newMojo(metadata, output, "com.example.test", List.of());
        set(forced, "forceRegenerate", true);
        assertThrows(MojoExecutionException.class, forced::execute);
        assertEquals(previousMarker, Files.readString(marker));

        Files.delete(person.resolve("blocker"));
        Files.delete(person);
        newMojo(metadata, output, "com.example.test", List.of()).execute();
        assertTrue(Files.isRegularFile(person, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void generationRejectsSymlinkEscape(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(output);
        Files.createDirectories(outside);
        Files.createSymbolicLink(output.resolve("com"), outside);

        assertThrows(MojoExecutionException.class,
                () -> newMojo(metadata, output, "com.example.test", List.of()).execute());
        try (Stream<Path> files = Files.walk(outside)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".java")).count());
        }
    }

    @Test
    void symlinkedStagingParentIsRejected(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path realParent = tempDir.resolve("real-target");
        Files.createDirectories(realParent);
        Path linkParent = tempDir.resolve("target");
        Files.createSymbolicLink(linkParent, realParent);
        Path output = linkParent.resolve("out");

        // The atomic-swap temp dirs would be created through the symlinked parent and land
        // outside the build tree; the generation must refuse instead.
        assertThrows(MojoExecutionException.class,
                () -> newMojo(metadata, output, "com.example.test", List.of()).execute());
        try (Stream<Path> files = Files.walk(realParent)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".java")).count());
        }
    }

    @Test
    void successfulGenerationLeavesNoTempDirsBesideOutput(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        try (Stream<Path> files = Files.list(output.getParent())) {
            assertEquals(0, files.filter(path -> path.getFileName().toString()
                            .startsWith(".odata-generation-")).count(),
                    "staging/publication/backup temp dirs must be cleaned up");
        }
    }

    @Test
    void staleDeletionRejectsSymlinkEscape(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();
        Path oldFile = findJava(output, "Foo.java");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "keep");
        Files.delete(oldFile);
        Files.createSymbolicLink(oldFile, outside);
        Files.writeString(metadata, BAR);

        assertThrows(MojoExecutionException.class,
                () -> newMojo(metadata, output, "com.example.test", List.of()).execute());
        assertEquals("keep", Files.readString(outside));
    }

    @Test
    void coreImplementationChangeInvalidatesMarkerHash(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        GenerateMojo mojo = newMojo(metadata, tempDir.resolve("out"), "com.example.test", List.of());
        set(mojo, "pluginVersion", "same-version");
        String before = invokeHash(mojo, metadata);

        Path codeSource = Path.of(Generator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Assumptions.assumeTrue(Files.isDirectory(codeSource), "the reactor test classpath must expose core classes");
        Path classFile = codeSource.resolve("io/github/akbarhusain/odata/core/generator/Generator.class");
        byte[] original = Files.readAllBytes(classFile);
        try {
            Files.write(classFile, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
            String after = invokeHash(mojo, metadata);
            assertNotEquals(before, after);
        } finally {
            Files.write(classFile, original);
        }
    }

    @Test
    void failedMarkerPublicationDoesNotPoisonTheNextRun(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path marker = markers(output).get(0);
        Files.delete(marker);
        Files.createDirectories(marker);
        Files.writeString(marker.resolve("blocker"), "blocked");
        Files.writeString(metadata, BAR);

        assertThrows(MojoExecutionException.class,
                () -> newMojo(metadata, output, "com.example.test", List.of()).execute());
        assertTrue(Files.isDirectory(marker));

        Files.delete(marker.resolve("blocker"));
        Files.delete(marker);
        newMojo(metadata, output, "com.example.test", List.of()).execute();
        assertTrue(Files.isRegularFile(output.resolve("com/example/test/entity/Bar.java"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void directoryMetadataIsRejectedBeforeOutputCreation(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("out");
        GenerateMojo mojo = new GenerateMojo();
        set(mojo, "metadataFile", tempDir.toFile());
        set(mojo, "outputDirectory", output.toFile());
        set(mojo, "basePackage", "com.example.test");
        set(mojo, "project", new MavenProject());

        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
    }

    @Test
    void blankSchemaMappingIsRejectedBeforeOutputCreation(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        GenerateMojo mojo = newMojo(metadata, output, "com.example.test",
                List.of(new SchemaMapping("TestNS", " ")));

        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
    }

    @Test
    void duplicateSchemaMappingsAreRejectedBeforeOutputCreation(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        GenerateMojo mojo = newMojo(metadata, output, "com.example.test", List.of(
                new SchemaMapping("TestNS", "com.example.one"),
                new SchemaMapping("TestNS", "com.example.two")));

        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
    }

    @Test
    void malformedSchemaPackageIsRejectedBeforeOutputCreation(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        GenerateMojo mojo = newMojo(metadata, output, "com.example.test",
                List.of(new SchemaMapping("TestNS", "com/example")));

        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
    }

    @Test
    void blankSchemaNamespaceIsRejectedBeforeOutputCreation(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        GenerateMojo mojo = newMojo(metadata, output, "com.example.test",
                List.of(new SchemaMapping(" ", "com.example")));

        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
    }

    @Test
    void symlinkedMetadataFileIsResolvedAndHashed(@TempDir Path tempDir) throws Exception {
        Path real = writeMetadata(tempDir, FOO);
        Path link = tempDir.resolve("linked-metadata.xml");
        Files.createSymbolicLink(link, real);
        Path output = tempDir.resolve("out");

        newMojo(link, output, "com.example.test", List.of()).execute();

        assertTrue(Files.isRegularFile(findJava(output, "Foo.java"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void backslashManifestEntryDoesNotBrickLaterBuilds(@TempDir Path tempDir) throws Exception {
        Path metadata = writeMetadata(tempDir, FOO);
        Path output = tempDir.resolve("out");
        newMojo(metadata, output, "com.example.test", List.of()).execute();

        Path marker = markers(output).get(0);
        String[] lines = Files.readString(marker).split("\\R");
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] parts = lines[i].split("\t", -1);
            lines[i] = "..\\evil.java\t" + parts[1] + "\t" + parts[2];
            break;
        }
        Files.writeString(marker, String.join("\n", lines) + "\n");

        // Previously this threw from resolveManifestPath and left the corrupt marker in
        // place, failing every subsequent build.
        newMojo(metadata, output, "com.example.test", List.of()).execute();
        assertTrue(Files.isRegularFile(findJava(output, "Foo.java"), LinkOption.NOFOLLOW_LINKS));
    }

    private static GenerateMojo newMojo(Path metadata, Path output, String basePackage,
                                         List<SchemaMapping> mappings) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        set(mojo, "metadataFile", metadata.toFile());
        set(mojo, "outputDirectory", output.toFile());
        set(mojo, "basePackage", basePackage);
        set(mojo, "schemaPackages", new ArrayList<>(mappings));
        set(mojo, "project", new MavenProject());
        return mojo;
    }

    private static Path writeMetadata(Path directory, String source) throws Exception {
        Path path = directory.resolve("metadata.xml");
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field javaField = GenerateMojo.class.getDeclaredField(field);
        javaField.setAccessible(true);
        javaField.set(target, value);
    }

    private static String invokeHash(GenerateMojo mojo, Path metadata) throws Exception {
        Method method = GenerateMojo.class.getDeclaredMethod("computeMarkerHash", Path.class);
        method.setAccessible(true);
        return (String) method.invoke(mojo, metadata);
    }

    private static Path findJava(Path root, String suffix) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).findFirst().orElseThrow();
        }
    }

    private static List<Path> markers(Path output) throws Exception {
        try (Stream<Path> files = Files.list(output)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".odata-generation-marker-"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
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
