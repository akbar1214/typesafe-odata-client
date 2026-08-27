package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9 (extended) after decision 95: keyed accessors pass the key's Edm type so literals
 * render per the OData ABNF instead of by value-shape heuristics. The accessors now
 * live as keyed container overloads (the collection-request byID family was removed).
 */
class TypedKeyLiteralTest {

    @Test
    void m9KeyedOverloadsPassEdmType() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel.SchemaModel demo;
        try (InputStream is = TypedKeyLiteralTest.class.getResourceAsStream("/odata-demo-metadata.xml")) {
            demo = parser.parse(is).schemas().get(0);
        }
        // Advertisement has an Edm.Guid key
        String containerCode = new ContainerGenerator("com.example.demo", java.util.Map.of(),
                "com.example.demo", java.util.List.of(demo))
                .generate(demo.containers().get(0), demo);

        assertTrue(containerCode.contains("addKey(\"ID\", iD, \"Edm.Guid\")"),
                "Guid keys pass their Edm type so the literal renders unquoted without heuristics. Got:\n"
                        + snippet(containerCode, "advertisements("));

        StaxCsdlParser tripPinParser = new StaxCsdlParser();
        CsdlModel.SchemaModel tripPin;
        try (InputStream is = TypedKeyLiteralTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            tripPin = tripPinParser.parse(is).schemas().get(0);
        }
        String trippinContainer = new ContainerGenerator("com.example.trippin", java.util.Map.of(),
                "com.example.trippin", java.util.List.of(tripPin))
                .generate(tripPin.containers().get(0), tripPin);
        assertTrue(trippinContainer.contains("addKey(\"UserName\", userName, \"Edm.String\")"),
                "String keys are always quoted — a UUID-shaped string key is no longer sent unquoted");
    }

    private static String snippet(String code, String marker) {
        int i = code.indexOf(marker);
        return i < 0 ? "(missing)" : code.substring(i, Math.min(i + 200, code.length()));
    }
}
