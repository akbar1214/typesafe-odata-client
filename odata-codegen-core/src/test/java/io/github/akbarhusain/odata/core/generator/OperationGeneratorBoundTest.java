package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bound operations (decision 96): ops whose BINDING parameter (first Parameter) resolves
 * to the entity type or one of its ancestors surface on that entity's request ecosystem —
 * resolved classes in .operation, accessors on the entity request. Binding params are
 * excluded from invocation parameters; ancestor-bound ops carry a cast segment.
 */
class OperationGeneratorBoundTest {

    private final CsdlModel trippin;
    private final CsdlModel.SchemaModel schema;

    OperationGeneratorBoundTest() {
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            this.trippin = new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.schema = trippin.schemas().get(0);
    }

    private OperationGenerator generator() {
        return new OperationGenerator("com.example.trippin", java.util.Map.of(),
                "com.example.trippin", trippin.schemas());
    }

    private CsdlModel.EntityTypeModel type(String name) {
        return schema.entityTypes().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void personRequestDiscoversItsThreeBoundOperations() {
        List<OperationGenerator.BoundOp> ops = generator().boundOperationsFor(type("Person"), schema);
        assertEquals(3, ops.size(), () -> "ShareTrip (action) + GetFavoriteAirline + GetFriendsTrips: " + ops);
        assertTrue(ops.stream().allMatch(OperationGenerator.BoundOp::isFunction) == false);
        assertEquals(1, ops.stream().filter(o -> !o.isFunction()).count(), "one bound action");
    }

    @Test
    void bindingParameterExcludedFromInvocationParameters() {
        OperationGenerator.BoundOp shareTrip = generator().boundOperationsFor(type("Person"), schema)
                .stream().filter(o -> o.opName().equals("ShareTrip")).findFirst().orElseThrow();
        assertEquals(List.of("userName", "tripId"),
                shareTrip.parameters().stream().map(CsdlModel.ParameterModel::name).toList(),
                "the binding parameter 'person' is the URL context, not an invocation parameter");
        assertTrue(shareTrip.isFunction() == false);
        assertNull(shareTrip.castSegment(), "op bound to Person needs no cast on a Person request");
        assertEquals("PersonShareTripActionRequest", shareTrip.className());
        assertEquals("shareTrip", shareTrip.accessorName());
    }

    @Test
    void tripRequestOnlySeesTripBoundOps() {
        List<OperationGenerator.BoundOp> ops = generator().boundOperationsFor(type("Trip"), schema);
        assertEquals(1, ops.size());
        assertEquals("GetInvolvedPeople", ops.get(0).opName());
        assertTrue(ops.get(0).isFunction());
        assertEquals("TripGetInvolvedPeopleFunctionRequest", ops.get(0).className());
    }

    @Test
    void ancestorBoundOpsSurfaceOnSubtypeWithCastSegment() {
        // Synthetic: Op bound to Base; Derived extends Base → Derived's request sees it
        // with a cast segment so the URL reads .../Derived('x')/N.NS.Base/OpName
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Derived", "N.NS.Base", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(),
                List.of(new CsdlModel.FunctionModel("Rate", true, false, null,
                        List.of(new CsdlModel.ParameterModel("target", "N.NS.Base", false),
                                new CsdlModel.ParameterModel("score", "Edm.Int32", false)),
                        new CsdlModel.ReturnTypeModel("Edm.Double", false))),
                List.of(), List.of());

        List<OperationGenerator.BoundOp> ops = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                .boundOperationsFor(s.entityTypes().get(1), s);
        assertEquals(1, ops.size());
        assertEquals("N.NS.Base", ops.get(0).castSegment(),
                "ancestor-bound op carries the qualified cast segment");
        assertEquals("DerivedRateFunctionRequest", ops.get(0).className());
        // base request also sees it (no cast)
        List<OperationGenerator.BoundOp> onBase = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                .boundOperationsFor(s.entityTypes().get(0), s);
        assertEquals(1, onBase.size());
        assertNull(onBase.get(0).castSegment());
    }

