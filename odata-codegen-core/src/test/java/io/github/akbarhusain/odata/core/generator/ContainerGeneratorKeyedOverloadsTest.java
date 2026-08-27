package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyed container overloads (decision 95): every entity set with a key gains a
 * {@code <set>(keyParams...)} overload next to the zero-arg collection accessor,
 * returning the entity request directly — {@code client.people("russellwhyte")}
 * instead of {@code client.people().personByUserName("russellwhyte")}.
 */
class ContainerGeneratorKeyedOverloadsTest {

    private String generateTripPinContainer() throws Exception {
        var model = load("/trippin-metadata.xml");
        var schema = model.schemas().get(0);
        return new ContainerGenerator("com.example.trippin", java.util.Map.of(),
                "com.example.trippin", model.schemas())
                .generate(schema.containers().get(0), schema);
    }

    @Test
    void entitySetWithSingleKeyGetsKeyedOverloadReturningEntityRequest() throws Exception {
        String code = generateTripPinContainer();
        assertTrue(code.contains("public PersonEntityRequest people(String userName)"),
                "keyed overload mirrors the set accessor name with typed key params");
        assertTrue(code.contains(
                "new PersonEntityRequest(context, context.basePath().addSegment(\"People\")"
                        + ".addKey(\"UserName\", userName, \"Edm.String\"))"),
                "key renders via segment-local addKey with the resolved Edm type (decision 52)");
    }

    @Test
    void zeroArgCollectionAccessorStillEmitted() throws Exception {
        String code = generateTripPinContainer();
        assertTrue(code.contains("public PersonCollectionRequest people()"),
                "unkeyed collection queries remain on the zero-arg accessor");
    }

    @Test
    void nonStringKeyRendersTypedParameter() throws Exception {
        String code = generateTripPinContainer();
        // Photos.Id is Edm.Int64 — keyJavaType yields the boxed form (same contract as tripByID(Integer))
        assertTrue(code.contains("public PhotoEntityRequest photos(Long id)"),
                "key Java type comes from resolveKeyType (Edm.Int64 → Long)");
        assertTrue(code.contains(".addKey(\"Id\", id, \"Edm.Int64\")"));
    }

    @Test
    void keylessEntitySetGetsNoOverload() {
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("K.NS", null,
                List.of(new CsdlModel.EntityTypeModel("LogEntry", null, false, false, false,
                        List.of(), List.of(
                                new CsdlModel.PropertyModel("Message", "Edm.String", true, null, List.of()),
                                new CsdlModel.PropertyModel("Stamp", "Edm.DateTimeOffset", true, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CsdlModel.ContainerModel("C",
                        List.of(new CsdlModel.EntitySetModel("Logs", "K.NS.LogEntry",
                                List.of(), List.of())),
                        List.of(), List.of(), List.of())));
        String code = new ContainerGenerator("app", java.util.Map.of(), "app",
                List.of(schema)).generate(schema.containers().get(0), schema);
        assertFalse(code.contains("public LogEntryEntityRequest logs("),
                "keyless entity types cannot be keyed — no overload emitted");
        assertTrue(code.contains("public LogEntryCollectionRequest logs()"),
                "collection accessor still present");
    }

    private static CsdlModel load(String path) {
        try (InputStream is = ContainerGeneratorKeyedOverloadsTest.class.getResourceAsStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
