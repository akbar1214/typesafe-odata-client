package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One navigation, one entry, at the REQUEST level too. The runtime {@link
 * io.github.akbarhusain.odata.runtime.query.Expandable} helper covers the nested builders,
 * but the generated collection/entity request classes collect their own {@code expands}
 * list — so both emitters must call the helper, or the duplicate sails through on exactly
 * the two request surfaces users touch most.
 */
class RequestGeneratorExpandDuplicateTest {

    private String collectionRequest() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            var model = new StaxCsdlParser().parse(is);
            var schema = model.schemas().get(0);
            var person = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
            return new RequestGenerator("com.example.trippin", java.util.Map.of(),
                    "com.example.trippin", model.schemas())
                    .generateCollectionRequest(person, schema);
        }
    }

    private String entityRequest() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            var model = new StaxCsdlParser().parse(is);
            var schema = model.schemas().get(0);
            var person = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
            return new RequestGenerator("com.example.trippin", java.util.Map.of(),
                    "com.example.trippin", model.schemas())
                    .generateEntityRequest(person, schema);
        }
    }

    @Test
    void collectionRequestRejectsASecondEntryForOneNavigation() throws Exception {
        String code = collectionRequest();
        assertTrue(code.contains("Expandable.requireDistinctExpand(next.expands, rendered);"),
                "collection expand must check each item against the ones already collected: " + code);
    }

    @Test
    void entityRequestRejectsASecondEntryForOneNavigation() throws Exception {
        String code = entityRequest();
        assertTrue(code.contains("Expandable.requireDistinctExpand(next.expands, rendered);"),
                "entity expand must check each item against the ones already collected: " + code);
    }

    /**
     * The check must run BEFORE the item is appended — otherwise two colliding arguments
     * in one call (expand(A, A)) would each see an empty list.
     */
    @Test
    void checkPrecedesTheAppend() throws Exception {
        for (String code : new String[] { collectionRequest(), entityRequest() }) {
            int check = code.indexOf("Expandable.requireDistinctExpand(next.expands, rendered);");
            int append = code.indexOf("if (!next.expands.contains(rendered)) next.expands.add(rendered);");
            assertTrue(check >= 0 && append > check,
                    "the duplicate check must precede the append: " + code);
        }
    }

    /** Exact duplicates stay idempotent, so the existing contains() guard must survive. */
    @Test
    void exactDuplicateIsStillCollapsed() throws Exception {
        for (String code : new String[] { collectionRequest(), entityRequest() }) {
            assertTrue(code.contains("if (!next.expands.contains(rendered)) next.expands.add(rendered);"),
                    "an identical repeat stays a silent no-op: " + code);
        }
    }
}