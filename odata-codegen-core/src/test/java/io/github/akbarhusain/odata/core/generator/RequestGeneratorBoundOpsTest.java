package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bound-operation accessors embed on the entity request (keyed contextPath flows into
 * the op request as basePath), and Generator writes one .operation file per bound op.
 */
class RequestGeneratorBoundOpsTest {

    private String generatePersonEntityRequest() throws Exception {
        var model = load("/trippin-metadata.xml");
        var schema = model.schemas().get(0);
        var person = schema.entityTypes().stream().filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
        return new RequestGenerator("com.example.trippin", Map.of(), "com.example.trippin", model.schemas())
                .generateEntityRequest(person, schema);
    }

    @Test
    void entityRequestEmbedsBoundOperationAccessors() throws Exception {
        String code = generatePersonEntityRequest();
        assertTrue(code.contains(
                "public PersonShareTripActionRequest shareTrip(String userName, int tripId)"),
                "accessor passes its keyed contextPath as the op request's basePath: " + code);
        assertTrue(code.contains("new PersonShareTripActionRequest(context, contextPath, userName, tripId)"));
        assertTrue(code.contains(
                "import com.example.trippin.operation.PersonShareTripActionRequest;"));
        // bound functions too
        assertTrue(code.contains("public PersonGetFriendsTripsFunctionRequest getFriendsTrips(String userName)"));
    }

    @Test
    void boundAccessorImportsParameterTypes() {
        // The accessor signature uses parameterJavaType — structured/enum/collection
        // parameter types need their own imports on the entity request, which only
        // imported the op-request class itself
        CsdlModel.SchemaModel s = new CsdlModel.SchemaModel("NS", null,
                List.of(new CsdlModel.EntityTypeModel("Doc", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(new CsdlModel.ComplexTypeModel("Address", null, false, false,
                        List.of(new CsdlModel.PropertyModel("Street", "Edm.String", false, null, List.of())),
                        List.of())),
                List.of(new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                        List.of(new CsdlModel.EnumMemberModel("Red", 0L)))),
                List.of(),
                List.of(
                        new CsdlModel.FunctionModel("Rate", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "NS.Doc", false),
                                        new CsdlModel.ParameterModel("color", "NS.Color", false)),
                                new CsdlModel.ReturnTypeModel("Edm.Double", false)),
                        new CsdlModel.FunctionModel("RateAll", true, false, null,
                                List.of(new CsdlModel.ParameterModel("doc", "NS.Doc", false),
                                        new CsdlModel.ParameterModel("addrs", "Collection(NS.Address)", false)),
                                new CsdlModel.ReturnTypeModel("Edm.Int32", false))),
                List.of(), List.of());

        String code = new RequestGenerator("app", Map.of(), "app", List.of(s))
                .generateEntityRequest(s.entityTypes().get(0), s);

        assertTrue(code.contains("import app.enums.Color;"),
                "enum parameter type needs its import on the entity request: " + code);
        assertTrue(code.contains("import app.complex.Address;"),
                "structured parameter type needs its import on the entity request");
        assertTrue(code.contains("import java.util.List;"),
                "collection parameter needs List for the accessor signature");
        assertTrue(code.contains("public DocRateFunctionRequest rate(Color color)"));
        assertTrue(code.contains("public DocRateAllFunctionRequest rateAll(List<Address> addrs)"));
    }

    @Test
    void generatorWritesBoundOperationFiles() throws Exception {
        CsdlModel model = load("/trippin-metadata.xml");
        Path out = Files.createTempDirectory("boundops");
        try {
            new Generator(out, Map.of("Microsoft.OData.SampleService.Models.TripPin", "com.example.trippin"),
                    "com.example.trippin").generate(model);
            assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin", "operation",
                    "PersonShareTripActionRequest.java"))));
            assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin", "operation",
                    "PersonGetFriendsTripsFunctionRequest.java"))));
            assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin", "operation",
                    "PersonGetFavoriteAirlineFunctionRequest.java"))));
            assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin", "operation",
                    "TripGetInvolvedPeopleFunctionRequest.java"))));
        } finally {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static CsdlModel load(String path) {
        try (InputStream is = RequestGeneratorBoundOpsTest.class.getResourceAsStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
