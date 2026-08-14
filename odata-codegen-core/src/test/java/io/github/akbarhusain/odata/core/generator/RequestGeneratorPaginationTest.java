package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class RequestGeneratorPaginationTest {

    private String generatePeopleCollectionRequest() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person"))
                .findFirst()
                .orElseThrow();
        return new RequestGenerator("com.example.trippin").generateCollectionRequest(person, schema);
    }

    @Test
    void collectionRequestHasNextPageMethod() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("public PersonCollectionRequest nextPage(String nextLink)"),
                "Collection request should expose nextPage(String)");
        assertTrue(code.contains("contextPath.fromNextLink(nextLink)"),
                "nextPage should resolve the OData nextLink via ContextPath");
    }

    @Test
    void collectionRequestHasCountValueMethod() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("public long countValue()"),
                "Collection request should expose countValue() for GET /Person/$count");
        assertTrue(code.contains("EntityOperations.executeCount(context, tmp.buildContext())"),
                "countValue should delegate to EntityOperations.executeCount");
    }

    @Test
    void countValueClearsInlineCountFlag() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("tmp.countRequested = false;"),
                "countValue should clear the $count=true query flag before appending /$count segment");
    }

    @Test
    void countValueClearsTopAndSkip() throws Exception {
        // M2: $count must not carry $top/$skip (forbidden by the OData spec).
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("tmp.topValue = null;"),
                "countValue should clear $top before appending /$count segment");
        assertTrue(code.contains("tmp.skipValue = null;"),
                "countValue should clear $skip before appending /$count segment");
    }

    @Test
    void countStillEmitsInlineCountQuery() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("ctx = ctx.addQuery(\"$count\", \"true\")"),
                "count() should still emit $count=true for inline count");
    }
}
