package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key property without {@code Nullable="false"} is invalid CSDL — "Key properties MUST
 * NOT be nullable" and "If no value is specified for a single-valued property, the
 * Nullable attribute defaults to true" (CSDL XML 4.01, §§6.5 / 7.2.1). Rejecting it is
 * correct and is the project's loud-failure-at-the-metadata-boundary policy.
 *
 * <p>What was wrong was the MESSAGE. It said "key property 'Id' must be non-null" and
 * stopped there. The user-facing remedy is a one-attribute edit to their metadata, and
 * nothing in the message said so — a reader had to know that the implicit default is
 * {@code true} and therefore that the attribute had to be written out explicitly. That is
 * the cost the project already accepted elsewhere: an error that names the offending
 * element but not the edit is only half an error message.
 */
class KeyPropertyNullabilityMessageTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static IllegalStateException generateExpectingFailure(Path out, String propertyXml)
            throws java.io.IOException {
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"N\">"
                + "<EntityType Name=\"Thing\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + propertyXml
                + "</EntityType>"
                + "<EntityContainer Name=\"C\">"
                + "<EntitySet Name=\"Things\" EntityType=\"N.Thing\"/>"
                + "</EntityContainer></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
        Path file = out.resolve("metadata.xml");
        try {
            Files.writeString(file, doc, StandardCharsets.UTF_8);
            CsdlModelBridge.generate(out, file);
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected generation to reject a nullable key property");
    }

    /** Small indirection so the test reads as one concern rather than a build recipe. */
    static final class CsdlModelBridge {
        static void generate(Path out, Path metadataFile) {
            try {
                var model = new io.github.akbarhusain.odata.core.parser.StaxCsdlParser()
                        .parse(Files.newInputStream(metadataFile));
                new Generator(out, Map.of(), "com.n").generate(model);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (javax.xml.stream.XMLStreamException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void messageNamesTheNullableFacetAndTheExactFix(@TempDir Path out) throws java.io.IOException {
        IllegalStateException error = generateExpectingFailure(out,
                "<Property Name=\"Id\" Type=\"Edm.String\"/>");

        String message = error.getMessage();
        assertTrue(message.contains("Nullable=\"false\""),
                "the message must show the exact attribute the user has to add: " + message);
        assertTrue(message.contains("Id"),
                "the message must name the offending property: " + message);
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("default"),
                "the message must explain WHY an explicit attribute is needed -- the facet "
                        + "defaults to true when unspecified, which is the whole puzzle: " + message);
    }

    @Test
    void stillRejectsWhenThePropertyExplicitlySaysNullableTrue(@TempDir Path out) throws java.io.IOException {
        IllegalStateException error = generateExpectingFailure(out,
                "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"true\"/>");
        assertTrue(error.getMessage().contains("Nullable=\"false\""),
                "an explicit Nullable=\"true\" is equally invalid and equally fixable: "
                        + error.getMessage());
    }

    @Test
    void aCorrectlyAnnotatedKeyIsAccepted(@TempDir Path out) throws Exception {
        // The regression witness: the check itself must NOT be relaxed. Only the message
        // changes -- a client generated from a nullable key would build URLs with a null
        // segment, so failing loudly here is the correct behaviour.
        Path dir = Files.createTempDirectory("goodkey");
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"N\">"
                + "<EntityType Name=\"Thing\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                + "</EntityType></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
        Path file = dir.resolve("m.xml");
        Files.writeString(file, doc, StandardCharsets.UTF_8);
        CsdlModelBridge.generate(dir, file);
        assertTrue(Files.exists(dir.resolve("com/n/entity/Thing.java")),
                "Nullable=\"false\" must generate normally");
    }
}
