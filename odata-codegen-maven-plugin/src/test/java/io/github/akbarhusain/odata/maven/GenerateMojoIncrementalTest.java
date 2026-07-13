package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
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
        assertTrue(new File(outputDir, ".odata-generation-marker").exists(), "Marker file should be created");
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
}
