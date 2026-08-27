package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M21: entity request classes don't extend each other, so inherited navigation
 * properties, named streams, and HasStream must be re-emitted on the subtype's request.
 * M22: countValue() must not send $select/$expand/$orderby to /$count. M23: generated
 * requests pass SchemaInfo.INSTANCE for polymorphic @odata.type deserialization.
 */
class RequestGeneratorInheritedMembersTest {

    static CsdlModel.SchemaModel schema;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorInheritedMembersTest.class
                .getResourceAsStream("/inherited-members-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            schema = model.schemas().get(0);
        }
    }

    private String generateVideoEntityRequest() {
        var video = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Video")).findFirst().orElseThrow();
        return new RequestGenerator("com.example.test").generateEntityRequest(video, schema);
    }

    @Test
    void m21InheritedNavMethodsEmittedOnSubtypeRequest() {
        String code = generateVideoEntityRequest();

        assertTrue(code.contains("public PersonEntityRequest owner()"),
                "inherited nav 'Owner' must be navigable from the subtype request. Got:\n" + code);
        assertTrue(code.contains("public TagCollectionRequest tags()"),
                "inherited collection nav 'Tags' must be navigable from the subtype request");
        assertTrue(code.contains("addTagsRef"),
                "inherited $ref methods must be emitted for collection navs");
    }

    @Test
    void m21InheritedHasStreamAndNamedStreamEmittedOnSubtypeRequest() {
        String code = generateVideoEntityRequest();

        assertTrue(code.contains("streamMedia()"),
                "HasStream on the BASE type applies to the subtype request");
        assertTrue(code.contains("streamThumb()"),
                "Edm.Stream property declared on the BASE must get stream methods");
    }

    @Test
    void m22CountValueClearsInapplicableOptions() {
        var tripPin = loadTripPin();
        var trip = tripPin.entityTypes().stream()
                .filter(e -> e.name().equals("Trip")).findFirst().orElseThrow();
        String code = new RequestGenerator("com.example.trippin")
                .generateCollectionRequest(trip, tripPin);

        assertTrue(code.contains("tmp.selects.clear()"),
                "/$count forbids $select — countValue must clear it. Got:\n" + code);
        assertTrue(code.contains("tmp.expands.clear()"),
                "/$count forbids $expand — countValue must clear it");
        assertTrue(code.contains("tmp.orderings.clear()"),
                "/$count forbids $orderby — countValue must clear it");
    }

    @Test
    void m23GeneratedRequestsPassSchemaInfoForPolymorphicReads() {
        String entityRequest = generateVideoEntityRequest();
        assertTrue(entityRequest.contains("SchemaInfo.INSTANCE"),
                "entity get() must pass the schema registry for @odata.type deserialization");

        var tripPin = loadTripPin();
        var trip = tripPin.entityTypes().stream()
                .filter(e -> e.name().equals("Trip")).findFirst().orElseThrow();
        String collectionRequest = new RequestGenerator("com.example.trippin")
                .generateCollectionRequest(trip, tripPin);
        assertTrue(collectionRequest.contains("executeAndGetCollection(context, buildContext(), Trip.class, SchemaInfo.INSTANCE)"),
                "collection get() must pass the schema registry");
    }

    private CsdlModel.SchemaModel loadTripPin() {
        try (InputStream is = RequestGeneratorInheritedMembersTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            return new StaxCsdlParser().parse(is).schemas().get(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void l12GeneratedSelectRejectsFunctionTransformations() {
        var tripPin = loadTripPin();
        var trip = tripPin.entityTypes().stream()
                .filter(e -> e.name().equals("Trip")).findFirst().orElseThrow();
        String code = new RequestGenerator("com.example.trippin")
                .generateCollectionRequest(trip, tripPin);

        assertTrue(code.contains("not a selectable property"),
                "generated select() must reject function transformations like tolower(Name). Got:\n" + code);
    }
}
