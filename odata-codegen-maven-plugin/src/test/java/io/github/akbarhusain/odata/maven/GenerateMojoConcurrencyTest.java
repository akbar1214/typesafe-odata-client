package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mojo declares {@code threadSafe = true}, which is a promise to Maven that
 * concurrent executions in one reactor are safe. A shared output directory is exactly
 * the case that promise covers, and it is reachable: two modules can generate the same
 * metadata into one directory, and {@code mvn -T} runs module builds in parallel.
 *
 * <p>Publication is a two-rename swap — move the live tree aside, then move the new one
 * into place — with a window in between where the output directory does not exist. Two
 * executions interleave through that window: one moves the other's freshly published
 * tree into its own backup and then deletes it. Both executions log success and exit 0
 * while one client's entire source tree is gone.
 */
class GenerateMojoConcurrencyTest {

    private static final String METADATA = """
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="C">
                    <EntitySet Name="Foos" EntityType="NS.Foo"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    @Test
    void concurrentExecutionsSharingAnOutputDirectoryBothLeaveTheirSources(@TempDir Path tempDir)
            throws Exception {
        Path metadata = tempDir.resolve("metadata.xml");
        Files.writeString(metadata, METADATA, StandardCharsets.UTF_8);
        Path output = tempDir.resolve("out");

        // Repeat: a single interleaving can pass by luck, which is how this defect hid.
        int lostOutputs = 0;
        int iterations = 12;
        for (int i = 0; i < iterations; i++) {
            Files.createDirectories(output);
            runConcurrently(metadata, output, "com.example.one", "com.example.two");
            if (!Files.isRegularFile(output.resolve("com/example/one/entity/Foo.java"))
                    || !Files.isRegularFile(output.resolve("com/example/two/entity/Foo.java"))) {
                lostOutputs++;
            }
        }
        assertTrue(lostOutputs == 0,
                lostOutputs + "/" + iterations + " concurrent runs lost a generated source tree "
                        + "even though both executions reported success");
    }

    private void runConcurrently(Path metadata, Path output, String firstPackage, String secondPackage)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Void> first = pool.submit(() -> {
                start.await();
                newMojo(metadata, output, firstPackage).execute();
                return null;
            });
            Future<Void> second = pool.submit(() -> {
                start.await();
                newMojo(metadata, output, secondPackage).execute();
                return null;
            });
            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static GenerateMojo newMojo(Path metadata, Path output, String basePackage) throws Exception {
        GenerateMojo mojo = new GenerateMojo();
        set(mojo, "metadataFile", metadata.toFile());
        set(mojo, "outputDirectory", output.toFile());
        set(mojo, "basePackage", basePackage);
        set(mojo, "schemaPackages", new ArrayList<SchemaMapping>());
        set(mojo, "project", new MavenProject());
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field javaField = GenerateMojo.class.getDeclaredField(field);
        javaField.setAccessible(true);
        javaField.set(target, value);
    }
}