    @Test
    void sameNameBoundFunctionsOverloadByParameterNames() {
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Doc", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(),
                List.of(
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "N.NS.Doc", false),
                                        new CsdlModel.ParameterModel("name", "Edm.String", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false)),
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "N.NS.Doc", false),
                                        new CsdlModel.ParameterModel("score", "Edm.Int32", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false))),
                List.of(), List.of());

        List<OperationGenerator.BoundOp> ops = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                .boundOperationsFor(s.entityTypes().get(0), s);
        assertEquals(2, ops.size(), "overloads enumerated, not ambiguous");
        assertTrue(ops.stream().anyMatch(o -> o.accessorName().equals("getByName")),
                "suffix derives from parameter names: " + ops.stream().map(OperationGenerator.BoundOp::accessorName).toList());
        assertTrue(ops.stream().anyMatch(o -> o.accessorName().equals("getByScore")));
    }

    @Test
    void identicalParameterNameOverloadsFailLoudly() {
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Doc", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(),
                List.of(
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "N.NS.Doc", false),
                                        new CsdlModel.ParameterModel("x", "Edm.String", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false)),
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "N.NS.Doc", false),
                                        new CsdlModel.ParameterModel("x", "Edm.Int32", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false))),
                List.of(), List.of());

        // Overloads are identified by PARAMETER NAMES — identical name lists are
        // indistinguishable in an invocation URL and fail loudly (imports parity)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                        .boundOperationsFor(s.entityTypes().get(0), s));
        assertTrue(ex.getMessage().contains("Get"), ex.getMessage());
    }

    @Test
    void sameNameBoundActionsFailLoudly() {
        CsdlModel.ParameterModel binding = new CsdlModel.ParameterModel("doc", "N.NS.Doc", false);
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Doc", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        new CsdlModel.ActionModel("Touch", true, null, List.of(binding), null),
                        new CsdlModel.ActionModel("Touch", true, null, List.of(binding),
                                new CsdlModel.ReturnTypeModel("Edm.Int32", false))),
                List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                        .boundOperationsFor(s.entityTypes().get(0), s));
        assertTrue(ex.getMessage().contains("Touch"), ex.getMessage());
    }

    @Test
    void boundFunctionRequestClassBuildsOnBasePathWithCastSegment() {
        CsdlModel.SchemaModel s = syntheticBaseDerived();
        OperationGenerator gen = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s));
        OperationGenerator.BoundOp op = gen.boundOperationsFor(s.entityTypes().get(1), s).get(0);
        String code = gen.generateBoundOperationRequest(op, s.entityTypes().get(1), s);

        assertTrue(code.contains("package app.operation;"), () -> code);
        assertTrue(code.contains("basePath.addSegment(\"N.NS.Base\")")
                && code.contains("OperationPath.segment(\"Rate\""),
                "cast segment precedes the operation segment: " + snippet(code, "this.contextPath"));
    }

    @Test
    void boundVoidActionRequestPostsBodyBuiltFromBasePath() {
        OperationGenerator gen = generator();
        OperationGenerator.BoundOp shareTrip = gen.boundOperationsFor(type("Person"), schema)
                .stream().filter(o -> o.opName().equals("ShareTrip")).findFirst().orElseThrow();
        String code = gen.generateBoundOperationRequest(shareTrip, type("Person"), schema);

        assertTrue(code.contains(
                "public PersonShareTripActionRequest(Context context, ContextPath basePath, String userName, int tripId)"),
                "non-nullable primitive binding params stay unboxed (import-parity contract)");
        assertTrue(code.contains("this.contextPath = basePath.addSegment(\"ShareTrip\");"),
                "no cast when the op is bound to the request's own type");
        assertTrue(code.contains("__params.put(\"userName\", userName);"));
        assertTrue(code.contains("EntityOperations.invokeVoidSync(context, contextPath, HttpMethod.POST, body)"),
                "parameterless-RETURN bound action is a void POST with body");
    }

    private static CsdlModel.SchemaModel syntheticBaseDerived() {
        return new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Derived", "N.NS.Base", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(),
                List.of(new CsdlModel.FunctionModel("Rate", true, false, null,
                        List.of(new CsdlModel.ParameterModel("target", "N.NS.Base", false),
                                new CsdlModel.ParameterModel("score", "Edm.Int32", false)),
                        new CsdlModel.ReturnTypeModel("Edm.Double", false))),
                List.of(), List.of());
    }

    private static String snippet(String code, String marker) {
        int i = code.indexOf(marker);
        return i < 0 ? "(missing " + marker + ")" : code.substring(i, Math.min(i + 160, code.length()));
    }

    @Test
    void nonEntityBindingParameterFailsLoudly() {
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Doc", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(),
                List.of(new CsdlModel.FunctionModel("Bad", true, false, null,
                        List.of(new CsdlModel.ParameterModel("who", "Edm.String", false)),
                        new CsdlModel.ReturnTypeModel("Edm.String", false))),
                List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                        .boundOperationsFor(s.entityTypes().get(0), s));
        assertTrue(ex.getMessage().contains("Bad"), ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("binding"), ex.getMessage());
    }
}
