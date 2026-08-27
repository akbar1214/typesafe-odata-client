package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Key machinery after decision 95: the {@code byID}/`byKey` accessor family on
 * collection requests is REPLACED by keyed container overloads and keyed nav
 * overloads. Composite and inherited keys surface as multi-parameter overloads.
 */
class RequestGeneratorKeyTest {

    @Test
    void compositeKeyEntityKeysViaContainerOverloadWithAllKeyParams() throws Exception {
        CsdlModel model = load("/northwind-metadata.xml");
        // Order_Detail lives in NorthwindModel; the container is in ODataWebV4.Northwind.Model
        CsdlModel.SchemaModel opSchema = model.schemas().stream()
                .filter(s -> s.entityTypes().stream().anyMatch(e -> e.name().equals("Order_Detail")))
                .findFirst().orElseThrow();
        CsdlModel.SchemaModel containerSchema = model.schemas().stream()
                .filter(s -> !s.containers().isEmpty()).findFirst().orElseThrow();
        CsdlModel.EntityTypeModel orderDetail = opSchema.entityTypes().stream()
                .filter(e -> e.name().equals("Order_Detail")).findFirst().orElseThrow();

        assertTrue(keyParamsOf(orderDetail, opSchema, containerSchema).size() == 2,
                "composite key flattens to two specs");
        String containerCode = new ContainerGenerator("com.example.northwind", java.util.Map.of(),
                "com.example.northwind", model.schemas())
                .generate(containerSchema.containers().get(0), containerSchema);

        assertTrue(containerCode.contains("order_Details(Integer orderID, Integer productID)"),
                "Composite-key entity set gets one overload with ALL key params (CSDL order): "
                        + snippet(containerCode, "order_Details("));
        assertTrue(containerCode.contains(".addKey(\"OrderID\", orderID, \"Edm.Int32\")"));
        assertTrue(containerCode.contains(".addKey(\"ProductID\", productID, \"Edm.Int32\")"));
    }

    @Test
    void inheritedKeyEntityKeysViaContainerOverload() {
        // TripPin has no entity set over a subtype — synthetic: Derived inherits the key
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("V.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("VehicleBase", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Car", "V.NS.VehicleBase", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CsdlModel.ContainerModel("C",
                        List.of(new CsdlModel.EntitySetModel("Cars", "V.NS.Car", List.of(), List.of())),
                        List.of(), List.of(), List.of())));

        String containerCode = new ContainerGenerator("app", java.util.Map.of(), "app", List.of(schema))
                .generate(schema.containers().get(0), schema);

        assertTrue(containerCode.contains("public CarEntityRequest cars(Integer id)"),
                "Subtype keys with the INHERITED key (resolvedKeys walks the base chain)");
        assertTrue(containerCode.contains(".addKey(\"Id\", id, \"Edm.Int32\")"));
    }

    @Test
    void byIdAccessorFamilyIsReplacedNotDuplicated() throws Exception {
        // Option A (breaking, pre-1.0): the collection-request byKey accessors are gone —
        // keying happens at the container or on nav overloads only
        CsdlModel model = load("/trippin-metadata.xml");
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        String collectionCode = new RequestGenerator("com.example.trippin", java.util.Map.of(),
                "com.example.trippin", model.schemas()).generateCollectionRequest(person, schema);

        assertFalse(collectionCode.contains("personByUserName"),
                "byID/byKey family deleted from collection requests (decision 95, option A)");
    }

    private static CsdlModel load(String path) {
        try (InputStream is = RequestGeneratorKeyTest.class.getResourceAsStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.List<RequestGenerator.KeyParamSpec> keyParamsOf(
            CsdlModel.EntityTypeModel type, CsdlModel.SchemaModel opSchema, CsdlModel.SchemaModel containerSchema) {
        return new RequestGenerator("com.example.northwind", java.util.Map.of(),
                "com.example.northwind", java.util.List.of(containerSchema, opSchema))
                .keyParamSpecs(type, containerSchema);
    }

    private static String snippet(String code, String marker) {
        int i = code.indexOf(marker);
        return i < 0 ? "(missing " + marker + ")" : code.substring(i, Math.min(i + 120, code.length()));
    }
}
