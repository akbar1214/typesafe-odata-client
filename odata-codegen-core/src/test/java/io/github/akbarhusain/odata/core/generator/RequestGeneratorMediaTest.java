package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class RequestGeneratorMediaTest {

    private static final String NAMESPACE = "ODataDemo";

    private CsdlModel.SchemaModel schema() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorMediaTest.class
                .getResourceAsStream("/odata-demo-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            return model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private String generateRequest(String entityName) throws Exception {
        CsdlModel.SchemaModel schema = schema();
        CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                .filter(e -> e.name().equals(entityName))
                .findFirst()
                .orElseThrow();
        return new RequestGenerator("com.example.odata").generateEntityRequest(type, schema);
    }

    @Test
    void mediaEntityGetsStreamAndSetMedia() throws Exception {
        String code = generateRequest("Advertisement");
        assertTrue(code.contains("public java.io.InputStream streamMedia()"),
                "HasStream entity should expose streamMedia()");
        assertTrue(code.contains("public void setMedia(java.io.InputStream content)"),
                "HasStream entity should expose setMedia(InputStream)");
        assertTrue(code.contains("public void setMedia(java.io.InputStream content, String etag)"),
                "HasStream entity should expose setMedia(InputStream, etag)");
        assertTrue(code.contains("EntityOperations.streamMedia(context, contextPath.addSegment(\"$value\"))"),
                "streamMedia should GET the entity's $value segment");
        assertTrue(code.contains("EntityOperations.putMedia(context, contextPath.addSegment(\"$value\")"),
                "setMedia should PUT the entity's $value segment");
    }

    @Test
    void namedStreamPropertyGetsStreamAndSet() throws Exception {
        String code = generateRequest("PersonDetail");
        assertTrue(code.contains("public java.io.InputStream streamPhoto()"),
                "Edm.Stream property Photo should expose streamPhoto()");
        assertTrue(code.contains("public void setPhoto(java.io.InputStream content)"),
                "Edm.Stream property Photo should expose setPhoto(InputStream)");
        assertTrue(code.contains("EntityOperations.streamMedia(context, contextPath.addSegment(\"Photo\"))"),
                "Named stream should target <property> (the media resource itself, no /$value)");
    }

    @Test
    void nonMediaEntityHasNoStreamMethods() throws Exception {
        String code = generateRequest("Product");
        assertFalse(code.contains("streamMedia"),
                "Plain entity should not expose streamMedia()");
        assertFalse(code.contains("setMedia"),
                "Plain entity should not expose setMedia()");
        assertFalse(code.contains("Edm.Stream"),
                "Plain entity should not reference stream methods");
    }
}
