package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyed nav overloads (decision 95): collection navs to keyed entity types gain a
 * keyed overload on the entity request — {@code person.trips(2)} renders
 * {@code People('x')/Trips(2)} without a tripByID() detour. Single navs already
 * return the entity request unkeyed; primitive/complex targets get no overload.
 */
class RequestGeneratorKeyedNavTest {

    private String generatePersonRequest() throws Exception {
        var model = load("/trippin-metadata.xml");
        var schema = model.schemas().get(0);
        var person = schema.entityTypes().stream().filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
        return new RequestGenerator("com.example.trippin", java.util.Map.of(),
                "com.example.trippin", model.schemas()).generateEntityRequest(person, schema);
    }

    @Test
    void collectionEntityNavGetsKeyedOverload() throws Exception {
        String code = generatePersonRequest();
        assertTrue(code.contains("public TripEntityRequest trips(Integer tripId)"),
                "keyed overload mirrors the nav accessor name with the target's typed key");
        assertTrue(code.contains(
                "new TripEntityRequest(context, contextPath.addSegment(\"Trips\")"
                        + ".addKey(\"TripId\", tripId, \"Edm.Int32\"))"));
    }

    @Test
    void recursiveCollectionNavKeysWithTargetKey() throws Exception {
        // Person.Friends is Collection(Person) — keyed with Person's own key
        String code = generatePersonRequest();
        assertTrue(code.contains("public PersonEntityRequest friends(String userName)"));
        assertTrue(code.contains(".addKey(\"UserName\", userName, \"Edm.String\")"));
    }

    @Test
    void singleNavGetsNoKeyedOverload() throws Exception {
        // Person.Photo is a single entity nav — it already returns the entity request
        String code = generatePersonRequest();
        assertEquals(1, countOccurrences(code, "public PhotoEntityRequest photo("),
                "exactly the unkeyed single-nav accessor, no keyed duplicate");
    }

    @Test
    void inheritedCollectionNavGetsKeyedOverloadOnSubtypeRequest() {
        // Derived inherits Base.Items — the subtype's entity request re-emits navs
        // (decision 106) and must re-emit the keyed overload too
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Item", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("ItemId"))),
                                List.of(new CsdlModel.PropertyModel("ItemId", "Edm.Int32", false, null, List.of())),
                                List.of()),
                        new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                                List.of(),
                                List.of(new CsdlModel.PropertyModel("Name", "Edm.String", true, null, List.of())),
                                List.of(new CsdlModel.NavigationPropertyModel("Items",
                                        "Collection(N.NS.Item)", null, false, false, List.of(), List.of()))),
                        new CsdlModel.EntityTypeModel("Derived", "N.NS.Base", false, false, false,
                                List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String code = new RequestGenerator("app", java.util.Map.of(), "app", List.of(schema))
                .generateEntityRequest(schema.entityTypes().get(2), schema);

        assertTrue(code.contains("public ItemEntityRequest items(Integer itemId)"),
                "inherited collection nav keyed overload: " + code);
        assertTrue(code.contains(".addKey(\"ItemId\", itemId, \"Edm.Int32\")"));
    }

    @Test
    void collectionNavToKeylessEntityGetsNoOverload() {
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("K.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Holder", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                                List.of(new CsdlModel.NavigationPropertyModel("Entries",
                                        "Collection(K.NS.LogEntry)", null, false, false, List.of(), List.of()))),
                        new CsdlModel.EntityTypeModel("LogEntry", null, false, false, false,
                                List.of(),
                                List.of(new CsdlModel.PropertyModel("Message", "Edm.String", true, null, List.of())),
                                List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String code = new RequestGenerator("app", java.util.Map.of(), "app", List.of(schema))
                .generateEntityRequest(schema.entityTypes().get(0), schema);

        assertFalse(code.contains("public LogEntryEntityRequest entries("),
                "keyless nav target cannot be keyed");
        assertTrue(code.contains("public LogEntryCollectionRequest entries()"));
    }

    @Test
    void keylessCollectionNavEmitsNoStrayBrace() {
        // A collection nav whose target entity has NO key takes no keyed overload — the
        // keyed path returns early, but the keyless path fell through to the shared
        // method-close epilogue, emitting a stray '}' after the already-closed method
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("N.NS", null,
                List.of(
                        new CsdlModel.EntityTypeModel("Item", null, false, false, false,
                                List.of(new CsdlModel.KeyModel(List.of("ItemId"))),
                                List.of(new CsdlModel.PropertyModel("ItemId", "Edm.Int32", false, null, List.of())),
                                List.of(new CsdlModel.NavigationPropertyModel("Tags",
                                        "Collection(N.NS.Tag)", null, false, false, List.of(), List.of()))),
                        new CsdlModel.EntityTypeModel("Tag", null, false, false, false,
                                List.of(),
                                List.of(new CsdlModel.PropertyModel("Value", "Edm.String", true, null, List.of())),
                                List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String code = new RequestGenerator("app", java.util.Map.of(), "app", List.of(schema))
                .generateEntityRequest(schema.entityTypes().get(0), schema);

        assertFalse(code.contains("    }\n\n    }\n\n"),
                "no stray closing brace after the collection nav method: " + code);
        assertEquals(0, countOccurrences(code, "public TagEntityRequest tags("),
                "keyless targets get no keyed overload");
        assertEquals(1, countOccurrences(code, "public TagCollectionRequest tags()"),
                "the plain collection accessor is still emitted exactly once");
    }

    @Test
    void crossSchemaKeyedNavImportsTargetEntityRequest() {
        // The keyed overload constructs the target's ENTITY request; for cross-schema
        // targets (distinct output packages) that class lives in another package and
        // needs its own import — the nav import loop previously added only the
        // CollectionRequest class, so the keyed overload referenced TagEntityRequest
        // unimported (same-package targets masked this)
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("A.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Item", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("ItemId"))),
                        List.of(new CsdlModel.PropertyModel("ItemId", "Edm.Int32", false, null, List.of())),
                        List.of(new CsdlModel.NavigationPropertyModel("Tags",
                                "Collection(B.NS.Tag)", null, false, false, List.of(), List.of())))),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("B.NS", null,
                List.of(new CsdlModel.EntityTypeModel("Tag", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("TagId"))),
                        List.of(new CsdlModel.PropertyModel("TagId", "Edm.Int32", false, null, List.of())),
                        List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel m = new CsdlModel(List.of(a, b), List.of());

        String code = new RequestGenerator("app",
                java.util.Map.of("A.NS", "app", "B.NS", "other"), "app", m.schemas())
                .generateEntityRequest(a.entityTypes().get(0), a);

        assertTrue(code.contains("import other.entity.request.TagEntityRequest;"),
                "keyed overload references TagEntityRequest cross-schema: " + code);
        assertTrue(code.contains("import other.collection.request.TagCollectionRequest;"));
        assertTrue(code.contains("public TagEntityRequest tags(Integer tagId)"),
                "the keyed overload is emitted for the cross-schema keyed target");
    }

    private static CsdlModel load(String path) {
        try (InputStream is = RequestGeneratorKeyedNavTest.class.getResourceAsStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
