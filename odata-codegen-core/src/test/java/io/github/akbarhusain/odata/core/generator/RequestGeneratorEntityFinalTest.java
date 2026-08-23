package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9: collection requests are emitted {@code public final class} (decision 36) but
 * entity requests were plain {@code public class}. Generated request classes are not
 * designed for inheritance — parity requires final on both.
 */
class RequestGeneratorEntityFinalTest {

    @Test
    void entityRequestClassIsFinal() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person"))
                .findFirst()
                .orElseThrow();
        String code = new RequestGenerator("com.example.trippin").generateEntityRequest(person, schema);
        assertTrue(code.contains("public final class PersonEntityRequest"),
                "entity request classes must be final, matching collection requests");
    }
}
