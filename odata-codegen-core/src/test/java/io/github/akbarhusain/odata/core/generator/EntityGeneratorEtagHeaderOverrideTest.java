package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7 (generator side): root entity classes must override applyETagFromResponse so the
 * runtime can capture a header-only ETag onto the entity after GET (the field is
 * root-declared, so only roots emit the override).
 */
class EntityGeneratorEtagHeaderOverrideTest {

    private String generatePerson() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person"))
                .findFirst()
                .orElseThrow();
        return new EntityGenerator("com.example.trippin").generate(person, schema);
    }

    @Test
    void rootEntityOverridesApplyEtagFromResponse() throws Exception {
        String code = generatePerson();
        assertTrue(code.contains("@Override\n    public void applyETagFromResponse(String value)"),
                "root entity must override applyETagFromResponse to receive the header-captured etag");
        assertTrue(code.contains("this.etag = value;"),
                "override must store the value in the etag lifecycle field");
    }

    @Test
    void overrideIgnoresBlankValues() throws Exception {
        String code = generatePerson();
        assertTrue(code.contains("value != null && !value.isEmpty()"),
                "blank/absent headers must not clobber an existing etag");
    }
}
