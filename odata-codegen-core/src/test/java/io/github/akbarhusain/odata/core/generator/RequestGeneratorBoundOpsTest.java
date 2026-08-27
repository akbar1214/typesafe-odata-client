package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
