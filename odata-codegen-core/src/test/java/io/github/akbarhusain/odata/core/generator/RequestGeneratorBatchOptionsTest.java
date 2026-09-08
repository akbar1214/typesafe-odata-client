package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch views of a request must mean the same thing as its direct execution:
 * {@code select(...).expand(...).toBatchOperation()} on an ENTITY request silently
 * dropped the query options (the collection request already honored them), and
 * {@code patchToBatchOperation} sent the FULL body while {@code patch()} sends only
 * the tracked changes (decision 51). Also: negative $top/$skip render invalid OData
 * and are rejected everywhere else (NavQuery, ApplyBuilder) — collection requests
 * were the gap.
 */
class RequestGeneratorBatchOptionsTest {

    private final CsdlModel.SchemaModel schema;
    private final CsdlModel.EntityTypeModel person;

    RequestGeneratorBatchOptionsTest() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        schema = model.schemas().get(0);
        person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
    }

    private String entityRequest() {
        return new RequestGenerator("com.example.trippin").generateEntityRequest(person, schema);
    }

    private String collectionRequest() {
        return new RequestGenerator("com.example.trippin").generateCollectionRequest(person, schema);
    }

    @Test
    void entityGetBatchOperationCarriesSelectAndExpand() {
        String code = entityRequest();
        assertTrue(code.contains("    public BatchOperation toBatchOperation() {\n"
                        + "        return BatchOperation.get(buildContext().toRelativeUrl());\n"),
                "entity GET in a batch must render the same URL as get(): " + snippet(code, "toBatchOperation()"));
    }

    @Test
    void entityPatchBatchOperationSendsOnlyTrackedChanges() {
        String code = entityRequest();
        String method = snippet(code, "patchToBatchOperation(Person entity, String etag)");
        assertTrue(method.contains("entity.getChangedFields()"),
                "batch PATCH must consult the tracked changes like patch() does: " + method);
        assertTrue(method.contains("context.serializer().serialize(entity, Person.class, changed)"),
                "partial body through the Serializer's includeFields overload: " + method);
        assertTrue(method.contains("context.serializer().serialize(entity, Person.class)"),
                "full-body fallback when nothing is tracked (safe merge semantics): " + method);
    }

    @Test
    void entityPatchBatchOperationAcceptsAnETag() {
        String code = entityRequest();
        assertTrue(code.contains("public BatchOperation patchToBatchOperation(Person entity, String etag)"),
                "conditional PATCH inside a batch needs the If-Match overload");
        assertTrue(code.contains("BatchOperation.patch(contextPath.toRelativeUrl(), body, etag)"));
    }

    @Test
    void collectionTopAndSkipRejectNegatives() {
        String code = collectionRequest();
        String top = snippet(code, "top(int count)");
        String skip = snippet(code, "skip(int count)");
        assertTrue(top.contains("if (count < 0)") && top.contains("IllegalArgumentException"),
                "$top must be >= 0 (parity with NavQuery/ApplyBuilder): " + top);
        assertTrue(skip.contains("if (count < 0)") && skip.contains("IllegalArgumentException"),
                "$skip must be >= 0 (parity with NavQuery/ApplyBuilder): " + skip);
    }

    private static String snippet(String code, String marker) {
        int at = code.indexOf(marker);
        assertTrue(at >= 0, "marker not found: " + marker);
        int end = code.indexOf("\n    }\n", at);
        return code.substring(at, end < 0 ? code.length() : end);
    }
}
