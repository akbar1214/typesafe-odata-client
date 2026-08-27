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
    void sameNameDifferentTypeOverloadsGenerate() {
        // ODATA-500: overloads are identified by the ORDERED SET of parameter types —
        // same names with different types are legal CSDL, distinguishable by literal form
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

        List<OperationGenerator.BoundOp> ops = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                .boundOperationsFor(s.entityTypes().get(0), s);
        assertEquals(2, ops.size(), "same names with different types are legal overloads");
        assertTrue(ops.stream().anyMatch(o -> o.accessorName().equals("getByX")));
        assertTrue(ops.stream().anyMatch(o -> o.accessorName().equals("getByX_2")),
                "type-only overloads dedupe deterministically: "
                        + ops.stream().map(OperationGenerator.BoundOp::accessorName).toList());
    }

    @Test
    void sameBindingDifferentTypesInheritedOverloadsGenerate() {
        // The BINDING ENTITY TYPE participates in overload identity (ODATA-425): Get
        // bound to Base and Get bound to Derived are distinct overloads — a derived-type
        // request sees both, with a cast segment for the ancestor-bound one
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Derived", "N.NS.Base", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(),
                List.of(
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("target", "N.NS.Base", false),
                                        new CsdlModel.ParameterModel("x", "Edm.String", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false)),
                        new CsdlModel.FunctionModel("Get", true, false, null,
                                List.of(new CsdlModel.ParameterModel("target", "N.NS.Derived", false),
                                        new CsdlModel.ParameterModel("x", "Edm.String", false)),
                                new CsdlModel.ReturnTypeModel("Edm.String", false))),
                List.of(), List.of());

        OperationGenerator gen = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s));
        List<OperationGenerator.BoundOp> onDerived = gen.boundOperationsFor(s.entityTypes().get(1), s);
        assertEquals(2, onDerived.size(), "ancestor-bound + own-bound both surface on Derived");
        assertTrue(onDerived.stream().anyMatch(o -> o.accessorName().equals("getByX")));
        assertTrue(onDerived.stream().anyMatch(o -> o.accessorName().equals("getByX_2")),
                "identical invocation params dedupe deterministically: "
                        + onDerived.stream().map(OperationGenerator.BoundOp::accessorName).toList());
        assertTrue(onDerived.stream().anyMatch(o -> o.castSegment() == null),
                "the Derived-bound overload needs no cast");
        assertTrue(onDerived.stream().anyMatch(o -> "N.NS.Base".equals(o.castSegment())),
                "the Base-bound overload carries the cast segment");
        List<OperationGenerator.BoundOp> onBase = gen.boundOperationsFor(s.entityTypes().get(0), s);
        assertEquals(1, onBase.size(), "the subtype-bound overload is invisible on Base");
        assertEquals("get", onBase.get(0).accessorName(), "lone overload keeps the bare op name");
    }

    @Test
    void trulyIdenticalOverloadsStillFailLoudly() {
        // Same binding type + same parameter names AND types — the overloads are
        // indistinguishable in an invocation URL (invalid CSDL: differs only by return type)
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
                                        new CsdlModel.ParameterModel("x", "Edm.String", false)),
                                new CsdlModel.ReturnTypeModel("Edm.Int32", false))),
                List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", java.util.Map.of(), "app", List.of(s))
                        .boundOperationsFor(s.entityTypes().get(0), s));
        assertTrue(ex.getMessage().contains("Get"), ex.getMessage());
    }

    @Test
    void sameNameBoundActionsOnDifferentBindingTypesGenerate() {
        // ODATA-425: bound actions overload by BINDING PARAMETER — one per binding type;
        // a derived-type request sees both, distinguished by the cast segment
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Derived", "N.NS.Base", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        new CsdlModel.ActionModel("Touch", true, null,
                                List.of(new CsdlModel.ParameterModel("target", "N.NS.Base", false)), null),
                        new CsdlModel.ActionModel("Touch", true, null,
                                List.of(new CsdlModel.ParameterModel("target", "N.NS.Derived", false)), null)),
                List.of());

        OperationGenerator gen = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s));
        List<OperationGenerator.BoundOp> onDerived = gen.boundOperationsFor(s.entityTypes().get(1), s);
        assertEquals(2, onDerived.size(), "bound actions on different binding types are legal overloads");
        assertTrue(onDerived.stream().anyMatch(o -> o.castSegment() == null));
        assertTrue(onDerived.stream().anyMatch(o -> "N.NS.Base".equals(o.castSegment())));
        assertTrue(onDerived.stream().anyMatch(o -> o.accessorName().equals("touch")));
        assertTrue(onDerived.stream().anyMatch(o -> o.accessorName().equals("touch_2")),
                "accessors dedupe deterministically: "
                        + onDerived.stream().map(OperationGenerator.BoundOp::accessorName).toList());
        List<OperationGenerator.BoundOp> onBase = gen.boundOperationsFor(s.entityTypes().get(0), s);
        assertEquals(1, onBase.size());
        assertEquals("touch", onBase.get(0).accessorName());
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
    void boundFunctionWithStructuredParameterRidesJsonAlias() {
        CsdlModel.SchemaModel s = syntheticStructuredParam();
        OperationGenerator gen = new OperationGenerator("app", java.util.Map.of(), "app", List.of(s));
        OperationGenerator.BoundOp op = gen.boundOperationsFor(s.entityTypes().get(0), s).get(0);
        String code = gen.generateBoundOperationRequest(op, s.entityTypes().get(0), s);

        assertTrue(code.contains("public PersonRateFunctionRequest(Context context, ContextPath basePath, Address address)"),
                "structured invocation parameter rides the ctor after basePath: " + snippet(code, "public PersonRateFunctionRequest"));
        assertTrue(code.contains("import app.complex.Address;"));
        assertTrue(code.contains("__pairs.add(\"Address=@p0\");"),
                "bound functions use the same alias mechanism as imports");
        assertTrue(code.contains("addQuery(\"@p0\", EntityOperations.jsonParameter(address))"));
    }

    private static CsdlModel.SchemaModel syntheticStructuredParam() {
        return new CsdlModel.SchemaModel("N.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Person", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(new CsdlModel.ComplexTypeModel("Address", null, false, false,
                        List.of(new CsdlModel.PropertyModel("Street", "Edm.String", false, null, List.of())),
                        List.of())),
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionModel("Rate", true, false, null,
                        List.of(new CsdlModel.ParameterModel("target", "N.NS.Person", false),
                                new CsdlModel.ParameterModel("Address", "N.NS.Address", false)),
                        new CsdlModel.ReturnTypeModel("Edm.Double", false))),
                List.of(), List.of());
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
