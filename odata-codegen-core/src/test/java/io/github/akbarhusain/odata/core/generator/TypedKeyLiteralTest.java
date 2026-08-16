package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9 (extended): generated key accessors pass the key's Edm type so literals render per
 * the OData ABNF instead of by value-shape heuristics.
 */
class TypedKeyLiteralTest {

    @Test
    void m9GeneratedKeyAccessorsPassEdmType() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel.SchemaModel schema;
        try (InputStream is = TypedKeyLiteralTest.class.getResourceAsStream("/odata-demo-metadata.xml")) {
            schema = parser.parse(is).schemas().get(0);
        }
        // Advertisement has an Edm.Guid key; Person has a String UserName key in TripPin metadata
        var advertisement = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Advertisement")).findFirst().orElseThrow();
        String code = new RequestGenerator("com.example.demo")
                .generateCollectionRequest(advertisement, schema);

        assertTrue(code.contains("addKey(\"ID\", iD, \"Edm.Guid\")"),
                "Guid keys pass their Edm type so the literal renders unquoted without heuristics. Got:\n" + code);

        StaxCsdlParser tripPinParser = new StaxCsdlParser();
        CsdlModel.SchemaModel tripPin;
        try (InputStream is = TypedKeyLiteralTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            tripPin = tripPinParser.parse(is).schemas().get(0);
        }
        var person = tripPin.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
        String personCode = new RequestGenerator("com.example.trippin")
                .generateCollectionRequest(person, tripPin);
        assertTrue(personCode.contains("addKey(\"UserName\", userName, \"Edm.String\")"),
                "String keys are always quoted — a UUID-shaped string key is no longer sent unquoted. Got:\n"
                        + personCode);
    }
}
