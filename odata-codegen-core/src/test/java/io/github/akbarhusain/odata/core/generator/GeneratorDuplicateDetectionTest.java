package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M18: two types mapping to the same output file must fail generation loudly instead of
 * silently overwriting. M20: members of one type that collide after Java-name mapping
 * (case folding, constant-case folding, reserved-name suffixes) must fail with a clear
 * error instead of generating a class that doesn't compile.
 */
class GeneratorDuplicateDetectionTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String ENTITY(String name, String... props) {
        StringBuilder sb = new StringBuilder();
        sb.append("<EntityType Name=\"").append(name).append("\">");
        for (String prop : props) {
            String[] parts = prop.split(":", 2);
            sb.append("<Property Name=\"").append(parts[0]).append("\" Type=\"").append(parts[1]).append("\"/>");
        }
        sb.append("</EntityType>");
        return sb.toString();
    }

    private static String schema(String namespace, String... types) {
        return "<Schema Namespace=\"" + namespace + "\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">"
                + String.join("", types) + "</Schema>";
    }

    private static String edmx(String... schemas) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">"
                + "<edmx:DataServices>" + String.join("", schemas) + "</edmx:DataServices></edmx:Edmx>";
    }

    @Test
    void m18SameOutputFileFromTwoSchemasFailsLoudly(@TempDir Path tempDir) throws Exception {
        CsdlModel model = parse(edmx(
                schema("Ns.A", ENTITY("Thing", "Id:Edm.Int32")),
                schema("Ns.B", ENTITY("Thing", "Name:Edm.String"))));

        Generator generator = new Generator(tempDir, java.util.Map.of(), "com.example.shared");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(model));
        assertTrue(ex.getMessage().contains("Duplicate generated class"),
                "silent overwrite must become a generation error: " + ex.getMessage());
    }

    @Test
    void m20CaseFoldedFieldCollisionFailsWithBothNames() throws Exception {
        CsdlModel model = parse(edmx(schema("Ns.A",
                ENTITY("Person", "Name:Edm.String", "name:Edm.Int32"))));

        Generator generator = new Generator(tempDir(), java.util.Map.of(), "com.example.dup");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> generator.generate(model));
        assertTrue(ex.getMessage().contains("'Name'") && ex.getMessage().contains("'name'"),
                "error must name both colliding members: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("field 'name'"),
                "error must name the collided field: " + ex.getMessage());
    }

    @Test
    void h6ConstantCaseCollisionsAutoDedup() throws Exception {
        // 'value' and 'VALUE' map to distinct fields (value / vALUE) but the same
        // constant VALUE — now deduplicated with a deterministic suffix instead of
        // generating duplicate constants that don't compile
        CsdlModel model = parse(edmx(schema("Ns.A",
                ENTITY("Trip", "value:Edm.String", "VALUE:Edm.String"))));

        Path dir = tempDir();
        new Generator(dir, java.util.Map.of(), "com.example.dup").generate(model);
        String code = java.nio.file.Files.readString(dir.resolve(
                "com/example/dup/entity/Trip.java"));

        assertTrue(code.contains("StringProperty<Trip> VALUE ")
                        || code.contains("StringProperty<Trip> VALUE ="),
                "first member keeps the natural constant. Got:\n" + code);
        assertTrue(code.contains("StringProperty<Trip> VALUE_2"),
                "colliding member gets a deterministic _2 suffix. Got:\n" + code);
    }

    @Test
    void m20DistinctMembersStillGenerate() throws Exception {
        CsdlModel model = parse(edmx(schema("Ns.A",
                ENTITY("Person", "Name:Edm.String", "Age:Edm.Int32"))));
        Generator generator = new Generator(tempDir(), java.util.Map.of(), "com.example.ok");
        assertDoesNotThrow(() -> generator.generate(model));
    }

    private Path tempDir() {
        return Path.of("target", "dup-detection-test-" + System.nanoTime());
    }
}
