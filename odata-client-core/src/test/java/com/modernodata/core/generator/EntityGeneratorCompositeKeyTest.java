package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel;
import com.modernodata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class EntityGeneratorCompositeKeyTest {

    private static final String NAMESPACE = "NorthwindModel";

    private String generateOrderDetail() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorCompositeKeyTest.class
                .getResourceAsStream("/northwind-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel orderDetail = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Order_Detail"))
                    .findFirst()
                    .orElseThrow();
            return new EntityGenerator("com.example.northwind").generate(orderDetail, schema);
        }
    }

    @Test
    void compositeKeyReturnsMapOfAllKeyProperties() throws Exception {
        String code = generateOrderDetail();

        int idx = code.indexOf("public Object getKey()");
        assertTrue(idx >= 0, "getKey() method should be present");
        int end = code.indexOf("    }\n\n", idx);
        String getKeyMethod = code.substring(idx, end);

        assertTrue(getKeyMethod.contains("java.util.Map.of("),
                "getKey() for composite keys should return java.util.Map.of(...)");
        assertTrue(getKeyMethod.contains("\"OrderID\", orderID"),
                "getKey() should include OrderID mapping");
        assertTrue(getKeyMethod.contains("\"ProductID\", productID"),
                "getKey() should include ProductID mapping");
        assertFalse(getKeyMethod.contains("return orderID"),
                "getKey() should NOT return a single field value for composite keys");
    }
}
