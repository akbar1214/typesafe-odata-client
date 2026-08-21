package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M13: Duplicate detection only within one generate()
 * M3 coverage via NamesMediumTest, but also test here for generator duplicate across calls
 */
class GeneratorMediumTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void m13_duplicateAcrossGenerateCallsShouldBeDetected(@TempDir Path tmp) throws Exception {
        String xml1 = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                      <EntityContainer Name="Container"><EntitySet Name="Foos" EntityType="NS.Test.Foo"/></EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        String xml2 = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/><Property Name="Extra" Type="Edm.String"/></EntityType>
                      <EntityContainer Name="Container"><EntitySet Name="Foos" EntityType="NS.Test.Foo"/></EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        Generator gen = new Generator(tmp, Map.of(), "com.test");
        gen.generate(parse(xml1));
        Path fooPath = tmp.resolve("com/test/entity/Foo.java");
        assertTrue(Files.exists(fooPath), "first generate should create Foo.java");
        String firstContent = Files.readString(fooPath);

        // Second generate with same output dir but different model: written.clear() loses previous state
        // Stale file detection should still catch duplicate or stale? Currently written cleared per call, so no detection across calls
        // After fix, stale files should be tracked or old .class cleaned
        // For test, we check that generator's writtenFiles after second call does not include stale handling,
        // but the bug is that old Foo.java with different content is silently overwritten without warning
        // We expect duplicate detection to consider previous files: here second content differs but same file
        // Should throw or at least handle stale. Currently it overwrites silently -> bug
        // We test that second generate does not silently succeed without cleaning stale state
        // After fix, should either throw duplicate or ensure stale files cleaned (e.g., via isUpToDate logic)
        // Simple check: written map is cleared, so duplicate detection won't catch same file with different content across calls if we check written only within call
        // But current code would throw on same file same content? Let's check: same file with different content should throw IllegalStateException
        // Actually written.putIfAbsent will not throw on first call second time because written cleared, so second call will just overwrite without exception
        // Expected after fix: should detect and handle (maybe delete stale or throw)
        // For failing test, we assert that second generate with different content should be detected
        // Currently it will NOT throw -> fail
        CsdlModel model2 = parse(xml2);
        // This should either throw or produce different file; currently it just overwrites without error -> we assert that it SHOULD throw or be handled
        // To make test fail before fix, we assert that file content changes but no exception (so we check that bug is present)
        // Instead, test that duplicate detection per-call is insufficient: after two calls, old content may remain if generation skipped via isUpToDate
        // Simpler: assert that written.clear() is insufficient by checking that generator does not track files across calls
        // We do: generate twice, check that Files.readString after second is different, but no exception -> bug
        gen.generate(model2);
        String secondContent = Files.readString(fooPath);
        assertNotEquals(firstContent, secondContent, "content should differ between versions");
        // Bug: no exception thrown for overwriting with different content across calls (duplicate detection missed)
        // After fix, generator should either handle stale files or keep written across calls
        // For test to fail before fix, we assert that a mechanism exists to detect stale files
        // Check GenerateMojo would handle stale, but Generator alone doesn't.
        // We make test check that Generator has a way to handle this: e.g., writtenFiles or stale cleanup
        // Before fix, Generator.writtenFiles only returns last call's files, so stale not tracked
        List<Path> written = gen.writtenFiles();
        // Currently writtenFiles size is 1 (only last call), but tmp may have stale files from previous if we had renames
        // We simulate rename: first has Foo, second has Bar (rename) -> old Foo.java should be deleted but isn't
        String xml3 = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Bar"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                      <EntityContainer Name="Container"><EntitySet Name="Bars" EntityType="NS.Test.Bar"/></EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        Generator gen2 = new Generator(tmp, Map.of(), "com.test");
        gen2.generate(parse(xml1));
        gen2.generate(parse(xml3));
        Path barPath = tmp.resolve("com/test/entity/Bar.java");
        assertTrue(Files.exists(barPath), "Bar should exist");
        // M13 bug: old Foo.java still exists (stale), not cleaned up
        boolean fooStillExists = Files.exists(fooPath);
        assertFalse(fooStillExists, "M13: stale Foo.java should have been cleaned up after rename, but it still exists (duplicate detection only per-call)");
    }
}
