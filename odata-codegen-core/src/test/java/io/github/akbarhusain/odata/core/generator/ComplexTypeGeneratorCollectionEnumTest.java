package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ComplexTypeGeneratorCollectionEnumTest {

    private static final String NAMESPACE = "Test.Models";

    private String generateComplexType(String typeName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = ComplexTypeGeneratorCollectionEnumTest.class
                .getResourceAsStream("/complex-collection-enum-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.ComplexTypeModel type = schema.complexTypes().stream()
                    .filter(c -> c.name().equals(typeName))
                    .findFirst()
                    .orElseThrow();
            return new ComplexTypeGenerator("com.example.test").generate(type, schema);
        }
    }

    @Test
    void collectionPropertyUsesListType() throws Exception {
        String code = generateComplexType("Detail");
        assertTrue(code.contains("List<String> tags"),
                "Collection(Edm.String) should map to List<String>, but was: " +
                code.substring(Math.max(0, code.indexOf("tags") - 30), code.indexOf("tags") + 6));
    }

    @Test
    void enumPropertyUsesEnumType() throws Exception {
        String code = generateComplexType("Detail");
        assertTrue(code.contains("Color color"),
                "Enum property should use the enum class name Color, not the complex type package");
    }

    @Test
    void enumPropertyHasCorrectImport() throws Exception {
        String code = generateComplexType("Detail");
        assertTrue(code.contains("import com.example.test.enums.Color;"),
                "Enum type should be imported from the enums package");
    }

    @Test
    void collectionGetterReturnsUnmodifiableList() throws Exception {
        String code = generateComplexType("Detail");
        assertTrue(code.contains("public List<String> getTags()"),
                "Collection getter should return List<String>");
        assertTrue(code.contains("Collections.unmodifiableList(tags)"),
                "Collection getter should wrap with unmodifiableList");
    }
}
