package io.github.akbarhusain.odata.maven;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H8: marker hash ignores metadataHeaders values (only names).
 * Changing header value (e.g., rotating Bearer token) must invalidate marker, currently does NOT.
 */
class GenerateMojoMarkerValueTest {

    @TempDir
    Path tempDir;

    private void setField(Object target, String name, Object value) throws Exception {
        var f = GenerateMojo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private String invokeComputeMarkerHash(GenerateMojo mojo, Path metadataPath) throws Exception {
        Method m = GenerateMojo.class.getDeclaredMethod("computeMarkerHash", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(mojo, metadataPath);
    }

    private File writeMetadata(String content) throws Exception {
        Path file = tempDir.resolve("metadata.xml");
        Files.writeString(file, content);
        return file.toFile();
    }

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
    void markerHashMustIncludeHeaderValuesNotJustNames() throws Exception {
        File metadata = writeMetadata(METADATA);
        Path metaPath = metadata.toPath();
        File outputDir = tempDir.resolve("out").toFile();
        outputDir.mkdirs();

        GenerateMojo mojo1 = new GenerateMojo();
        setField(mojo1, "metadataFile", metadata);
        setField(mojo1, "outputDirectory", outputDir);
        setField(mojo1, "basePackage", "com.example.test");
        setField(mojo1, "project", new MavenProject());
        setField(mojo1, "pluginVersion", "1.0.0");
        Properties h1 = new Properties();
        h1.setProperty("Authorization", "Bearer token1");
        setField(mojo1, "metadataHeaders", h1);

        GenerateMojo mojo2 = new GenerateMojo();
        setField(mojo2, "metadataFile", metadata);
        setField(mojo2, "outputDirectory", outputDir);
        setField(mojo2, "basePackage", "com.example.test");
        setField(mojo2, "project", new MavenProject());
        setField(mojo2, "pluginVersion", "1.0.0");
        Properties h2 = new Properties();
        h2.setProperty("Authorization", "Bearer token2");
        setField(mojo2, "metadataHeaders", h2);

        String hash1 = invokeComputeMarkerHash(mojo1, metaPath);
        String hash2 = invokeComputeMarkerHash(mojo2, metaPath);

        assertNotEquals(hash1, hash2,
                "H8: marker hash must change when header value changes (Bearer token rotation). Currently only header name is hashed, so hashes are equal");
    }

    @Test
    void differentHeaderNamesAlreadyChangeHash() throws Exception {
        File metadata = writeMetadata(METADATA);
        Path metaPath = metadata.toPath();
        File outputDir = tempDir.resolve("out2").toFile();
        outputDir.mkdirs();

        GenerateMojo mojo1 = new GenerateMojo();
        setField(mojo1, "metadataFile", metadata);
        setField(mojo1, "outputDirectory", outputDir);
        setField(mojo1, "basePackage", "com.example.test");
        setField(mojo1, "project", new MavenProject());
        setField(mojo1, "pluginVersion", "1.0.0");
        Properties h1 = new Properties();
        h1.setProperty("Authorization", "Bearer token");
        setField(mojo1, "metadataHeaders", h1);

        GenerateMojo mojo2 = new GenerateMojo();
        setField(mojo2, "metadataFile", metadata);
        setField(mojo2, "outputDirectory", outputDir);
        setField(mojo2, "basePackage", "com.example.test");
        setField(mojo2, "project", new MavenProject());
        setField(mojo2, "pluginVersion", "1.0.0");
        Properties h2 = new Properties();
        h2.setProperty("X-Other", "Bearer token");
        setField(mojo2, "metadataHeaders", h2);

        String hash1 = invokeComputeMarkerHash(mojo1, metaPath);
        String hash2 = invokeComputeMarkerHash(mojo2, metaPath);

        assertNotEquals(hash1, hash2, "different header names should already change hash");
    }
}
