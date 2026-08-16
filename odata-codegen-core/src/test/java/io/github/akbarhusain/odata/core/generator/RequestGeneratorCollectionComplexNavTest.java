package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7: isComplexTypeNav received the raw nav type, so a COLLECTION nav to a complex
 * type (Type="Collection(NS.HomeAddress)") never matched the type-kind map and the
 * generator emitted references to a CollectionRequest class that is only generated
 * for entity types — uncompilable output. Both single- and collection-valued complex
 * navs must be skipped in request generation (complex types are inline data, not
 * navigable).
 */
class RequestGeneratorCollectionComplexNavTest {

    static CsdlModel.SchemaModel schema;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorCollectionComplexNavTest.class
                .getResourceAsStream("/multi-schema-same-prop-name-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals("Dup.Main"))
                    .findFirst().orElseThrow();
        }
    }

    private String generatePersonEntityRequest() {
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
        return new RequestGenerator("com.example.dup").generateEntityRequest(person, schema);
    }

    @Test
    void collectionComplexNavDoesNotEmitCollectionRequestReferences() {
        String code = generatePersonEntityRequest();

        assertFalse(code.contains("HomeAddressCollectionRequest"),
                "collection nav to a complex type must not reference a CollectionRequest"
                        + " (only generated for entity types). Got:\n" + code);
    }

    @Test
    void collectionComplexNavDoesNotEmitNavOrRefMethods() {
        String code = generatePersonEntityRequest();

        assertFalse(code.contains("public HomeAddressCollectionRequest locations("),
                "no nav method for collection-of-complex navs");
        assertFalse(code.contains("addLocationsRef"),
                "no $ref add method for collection-of-complex navs");
        assertFalse(code.contains("removeLocationsRef"),
                "no $ref remove method for collection-of-complex navs");
        // Single-valued complex navs remain skipped as before (lesson 68)
        assertFalse(code.contains("public WorkAddressEntityRequest work("),
                "single-valued complex navs stay skipped");
    }
}
