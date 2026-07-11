package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ComplexTypeGeneratorInheritanceTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateComplexType(String typeName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = ComplexTypeGeneratorInheritanceTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.ComplexTypeModel type = schema.complexTypes().stream()
                    .filter(c -> c.name().equals(typeName))
                    .findFirst()
                    .orElseThrow();
            return new ComplexTypeGenerator("com.example.trippin").generate(type, schema);
        }
    }

    @Test
    void subtypeExtendsBaseComplexType() throws Exception {
        String code = generateComplexType("EventLocation");

        assertTrue(code.contains("public class EventLocation extends Location"),
                "EventLocation should extend its base complex type Location");
        assertTrue(code.contains("implements ODataType"),
                "Complex type should still implement ODataType");

        // Constructor must chain super() with the inherited properties first
        int ctorIdx = code.indexOf("public EventLocation(");
        assertTrue(ctorIdx >= 0, "EventLocation constructor should be present");
        int superIdx = code.indexOf("super(", ctorIdx);
        assertTrue(superIdx > ctorIdx, "EventLocation constructor should call super(...)");
        String superCall = code.substring(superIdx, code.indexOf(");", superIdx) + 2);
        assertTrue(superCall.contains("address"), "super() should pass inherited Address");
        assertTrue(superCall.contains("city"), "super() should pass inherited City");
        assertFalse(superCall.contains("buildingInfo"),
                "super() should NOT pass own property BuildingInfo");

        // Own property is assigned in the constructor body
        assertTrue(code.contains("this.buildingInfo = buildingInfo;"),
                "Own property should be assigned in the constructor body");

        // Own getter only; inherited getters come from the parent
        assertTrue(code.contains("public Optional<String> getBuildingInfo()"),
                "Own getter should be generated");
        assertFalse(code.contains("public Optional<String> getAddress()"),
                "Inherited getter should NOT be re-generated");

        // toString includes both inherited and own properties
        assertTrue(code.contains("Address=\" + address"),
                "toString should include inherited Address");
        assertTrue(code.contains("BuildingInfo=\" + buildingInfo"),
                "toString should include own BuildingInfo");
    }

    @Test
    void subtypeUsesWithMethodsForCopyOnWrite() throws Exception {
        String code = generateComplexType("EventLocation");

        // Subtypes get with*() (not a Builder, to avoid clashing with the inherited builder())
        assertFalse(code.contains("public static Builder builder()"),
                "Subtype should not declare its own builder() (would clash with inherited)");

        int withIdx = code.indexOf("public EventLocation withBuildingInfo(String value)");
        assertTrue(withIdx >= 0, "Own with* method should be generated");
        int bodyEnd = code.indexOf("    }\n\n", withIdx);
        String body = code.substring(withIdx, bodyEnd);
        assertTrue(body.contains("new EventLocation(this.address, this.city, value"),
                "with* should reconstruct via the all-args constructor including inherited fields");

        // Inherited with* is also generated and threads its own value alongside inherited fields
        assertTrue(code.contains("public EventLocation withAddress(String value)"),
                "Inherited with* should be generated for the subtype");
        int withAddrIdx = code.indexOf("public EventLocation withAddress(String value)");
        int addrBodyEnd = code.indexOf("    }\n\n", withAddrIdx);
        String addrBody = code.substring(withAddrIdx, addrBodyEnd);
        assertTrue(addrBody.contains("new EventLocation(value, this.city, this.buildingInfo"),
                "Inherited with* should pass value for the changed prop and inherited fields by name");
    }

    @Test
    void baseComplexTypeHasBuilderAndNoExtends() throws Exception {
        String code = generateComplexType("Location");
        assertFalse(code.contains("extends"),
                "Root complex type should not declare an extends clause");
        assertTrue(code.contains("public static Builder builder()"),
                "Root complex type should get a Builder");
        assertTrue(code.contains("public Location withAddress(String value)"),
                "Root complex type should get with* methods too");
    }
}
