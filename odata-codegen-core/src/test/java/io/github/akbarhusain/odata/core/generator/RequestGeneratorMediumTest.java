package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4: Collection-of-complex typedef nav not skipped
 */
class RequestGeneratorMediumTest {

    @Test
    void m4_typedefOfComplexNavIsSkipped() throws Exception {
        ComplexTypeModel address = new ComplexTypeModel("Address", null, false, false, List.of(
                new PropertyModel("Street", "Edm.String", true, null, List.of())
        ), List.of());

        SchemaModel shared = new SchemaModel("NS.Shared", null,
                List.of(), List.of(address), List.of(),
                List.of(new TypeDefinitionModel("MyAddr", "NS.Shared.Address")),
                List.of(), List.of(), List.of());

        EntityTypeModel foo = new EntityTypeModel("Foo", null, false, false, false,
                List.of(new KeyModel(List.of("Id"))),
                List.of(new PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new NavigationPropertyModel("MyNav", "NS.Shared.MyAddr", null, false, true, List.of(), List.of())));

        SchemaModel test = new SchemaModel("NS.Test", null,
                List.of(foo), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new ContainerModel("Container", null,
                        List.of(new EntitySetModel("Foos", "NS.Test.Foo", List.of(), List.of())), List.of(), List.of(), List.of())));

        RequestGenerator gen = new RequestGenerator("com.test", Map.of(), "com.test", List.of(shared, test));
        String code = gen.generateEntityRequest(foo, test);
        assertFalse(code.contains("MyAddrEntityRequest") || code.contains("MyAddrCollectionRequest"),
                "M4: typedef-of-complex nav should be skipped, but generated code contains MyAddr request: " + code);
        assertFalse(code.contains("MyAddr"),
                "M4: no MyAddr nav method should be generated for complex typedef: " + code);
    }

    @Test
    void m4_collectionTypedefOfComplexNavIsSkipped() throws Exception {
        ComplexTypeModel address = new ComplexTypeModel("Address", null, false, false, List.of(
                new PropertyModel("Street", "Edm.String", true, null, List.of())
        ), List.of());

        SchemaModel shared = new SchemaModel("NS.Shared", null,
                List.of(), List.of(address), List.of(),
                List.of(new TypeDefinitionModel("MyAddrs", "NS.Shared.Address")),
                List.of(), List.of(), List.of());

        EntityTypeModel foo = new EntityTypeModel("Foo", null, false, false, false,
                List.of(new KeyModel(List.of("Id"))),
                List.of(new PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new NavigationPropertyModel("Addrs", "Collection(NS.Shared.MyAddrs)", null, false, true, List.of(), List.of())));

        SchemaModel test = new SchemaModel("NS.Test", null,
                List.of(foo), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new ContainerModel("Container", null,
                        List.of(new EntitySetModel("Foos", "NS.Test.Foo", List.of(), List.of())), List.of(), List.of(), List.of())));

        RequestGenerator gen = new RequestGenerator("com.test", Map.of(), "com.test", List.of(shared, test));
        String code = gen.generateEntityRequest(foo, test);
        assertFalse(code.contains("MyAddrs"),
                "M4: Collection typedef-of-complex nav should be skipped: " + code);
    }

    @Test
    void m4_directComplexNavStillSkipped() throws Exception {
        ComplexTypeModel address = new ComplexTypeModel("Address", null, false, false, List.of(
                new PropertyModel("Street", "Edm.String", true, null, List.of())
        ), List.of());

        EntityTypeModel foo = new EntityTypeModel("Foo", null, false, false, false,
                List.of(new KeyModel(List.of("Id"))),
                List.of(new PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new NavigationPropertyModel("Addr", "NS.Test.Address", null, false, true, List.of(), List.of())));

        SchemaModel s = new SchemaModel("NS.Test", null,
                List.of(foo),
                List.of(address), List.of(), List.of(), List.of(), List.of(),
                List.of(new ContainerModel("Container", null, List.of(new EntitySetModel("Foos", "NS.Test.Foo", List.of(), List.of())), List.of(), List.of(), List.of())));

        RequestGenerator gen = new RequestGenerator("com.test", Map.of(), "com.test", List.of(s));
        String code = gen.generateEntityRequest(s.entityTypes().get(0), s);
        assertFalse(code.contains("AddressEntityRequest"), "direct complex nav should be skipped");
    }
}
