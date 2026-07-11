package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class EntityGeneratorCollectionGetterTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generatePerson() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorCollectionGetterTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Person"))
                    .findFirst()
                    .orElseThrow();
            return new EntityGenerator("com.example.trippin").generate(person, schema);
        }
    }

    @Test
    void collectionPropertiesGetReadableGetters() throws Exception {
        String code = generatePerson();

        // Bug #1: collection-typed properties must expose readable getters.
        assertTrue(code.contains("public List<String> getEmails()"),
                "Person should have a getEmails() getter for the Collection(Edm.String) property");
        assertTrue(code.contains("public List<Location> getAddressInfo()"),
                "Person should have a getAddressInfo() getter for the Collection(Location) property");
    }

    @Test
    void collectionPropertiesAreStoredImmutably() throws Exception {
        String code = generatePerson();

        // Immutable read: getter returns an unmodifiable view.
        assertTrue(code.contains("Collections.unmodifiableList(emails)"),
                "getEmails() should return Collections.unmodifiableList(...)");
        assertTrue(code.contains("Collections.unmodifiableList(addressInfo)"),
                "getAddressInfo() should return Collections.unmodifiableList(...)");
    }
}
