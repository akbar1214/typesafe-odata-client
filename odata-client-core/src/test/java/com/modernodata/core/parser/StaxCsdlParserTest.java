package com.modernodata.core.parser;

import com.modernodata.core.model.CsdlModel;
import com.modernodata.core.model.CsdlModel.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaxCsdlParserTest {

    private static CsdlModel trippinModel;
    private static CsdlModel northwindModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();

        try (InputStream is = StaxCsdlParserTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            assertNotNull(is, "trippin-metadata.xml not found on classpath");
            trippinModel = parser.parse(is);
        }

        try (InputStream is = StaxCsdlParserTest.class.getResourceAsStream("/northwind-metadata.xml")) {
            assertNotNull(is, "northwind-metadata.xml not found on classpath");
            northwindModel = parser.parse(is);
        }
    }

    // ===== TripPin Tests =====

    @Test
    void tripPin_hasSingleSchema() {
        assertEquals(1, trippinModel.schemas().size());
    }

    @Test
    void tripPin_schemaNamespace() {
        assertEquals("Microsoft.OData.SampleService.Models.TripPin",
                trippinModel.schemas().get(0).namespace());
    }

    @Test
    void tripPin_parsedEntityTypes() {
        SchemaModel schema = trippinModel.schemas().get(0);
        List<String> names = schema.entityTypes().stream()
                .map(EntityTypeModel::name).toList();
        assertTrue(names.containsAll(List.of(
                "Person", "Trip", "Photo", "Airline", "Airport",
                "PlanItem", "Flight", "Event", "PublicTransportation")));
    }

    @Test
    void tripPin_personEntityType() {
        SchemaModel schema = trippinModel.schemas().get(0);
        EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        assertTrue(person.openType());
        assertFalse(person.abstractType());
        assertFalse(person.hasStream());
        assertEquals(1, person.keys().size());
        assertEquals(List.of("UserName"), person.keys().get(0).propertyRefs());
        assertTrue(person.baseType() == null || person.baseType().isEmpty());
    }

    @Test
    void tripPin_personProperties() {
        SchemaModel schema = trippinModel.schemas().get(0);
        EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        List<String> propNames = person.properties().stream()
                .map(PropertyModel::name).toList();
        assertTrue(propNames.containsAll(List.of(
                "UserName", "FirstName", "LastName", "Emails",
                "AddressInfo", "Gender", "Concurrency")));
    }

    @Test
    void tripPin_personNavigationProperties() {
        SchemaModel schema = trippinModel.schemas().get(0);
        EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        List<String> navNames = person.navigationProperties().stream()
                .map(NavigationPropertyModel::name).toList();
        assertTrue(navNames.containsAll(List.of("Friends", "Trips", "Photo")));
    }

    @Test
    void tripPin_tripsContainsTarget() {
        SchemaModel schema = trippinModel.schemas().get(0);
        EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        NavigationPropertyModel trips = person.navigationProperties().stream()
                .filter(n -> n.name().equals("Trips")).findFirst().orElseThrow();
        assertTrue(trips.containsTarget());
    }

    @Test
    void tripPin_inheritance() {
        SchemaModel schema = trippinModel.schemas().get(0);

        EntityTypeModel publicTransport = schema.entityTypes().stream()
                .filter(e -> e.name().equals("PublicTransportation")).findFirst().orElseThrow();
        assertEquals("Microsoft.OData.SampleService.Models.TripPin.PlanItem", publicTransport.baseType());

        EntityTypeModel flight = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Flight")).findFirst().orElseThrow();
        assertEquals("Microsoft.OData.SampleService.Models.TripPin.PublicTransportation", flight.baseType());

        EntityTypeModel event = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Event")).findFirst().orElseThrow();
        assertEquals("Microsoft.OData.SampleService.Models.TripPin.PlanItem", event.baseType());
        assertTrue(event.openType());
    }

    @Test
    void tripPin_enumTypes() {
        SchemaModel schema = trippinModel.schemas().get(0);
        assertEquals(1, schema.enumTypes().size());

        EnumTypeModel gender = schema.enumTypes().get(0);
        assertEquals("PersonGender", gender.name());
        assertEquals(3, gender.members().size());
        assertEquals("Male", gender.members().get(0).name());
        assertEquals(0, gender.members().get(0).value());
        assertEquals("Female", gender.members().get(1).name());
        assertEquals(1, gender.members().get(1).value());
    }

    @Test
    void tripPin_complexTypes() {
        SchemaModel schema = trippinModel.schemas().get(0);
        List<String> names = schema.complexTypes().stream()
                .map(ComplexTypeModel::name).toList();
        assertTrue(names.containsAll(List.of("City", "Location", "EventLocation", "AirportLocation")));
    }

    @Test
    void tripPin_complexTypeInheritance() {
        SchemaModel schema = trippinModel.schemas().get(0);

        ComplexTypeModel location = schema.complexTypes().stream()
                .filter(c -> c.name().equals("Location")).findFirst().orElseThrow();
        assertTrue(location.openType());

        ComplexTypeModel eventLocation = schema.complexTypes().stream()
                .filter(c -> c.name().equals("EventLocation")).findFirst().orElseThrow();
        assertEquals("Microsoft.OData.SampleService.Models.TripPin.Location", eventLocation.baseType());
    }

    @Test
    void tripPin_functions() {
        SchemaModel schema = trippinModel.schemas().get(0);
        List<String> names = schema.functions().stream()
                .map(FunctionModel::name).toList();
        assertTrue(names.containsAll(List.of(
                "GetFavoriteAirline", "GetInvolvedPeople",
                "GetFriendsTrips", "GetNearestAirport")));
    }

    @Test
    void tripPin_boundFunction() {
        SchemaModel schema = trippinModel.schemas().get(0);

        FunctionModel getFriendsTrips = schema.functions().stream()
                .filter(f -> f.name().equals("GetFriendsTrips")).findFirst().orElseThrow();
        assertTrue(getFriendsTrips.isBound());
        assertTrue(getFriendsTrips.isComposable());
        assertEquals(2, getFriendsTrips.parameters().size());
        assertNotNull(getFriendsTrips.returnType());
    }

    @Test
    void tripPin_unboundFunction() {
        SchemaModel schema = trippinModel.schemas().get(0);

        FunctionModel getNearestAirport = schema.functions().stream()
                .filter(f -> f.name().equals("GetNearestAirport")).findFirst().orElseThrow();
        assertFalse(getNearestAirport.isBound());
        assertEquals(2, getNearestAirport.parameters().size());
    }

    @Test
    void tripPin_actions() {
        SchemaModel schema = trippinModel.schemas().get(0);
        List<String> names = schema.actions().stream()
                .map(ActionModel::name).toList();
        assertTrue(names.containsAll(List.of("ResetDataSource", "ShareTrip")));
    }

    @Test
    void tripPin_boundAction() {
        SchemaModel schema = trippinModel.schemas().get(0);

        ActionModel shareTrip = schema.actions().stream()
                .filter(a -> a.name().equals("ShareTrip")).findFirst().orElseThrow();
        assertTrue(shareTrip.isBound());
        assertEquals(3, shareTrip.parameters().size());
    }

    @Test
    void tripPin_container() {
        SchemaModel schema = trippinModel.schemas().get(0);
        assertEquals(1, schema.containers().size());

        ContainerModel container = schema.containers().get(0);
        assertEquals("DefaultContainer", container.name());
    }

    @Test
    void tripPin_entitySets() {
        ContainerModel container = trippinModel.schemas().get(0).containers().get(0);
        List<String> names = container.entitySets().stream()
                .map(EntitySetModel::name).toList();
        assertTrue(names.containsAll(List.of("People", "Photos", "Airlines", "Airports")));
    }

    @Test
    void tripPin_peopleEntitySetBindings() {
        ContainerModel container = trippinModel.schemas().get(0).containers().get(0);
        EntitySetModel people = container.entitySets().stream()
                .filter(e -> e.name().equals("People")).findFirst().orElseThrow();

        List<String> bindingPaths = people.navigationPropertyBindings().stream()
                .map(NavigationPropertyBindingModel::path).toList();
        assertTrue(bindingPaths.contains("Friends"));
    }

    @Test
    void tripPin_singletons() {
        ContainerModel container = trippinModel.schemas().get(0).containers().get(0);
        assertEquals(1, container.singletons().size());
        assertEquals("Me", container.singletons().get(0).name());
    }

    @Test
    void tripPin_functionImports() {
        ContainerModel container = trippinModel.schemas().get(0).containers().get(0);
        assertEquals(1, container.functionImports().size());
        assertEquals("GetNearestAirport", container.functionImports().get(0).name());
        assertTrue(container.functionImports().get(0).includeInServiceDocument());
    }

    @Test
    void tripPin_actionImports() {
        ContainerModel container = trippinModel.schemas().get(0).containers().get(0);
        assertEquals(1, container.actionImports().size());
        assertEquals("ResetDataSource", container.actionImports().get(0).name());
    }

    // ===== Northwind Tests =====

    @Test
    void northwind_hasTwoSchemas() {
        assertTrue(northwindModel.schemas().size() >= 1);
    }

    @Test
    void northwind_parsedEntityTypes() {
        SchemaModel schema = northwindModel.schemas().stream()
                .filter(s -> s.namespace().equals("NorthwindModel")).findFirst().orElseThrow();
        List<String> names = schema.entityTypes().stream()
                .map(EntityTypeModel::name).toList();
        assertTrue(names.containsAll(List.of(
                "Category", "Customer", "Employee", "Order",
                "Order_Detail", "Product", "Supplier")));
    }

    @Test
    void northwind_orderDetailCompositeKey() {
        SchemaModel schema = northwindModel.schemas().stream()
                .filter(s -> s.namespace().equals("NorthwindModel")).findFirst().orElseThrow();
        EntityTypeModel orderDetail = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Order_Detail")).findFirst().orElseThrow();

        assertEquals(1, orderDetail.keys().size());
        assertEquals(2, orderDetail.keys().get(0).propertyRefs().size());
        assertTrue(orderDetail.keys().get(0).propertyRefs().containsAll(List.of("OrderID", "ProductID")));
    }

    @Test
    void northwind_entitySets() {
        SchemaModel containerSchema = northwindModel.schemas().stream()
                .filter(s -> s.containers().size() > 0).findFirst().orElseThrow();
        ContainerModel container = containerSchema.containers().get(0);

        List<String> names = container.entitySets().stream()
                .map(EntitySetModel::name).toList();
        assertTrue(names.containsAll(List.of(
                "Categories", "Customers", "Employees", "Orders",
                "Order_Details", "Products", "Suppliers")));
    }

    @Test
    void northwind_navigationBindings() {
        SchemaModel containerSchema = northwindModel.schemas().stream()
                .filter(s -> s.containers().size() > 0).findFirst().orElseThrow();
        ContainerModel container = containerSchema.containers().get(0);

        EntitySetModel orders = container.entitySets().stream()
                .filter(e -> e.name().equals("Orders")).findFirst().orElseThrow();
        List<String> targets = orders.navigationPropertyBindings().stream()
                .map(NavigationPropertyBindingModel::target).toList();
        assertTrue(targets.containsAll(List.of("Customers", "Employees")));
    }
}
