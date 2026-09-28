package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsClientContractTest {

    private static Context context(String baseUrl, HttpTransport transport) {
        return context(baseUrl, transport, AuthProvider.none());
    }

    private static Context context(String baseUrl, HttpTransport transport, AuthProvider auth) {
        return Context.builder().baseUrl(baseUrl).transport(transport).authProvider(auth).build();
    }

    private static HttpResponse response(String body) {
        return response(body, Map.of());
    }

    private static HttpResponse response(String body, Map<String, List<String>> headers) {
        return new HttpResponse(200, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingTransport implements HttpTransport {
        private final Deque<HttpResponse> responses = new ArrayDeque<>();
        private HttpRequest lastRequest;
        private int submitCount;
        private boolean throwOnSubmit;
        private boolean throwOnStream;

        RecordingTransport(HttpResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            lastRequest = request;
            submitCount++;
            if (throwOnSubmit) {
                throw new IllegalStateException("submit exploded");
            }
            HttpResponse result = responses.isEmpty()
                    ? new HttpResponse(204, Map.of(), null) : responses.removeFirst();
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            lastRequest = request;
            if (throwOnStream) {
                throw new IllegalStateException("stream exploded");
            }
            return CompletableFuture.completedFuture(new ByteArrayInputStream(new byte[0]));
        }
    }

    @Test
    void sameOriginNextLinkKeepsAuthentication() {
        RecordingTransport transport = new RecordingTransport(
                response("{\"value\":[]}"), response("{\"value\":[]}"));
        Context context = context("https://example.com/service", transport,
                () -> Map.of("Authorization", "Bearer same-origin"));

        EntityOperations.executeAndGetCollection(context,
                context.basePath().fromNextLink("https://example.com/service/People?$skip=1"),
                Object.class);

        assertEquals(List.of("Bearer same-origin"),
                transport.lastRequest.headers().get("authorization"));
    }

    @Test
    void crossOriginNextLinkIsRejectedBeforeAuthenticationIsForwarded() {
        RecordingTransport transport = new RecordingTransport();
        Context context = context("https://example.com/service", transport,
                () -> Map.of("Authorization", "Bearer secret"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.executeAndGetCollection(context,
                        context.basePath().fromNextLink("https://other.example/People?$skip=1"),
                        Object.class));

        assertTrue(error.getMessage().toLowerCase().contains("origin")
                || error.getMessage().toLowerCase().contains("authentication"));
        assertEquals(0, transport.submitCount);
    }

    @Test
    void httpsDowngradeNextLinkIsRejectedBeforeAuthenticationIsForwarded() {
        RecordingTransport transport = new RecordingTransport();
        Context context = context("https://example.com/service", transport,
                () -> Map.of("Authorization", "Bearer secret"));

        assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.executeAndGetCollection(context,
                        context.basePath().fromNextLink("http://example.com/service/People?$skip=1"),
                        Object.class));

        assertEquals(0, transport.submitCount);
    }

    @Test
    void httpBaseToHttpsUpgradeNextLinkKeepsAuthentication() {
        RecordingTransport transport = new RecordingTransport(response("{\"value\":[]}"));
        Context context = context("http://example.com/service", transport,
                () -> Map.of("Authorization", "Bearer secret"));

        EntityOperations.executeAndGetCollection(context,
                context.basePath().fromNextLink("https://example.com/service/People?$skip=1"),
                Object.class);

        assertEquals(List.of("Bearer secret"),
                transport.lastRequest.headers().get("authorization"));
        assertTrue(transport.lastRequest.url().startsWith("https://example.com/service/"));
    }

    @Test
    void customTransportReceivesProtocolDefaultsAndProtocolHeadersReplaceCaseInsensitively() {
        RecordingTransport transport = new RecordingTransport(
                new HttpResponse(204, Map.of(), null));
        Context context = context("https://example.com/service", transport,
                () -> Map.of("authorization", "Bearer configured", "accept", "text/plain",
                        "content-type", "text/plain", "if-match", "old"));

        EntityOperations.executeAsync(context, HttpMethod.GET, context.basePath().addSegment("People"),
                null, Map.of("Accept", "application/xml", "Content-Type", "application/json",
                        "If-Match", "new"));

        HttpRequest request = transport.lastRequest;
        assertEquals(List.of("application/xml"), request.headers().get("ACCEPT"));
        assertEquals(List.of("application/json"), request.headers().get("content-type"));
        assertEquals(List.of("new"), request.headers().get("if-match"));
        assertEquals(List.of("Bearer configured"), request.headers().get("Authorization"));
        assertEquals(List.of("4.0"), request.headers().get("OData-Version"));
        assertEquals(List.of("4.01"), request.headers().get("OData-MaxVersion"));
    }

    @Test
    void customTransportReceivesDefaultAcceptAndODataVersions() {
        RecordingTransport transport = new RecordingTransport(new HttpResponse(204, Map.of(), null));
        Context context = context("https://example.com/service", transport);

        EntityOperations.executeAsync(context, HttpMethod.GET,
                context.basePath().addSegment("People"), null, null);

        assertEquals(List.of("application/json"), transport.lastRequest.headers().get("Accept"));
        assertEquals(List.of("4.0"), transport.lastRequest.headers().get("OData-Version"));
        assertEquals(List.of("4.01"), transport.lastRequest.headers().get("OData-MaxVersion"));
    }

    @Test
    void streamTransportExceptionIsReturnedThroughTheFuture() {
        RecordingTransport transport = new RecordingTransport();
        transport.throwOnStream = true;
        Context context = context("https://example.com/service", transport);

        CompletableFuture<InputStream> future = assertDoesNotThrow(() ->
                EntityOperations.streamMediaAsync(context, context.basePath().addSegment("Media")));

        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void streamInterceptorExceptionIsReturnedThroughTheFuture() {
        RecordingTransport transport = new RecordingTransport();
        HttpInterceptor interceptor = new HttpInterceptor() {
            @Override
            public HttpResponse intercept(HttpRequest request, HttpTransport delegate) {
                return delegate.submit(request).join();
            }

            @Override
            public CompletableFuture<InputStream> stream(HttpRequest request, HttpTransport delegate) {
                throw new IllegalStateException("interceptor exploded");
            }
        };
        Context context = Context.builder().baseUrl("https://example.com/service")
                .transport(transport).interceptors(List.of(interceptor)).build();

        CompletableFuture<InputStream> future = assertDoesNotThrow(() ->
                EntityOperations.streamMediaAsync(context, context.basePath().addSegment("Media")));

        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void batchAsyncTransportExceptionIsReturnedThroughTheFuture() {
        RecordingTransport transport = new RecordingTransport();
        transport.throwOnSubmit = true;
        Context context = context("https://example.com/service", transport);

        CompletableFuture<?> future = assertDoesNotThrow(() -> context.batch()
                .add(BatchOperation.get("People"))
                .executeAsync());

        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void cachedChainNeverUsesAPreviouslySuppliedTransport() {
        RecordingTransport first = new RecordingTransport(new HttpResponse(200, Map.of(), null));
        RecordingTransport second = new RecordingTransport(new HttpResponse(200, Map.of(), null));
        HttpInterceptor interceptor = (request, delegate) -> delegate.submit(request).join();
        Context context = Context.builder().baseUrl("https://example.com/service")
                .transport(first).interceptors(List.of(interceptor)).build();

        HttpTransport firstChain = EntityOperations.buildTransportChain(context, first);
        HttpTransport secondChain = EntityOperations.buildTransportChain(context, second);
        secondChain.submit(HttpRequest.builder().url(context.baseUrl() + "/People").build()).join();

        assertEquals(1, second.submitCount);
        assertEquals(0, first.submitCount);
        assertNotSame(firstChain, secondChain);
    }

    @Test
    void invalidAuthHeadersFailBeforeTransportSubmission() {
        RecordingTransport transport = new RecordingTransport();
        Context context = context("https://example.com/service", transport,
                () -> Map.of("Bad Header", "value"));

        assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.executeAsync(context, HttpMethod.GET,
                        context.basePath().addSegment("People"), null, null));
        assertEquals(0, transport.submitCount);
    }

    public static class WriteEntity implements ODataEntityType {
        private String name;
        private String etag;

        public WriteEntity() {
        }

        @JsonProperty("Name")
        public void setName(String name) {
            this.name = name;
        }

        @JsonProperty("@odata.etag")
        public void setEtag(String etag) {
            this.etag = etag;
        }

        public String getName() {
            return name;
        }

        @Override
        public String odataTypeName() {
            return "Test.WriteEntity";
        }

        @Override
        public Set<String> getChangedFields() {
            return Set.of();
        }

        @Override
        public Object getKey() {
            return name;
        }

        @Override
        public Optional<String> getETag() {
            return Optional.ofNullable(etag);
        }

        @Override
        public Map<String, Object> getUnmappedFields() {
            return Map.of();
        }

        @Override
        public ContextPath getContextPath() {
            return null;
        }

        @Override
        public void applyETagFromResponse(String value) {
            if (value != null && !value.isEmpty()) {
                this.etag = value;
            }
        }
    }

    @Test
    void postPutAndPatchCaptureHeaderOnlyEtags() {
        RecordingTransport transport = new RecordingTransport(
                response("{\"Name\":\"x\"}", Map.of("etag", List.of("header-post"))),
                response("{\"Name\":\"x\"}", Map.of("etag", List.of("header-put"))),
                response("{\"Name\":\"x\"}", Map.of("etag", List.of("header-patch"))));
        Context context = context("https://example.com/service", transport);
        ContextPath path = context.basePath().addSegment("Writes");

        WriteEntity post = EntityOperations.executePostEntity(context, path, new WriteEntity(), WriteEntity.class);
        WriteEntity put = EntityOperations.executePutEntity(context, path, new WriteEntity(), WriteEntity.class);
        WriteEntity patch = EntityOperations.executePatchEntity(context, path, new WriteEntity(), WriteEntity.class);

        assertEquals("header-post", post.getETag().orElseThrow());
        assertEquals("header-put", put.getETag().orElseThrow());
        assertEquals("header-patch", patch.getETag().orElseThrow());
    }

    @Test
    void bodyAnnotationWinsOverWriteResponseHeader() {
        RecordingTransport transport = new RecordingTransport(
                response("{\"@odata.etag\":\"body\"}", Map.of("ETag", List.of("header"))),
                response("{\"@odata.etag\":\"body\"}", Map.of("ETag", List.of("header"))),
                response("{\"@odata.etag\":\"body\"}", Map.of("ETag", List.of("header"))));
        Context context = context("https://example.com/service", transport);
        ContextPath path = context.basePath().addSegment("Writes");

        assertEquals("body", EntityOperations.executePostEntity(context, path,
                new WriteEntity(), WriteEntity.class).getETag().orElseThrow());
        assertEquals("body", EntityOperations.executePutEntity(context, path,
                new WriteEntity(), WriteEntity.class).getETag().orElseThrow());
        assertEquals("body", EntityOperations.executePatchEntity(context, path,
                new WriteEntity(), WriteEntity.class).getETag().orElseThrow());
    }

    @Test
    void conditionalWritesCaptureEtagsAndEmptyResponsesUpdateTheSubmittedEntity() {
        RecordingTransport transport = new RecordingTransport(
                response("{\"Name\":\"x\"}", Map.of("ETag", List.of("conditional-put"))),
                new HttpResponse(204, Map.of("ETag", List.of("empty-post")), null));
        Context context = context("https://example.com/service", transport);
        ContextPath path = context.basePath().addSegment("Writes");

        WriteEntity put = EntityOperations.executePutEntityWithETag(context, path,
                new WriteEntity(), WriteEntity.class, "if-match");
        assertEquals("conditional-put", put.getETag().orElseThrow());

        WriteEntity submitted = new WriteEntity();
        assertNull(EntityOperations.executePostEntity(context, path, submitted, WriteEntity.class));
        assertEquals("empty-post", submitted.getETag().orElseThrow());
    }

    @Test
    void collectionEnvelopeMustBeAnObjectWithAnArrayValue() {
        for (String body : List.of("{}", "{\"value\":{}}", "null")) {
            RecordingTransport transport = new RecordingTransport(response(body));
            Context context = context("https://example.com/service", transport);
            assertThrows(ODataException.class,
                    () -> EntityOperations.executeAndGetCollection(context,
                            context.basePath().addSegment("People"), Object.class), body);
        }
    }

    static final class FailingSerializer implements Serializer {
        @Override
        public <T> byte[] serialize(T value, Class<T> type) {
            return new byte[0];
        }

        @Override
        public <T> T deserialize(byte[] data, Class<T> type) {
            throw new IllegalStateException("element conversion failed");
        }

        @Override
        public <T> T deserialize(byte[] data, Type type) {
            throw new IllegalStateException("element conversion failed");
        }
    }

    @Test
    void collectionElementConversionFailuresAreODataExceptions() {
        RecordingTransport transport = new RecordingTransport(response("{\"value\":[{}]}"));
        Context context = Context.builder().baseUrl("https://example.com/service")
                .transport(transport).serializer(new FailingSerializer()).build();

        assertThrows(ODataException.class,
                () -> EntityOperations.executeAndGetCollection(context,
                        context.basePath().addSegment("People"), Object.class));
    }

    static final class TrackingJacksonSerializer extends JacksonSerializer {
        private int classDeserializations;

        @Override
        public <T> T deserialize(byte[] data, Class<T> type) {
            classDeserializations++;
            return super.deserialize(data, type);
        }
    }

    @Test
    void configuredJacksonSerializerSubclassIsUsedForCollectionElements() {
        TrackingJacksonSerializer serializer = new TrackingJacksonSerializer();
        RecordingTransport transport = new RecordingTransport(
                response("{\"value\":[{\"Name\":\"x\"}]}"));
        Context context = Context.builder().baseUrl("https://example.com/service")
                .transport(transport).serializer(serializer).build();

        CollectionPage<WriteEntity> page = EntityOperations.executeAndGetCollection(context,
                context.basePath().addSegment("Writes"), WriteEntity.class);

        assertEquals(1, page.currentPage().size());
        assertEquals(1, serializer.classDeserializations);
    }

    public static class Animal {
        @JsonProperty("Name")
        protected String name;
        @JsonProperty("Friends")
        protected List<Animal> friends = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Animal> getFriends() {
            return friends;
        }

        public void setFriends(List<Animal> friends) {
            this.friends = friends;
        }
    }

    public static class Cat extends Animal {
        @JsonProperty("LivesIndoors")
        protected Boolean livesIndoors;

        public Boolean getLivesIndoors() {
            return livesIndoors;
        }

        public void setLivesIndoors(Boolean livesIndoors) {
            this.livesIndoors = livesIndoors;
        }
    }

    static final class AnimalSchemaInfo implements SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "Test.Cat".equals(name) ? Cat.class : null;
        }
    }

    @Test
    void absoluteODataTypeAndNestedExpandedEntitiesResolveThroughSchemaInfo() {
        RecordingTransport transport = new RecordingTransport(response(
                "{\"@odata.type\":\"https://example.test/$metadata#Test.Cat\","
                        + "\"Name\":\"Root\",\"LivesIndoors\":true,"
                        + "\"Friends\":[{\"@odata.type\":\"https://example.test/$metadata#Test.Cat\","
                        + "\"Name\":\"Nested\",\"LivesIndoors\":false}]}"));
        Context context = context("https://example.com/service", transport);

        Animal result = EntityOperations.executeAndGetEntity(context,
                context.basePath().addSegment("Animals"), Animal.class, new AnimalSchemaInfo());

        assertInstanceOf(Cat.class, result);
        assertEquals(Boolean.TRUE, ((Cat) result).getLivesIndoors());
        assertInstanceOf(Cat.class, result.getFriends().get(0));
        assertEquals(Boolean.FALSE, ((Cat) result.getFriends().get(0)).getLivesIndoors());
    }

    @Test
    void nestedExpandedEntitiesResolveEvenWhenCollectionElementsCarryNoTopLevelODataType() {
        RecordingTransport transport = new RecordingTransport(response(
                "{\"value\":[{\"Name\":\"Root\",\"Friends\":["
                        + "{\"@odata.type\":\"https://example.test/$metadata#Test.Cat\","
                        + "\"Name\":\"Nested\",\"LivesIndoors\":false}]}]}"));
        Context context = context("https://example.com/service", transport);

        CollectionPage<Animal> page = EntityOperations.executeAndGetCollection(context,
                context.basePath().addSegment("Animals"), Animal.class, new AnimalSchemaInfo());

        Animal element = page.currentPage().get(0);
        assertInstanceOf(Cat.class, element.getFriends().get(0));
        assertEquals(Boolean.FALSE, ((Cat) element.getFriends().get(0)).getLivesIndoors());
    }

    public static class Address {
        @JsonProperty("Street")
        protected String street;
        public String getStreet() {
            return street;
        }
        public void setStreet(String street) {
            this.street = street;
        }
    }

    public static class Location extends Address {
        @JsonProperty("Building")
        protected String building;
        public String getBuilding() {
            return building;
        }
        public void setBuilding(String building) {
            this.building = building;
        }
    }

    static final class AddressSchemaInfo implements SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "NS.Location".equals(name) ? Location.class : null;
        }
    }

    @Test
    void absoluteODataTypeResolvesForValueWrappedComplexResults() {
        RecordingTransport transport = new RecordingTransport(response(
                "{\"@odata.context\":\"x\",\"value\":{"
                        + "\"@odata.type\":\"https://example.test/$metadata#NS.Location\","
                        + "\"Street\":\"Main\",\"Building\":\"Annex\"}}"));
        Context context = context("https://example.com/service", transport);

        Address result = EntityOperations.invokeComplexSync(context,
                context.basePath().addSegment("Address"), HttpMethod.GET, null,
                Address.class, new AddressSchemaInfo());

        assertInstanceOf(Location.class, result);
        assertEquals("Annex", ((Location) result).getBuilding());
    }

    @Test
    void removeRefRejectsNullAndBlankTargets() {
        RecordingTransport transport = new RecordingTransport();
        Context context = context("https://example.com/service", transport);
        ContextPath path = context.basePath().addSegment("People").addSegment("Friends");

        assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.removeRef(context, path, null));
        assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.removeRef(context, path, "  "));
        assertEquals(0, transport.submitCount);
    }
}
