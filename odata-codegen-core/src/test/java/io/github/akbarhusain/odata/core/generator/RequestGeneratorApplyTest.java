package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class RequestGeneratorApplyTest {

    private static final String NAMESPACE = "ODataDemo";

    private String generateCollectionRequest(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorApplyTest.class
                .getResourceAsStream("/odata-demo-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                    .filter(e -> e.name().equals(entityName))
                    .findFirst()
                    .orElseThrow();
            return new RequestGenerator("com.example.odata").generateCollectionRequest(type, schema);
        }
    }

    @Test
    void collectionRequestExposesApplyMethods() throws Exception {
        String code = generateCollectionRequest("Product");
        assertTrue(code.contains("public ProductCollectionRequest apply(ApplyExpression expr)"),
                "Collection request should expose apply(ApplyExpression)");
        assertTrue(code.contains("public ProductCollectionRequest apply(String raw)"),
                "Collection request should expose apply(String) raw overload");
    }

    @Test
    void applyRendersApplyQueryOption() throws Exception {
        String code = generateCollectionRequest("Product");
        assertTrue(code.contains("ctx = ctx.addQuery(\"$apply\", applyExpr);"),
                "apply() result should be emitted as the $apply query option");
        assertTrue(code.contains("next.applyExpr = expr.toODataApply();"),
                "apply(ApplyExpression) should render the expression");
        assertTrue(code.contains("next.applyExpr = ApplyExpression.of(raw).toODataApply();"),
                "apply(String) should wrap the raw value via ApplyExpression.of");
    }

    @Test
    void copyPreservesApplyExpr() throws Exception {
        String code = generateCollectionRequest("Product");
        assertTrue(code.contains("c.applyExpr = applyExpr;"),
                "copy() must carry the $apply expression forward");
    }
}
