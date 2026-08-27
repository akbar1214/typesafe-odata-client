package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-conformant operation response envelopes (review round 6, H2/H3/M4): real services
 * include {@code @odata.context} control annotations alongside the {@code "value"}
 * property, and complex/enum single results are value-wrapped —
 * {@code {"@odata.context":..., "value": {...}}} — unlike entity results which arrive
 * at the JSON root.
 */
class EntityOperationsInvokeEnvelopeTest {

    public static class Address {
        @JsonProperty("Street") protected String street;
        @JsonProperty("City") protected String city;
        public String getStreet() { return street; }
        public String getCity() { return city; }
    }

    public static class Location extends Address {
        @JsonProperty("Building") protected String building;
        public String getBuilding() { return building; }
    }

    static class TestSchemaInfo implements SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "NS.Location".equals(name) ? Location.class : null;
        }
    }

    private Context context(String json) {
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("application/json")),
                        json.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return Context.builder().baseUrl("https://example.com").transport(transport).build();
    }

    private ContextPath path(Context ctx, String segment) {
        return ctx.basePath().addSegment(segment);
    }

    // ---- H2: @odata.context must not defeat the primitive unwrap ----

    @Test
    void primitiveResultWithODataContextAnnotationIsUnwrapped() {
        Context ctx = context(
                "{\"@odata.context\":\"https://example.com/$metadata#Edm.Int32\",\"value\":42}");

        Integer result = EntityOperations.invokePrimitiveSync(ctx, path(ctx, "GetCount"),
                HttpMethod.GET, null, Integer.class);

        assertEquals(42, result);
    }

    @Test
    void primitiveResultWithOnlyControlAnnotationAndValueUnwraps() {
        // @odata.metadataEtag is another legal control annotation services emit
        Context ctx = context("{\"@odata.metadataEtag\":\"W/\\\"x\\\"\",\"value\":\"hi\"}");

        String result = EntityOperations.invokePrimitiveSync(ctx, path(ctx, "Say"),
                HttpMethod.GET, null, String.class);

        assertEquals("hi", result);
    }

    @Test
    void primitiveWrapperObjectStillUnwrappedWithoutAnnotations() {
        Context ctx = context("{\"value\":7}");

        Integer result = EntityOperations.invokePrimitiveSync(ctx, path(ctx, "Lucky"),
                HttpMethod.GET, null, Integer.class);

        assertEquals(7, result);
    }

    @Test
    void bareLiteralPrimitiveStillPassesThrough() {
        Context ctx = context("42");

        Integer result = EntityOperations.invokePrimitiveSync(ctx, path(ctx, "Bare"),
                HttpMethod.GET, null, Integer.class);

        assertEquals(42, result);
    }

    @Test
    void nullValuedPrimitiveEnvelopeDeserializesToNull() {
        Context ctx = context(
                "{\"@odata.context\":\"https://example.com/$metadata#Edm.String\",\"value\":null}");

        String result = EntityOperations.invokePrimitiveSync(ctx, path(ctx, "Maybe"),
                HttpMethod.GET, null, String.class);

        assertNull(result);
    }

    // ---- H3: complex/enum single results unwrap the value envelope ----

    @Test
    void complexResultUnwrapsValueEnvelope() {
        Context ctx = context("{\"@odata.context\":\"https://example.com/$metadata#NS.Address\","
                + "\"value\":{\"Street\":\"Main St\",\"City\":\"Seattle\"}}");

        Address result = EntityOperations.invokeComplexSync(ctx, path(ctx, "HomeAddress"),
                HttpMethod.GET, null, Address.class);

        assertNotNull(result);
        assertEquals("Main St", result.getStreet());
        assertEquals("Seattle", result.getCity());
    }

    @Test
    void complexResultWithoutEnvelopePassesThrough() {
        // tolerance for services that inline the complex at the root
        Context ctx = context("{\"Street\":\"Pine\",\"City\":\"Denver\"}");

        Address result = EntityOperations.invokeComplexSync(ctx, path(ctx, "WorkAddress"),
                HttpMethod.GET, null, Address.class);

        assertNotNull(result);
        assertEquals("Pine", result.getStreet());
    }

    @Test
    void complexResultEmptyBodyReturnsNull() {
        Context ctx = context("");

        Address result = EntityOperations.invokeComplexSync(ctx, path(ctx, "Nothing"),
                HttpMethod.GET, null, Address.class);

        assertNull(result);
    }

    @Test
    void complexResultAsyncParityUnwrapsEnvelope() throws Exception {
        Context ctx = context("{\"value\":{\"Street\":\"Oak\",\"City\":\"Portland\"}}");

        Address result = EntityOperations.invokeComplexAsync(ctx, path(ctx, "HomeAddress"),
                HttpMethod.GET, null, Address.class).join();

        assertNotNull(result);
        assertEquals("Oak", result.getStreet());
    }

    @Test
    void complexResultWithRealValuePropertyIsNotDoubleUnwrapped() {
        // a complex that genuinely HAS a "value" property plus other properties must not
        // be mistaken for an envelope: unwrap only fires when "value" is the sole
        // non-control property
        Context ctx = context(
                "{\"@odata.context\":\"https://example.com/$metadata#NS.Address\",\"value\":{\"Street\":\"Elm\"}}");

        Address result = EntityOperations.invokeComplexSync(ctx, path(ctx, "HomeAddress"),
                HttpMethod.GET, null, Address.class);

        assertEquals("Elm", result.getStreet());
    }

    // ---- M4: polymorphic operation results via SchemaInfo ----

    @Test
    void complexPolymorphicResultResolvesSubtype() {
        Context ctx = context("{\"value\":{\"@odata.type\":\"#NS.Location\","
                + "\"Street\":\"Fir\",\"Building\":\"Annex\"}}");

        Address result = EntityOperations.invokeComplexSync(ctx, path(ctx, "AnyAddress"),
                HttpMethod.GET, null, Address.class, new TestSchemaInfo());

        assertInstanceOf(Location.class, result);
        assertEquals("Fir", result.getStreet());
        assertEquals("Annex", ((Location) result).getBuilding());
    }

    @Test
    void entityPolymorphicResultResolvesSubtype() {
        Context ctx = context("{\"@odata.type\":\"#NS.Location\",\"Street\":\"Alder\",\"Building\":\"Tower\"}");

        Address result = EntityOperations.invokeSync(ctx, path(ctx, "GetAddress"),
                HttpMethod.GET, null, Address.class, new TestSchemaInfo());

        assertInstanceOf(Location.class, result);
        assertEquals("Tower", ((Location) result).getBuilding());
    }

    @Test
    void entityPolymorphicResultWithoutMatchingSubtypeFallsBackToDeclaredType() {
        Context ctx = context("{\"@odata.type\":\"#NS.Unknown\",\"Street\":\"Birch\"}");

        Address result = EntityOperations.invokeSync(ctx, path(ctx, "GetAddress"),
                HttpMethod.GET, null, Address.class, new TestSchemaInfo());

        assertEquals(Address.class, result.getClass());
        assertEquals("Birch", result.getStreet());
    }
}
