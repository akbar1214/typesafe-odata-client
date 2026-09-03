package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD) correctness batch:
 * 1. validatePackage must reject Java KEYWORDS as package segments
 *    (com.class passes identifier-syntax checks but "package com.class;" does not compile)
 *    — but only HARD keywords: contextual keywords like record/to/open are LEGAL as
 *    package segments (javac-verified), and Names.toPackageName lowercases schema
 *    namespaces, so rejecting them breaks legal metadata.
 * 2. CSDL names embedded in generated string literals (@JsonProperty, changed-fields,
 *    toString, mergeChanged) must be Java-escaped — a raw quote/backslash breaks
 *    compilation of the generated file (unclosed string literal).
 */
class GeneratorCorrectnessTest {

    @Test
    void packageSegmentThatIsJavaKeywordIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("com.class"),
                "segment 'class' is a Java keyword — 'package com.class;' does not compile");
    }

    @Test
    void packageSegmentThatIsPrimitiveKeywordIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("com.int"),
                "segment 'int' is a Java keyword");
    }

    @Test
    void packageSegmentThatIsKeywordLiteralIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("com.true"),
                "'true'/'false'/'null' are literals — illegal as package segments");
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("com.continue"),
                "'continue' is a hard keyword (JLS 3.9)");
    }

    @Test
    void packageSegmentThatIsUnderscoreIsRejected() {
        // '_' is a reserved keyword since Java 9 — 'package com._;' does not compile
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("com._"),
                "'_' is a hard keyword (Java 9+)");
        assertThrows(IllegalArgumentException.class,
                () -> Generator.validatePackage("_"));
    }

    @Test
    void underscoreNamedPropertyGeneratesCompilableIdentifier(@TempDir Path tmp) throws Exception {
        // a CSDL property named '_' must rename to '__' (the lone underscore is an
        // illegal identifier since Java 9) — and the output must compile
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("_", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));

        String code = Files.readString(out.resolve("com/example/entity/Foo.java"));
        assertFalse(code.contains(" String _;") && !code.contains(" String __;"),
                "the lone-underscore field is illegal since Java 9:\n" + code);
        assertTrue(code.contains(" String __;"),
                "the '_' member must rename to '__' like any reserved word:\n" + code);

        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "entity with a '_' property must compile. Errors:\n" + errors);
    }

    @Test
    void contextualKeywordPackageSegmentsAreAccepted() {
        // record/to/open/yield/when are CONTEXTUAL keywords: "package com.record;"
        // compiles (javac-verified). Rejecting them regressed legal metadata —
        // Names.toPackageName lowercases schema namespaces ("Record" -> "record").
        assertDoesNotThrow(() -> Generator.validatePackage("com.record"));
        assertDoesNotThrow(() -> Generator.validatePackage("com.to"));
        assertDoesNotThrow(() -> Generator.validatePackage("com.open"));
        assertDoesNotThrow(() -> Generator.validatePackage("record"));
        assertDoesNotThrow(() -> Generator.validatePackage("com.sealed.util"));
    }

    @Test
    void validPackageStillAccepted() throws Exception {
        assertDoesNotThrow(() -> Generator.validatePackage("com.example"));
        assertDoesNotThrow(() -> Generator.validatePackage("com_example_model"));
    }

    @Test
    void schemaNamespaceDerivingContextualKeywordPackageStillGenerates(@TempDir Path tmp) throws Exception {
        // End-to-end regression for the toPackageName fallback: namespace "Record"
        // lowercases to the contextual keyword "record" — legal, must generate
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("Record", null,
                List.of(entity), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, java.util.Map.of()).generate(new CsdlModel(List.of(schema), List.of()));

        assertTrue(Files.exists(out.resolve("record/entity/Foo.java")),
                "namespace 'Record' derives package 'record' — legal, must generate");
    }

    @Test
    void hostilePropertyNameIsEscapedInGeneratedLiterals(@TempDir Path tmp) throws Exception {
        // CSDL NCNames cannot legally contain quotes, but lenient/hand-built models and
        // non-conformant feeds reach the generators — raw embedding produces an
        // unclosed string literal in the OUTPUT (caught only by javac, lesson 120).
        String hostile = "A\"B\\C";
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel(hostile, "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));

        String code = Files.readString(out.resolve("com/example/entity/Foo.java"));

        // The wire name must survive with Java escaping: A\"B\\C
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"A\\\"B\\\\C\")"),
                "JsonProperty wire name must be Java-escaped:\n" + code);
        assertTrue(code.contains("changed.add(\"A\\\"B\\\\C\")")
                        || code.contains("mergeChanged(changedFields, \"A\\\"B\\\\C\")"),
                "changed-fields wire name must be Java-escaped:\n" + code);
    }
}
