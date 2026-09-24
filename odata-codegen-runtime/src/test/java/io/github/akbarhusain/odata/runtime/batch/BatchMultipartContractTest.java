package io.github.akbarhusain.odata.runtime.batch;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.internal.MultipartHelper;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class BatchMultipartContractTest {

    @Test
    void changesetRejectsEmptyAndGetOperations() {
        assertThrows(IllegalArgumentException.class, () -> new Changeset(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Changeset(List.of(BatchOperation.get("Customers"))));
    }

    @Test
    void multipartChangesetEncoderRejectsGetOperations() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartHelper.encodeChangeset("b", List.of(BatchOperation.get("Customers"))));
    }

    @Test
    void batchRejectsNullAndBlankEntries() {
        Context context = context(new StubTransport(emptySuccess()));
        assertThrows(IllegalArgumentException.class, () -> context.batch().add(null));
        assertThrows(IllegalArgumentException.class, () -> context.batch().addChangeset(null));
        assertThrows(IllegalArgumentException.class, () -> BatchOperation.get(" "));
    }

    @Test
    void explicitStandaloneIdAndChangesetReferenceAreEncoded() {
        BatchOperation first = BatchOperation.post("Customers", "{\"Name\":\"A\"}".getBytes(StandardCharsets.UTF_8), "1");
        BatchOperation second = BatchOperation.post("$1/Orders", "{\"Customer\":\"A\"}".getBytes(StandardCharsets.UTF_8), "2");
        String body = new String(MultipartHelper.encodeBatchRequest("batch_x",
                List.of(new Changeset(List.of(first, second)), BatchOperation.get("Customers", "3"))),
                StandardCharsets.UTF_8);

        assertTrue(body.contains("Content-ID: 1"));
        assertTrue(body.contains("Content-ID: 2"));
        assertTrue(body.contains("Content-ID: 3"));
        assertTrue(body.contains("POST Customers/Orders HTTP/1.1")
                || body.contains("POST https://example.com/Customers/Orders HTTP/1.1"));
    }

    @Test
    void changesetResponsesAreReorderedBySubmittedContentId() {
        String response = response(
                "--batch_r\n"
                        + "Content-Type: application/http\nContent-ID: 3\n\n"
                        + "HTTP/1.1 200 OK\n\n"
                        + "{\"id\":\"third\"}\n"
                        + "--batch_r\n"
                        + "Content-Type: multipart/mixed; BOUNDARY = cs_r\n\n"
                        + "--cs_r\nContent-Type: application/http\nContent-ID: 2\n\n"
                        + "HTTP/1.1 201 Created\n\n{\"id\":\"second\"}\n"
                        + "--cs_r\nContent-Type: application/http\nContent-ID: 1\n\n"
                        + "HTTP/1.1 201 Created\n\n{\"id\":\"first\"}\n"
                        + "--cs_r--\n"
                        + "--batch_r--\n");
        StubTransport transport = new StubTransport(new HttpResponse(200,
                Map.of("content-type", List.of("MuLtIpArT/MiXeD; BoUnDaRy = batch_r")), response.getBytes(StandardCharsets.UTF_8)));
        BatchResponse result = context(transport)
                .batch()
                .addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}")))))
                .add(BatchOperation.get("Customers", "3"))
                .execute();

        assertEquals("1", result.get(0).contentId());
        assertEquals("2", result.get(1).contentId());
        assertEquals("3", result.get(2).contentId());
        assertEquals("3", result.wireOrder().get(0).contentId());
        assertEquals("2", result.wireOrder().get(1).contentId());
    }

    @Test
    void topLevelChangesetGroupsAreMatchedByTheirContentIds() {
        String response = "--batch_groups\n"
                + "Content-Type: multipart/mixed; boundary=cs_two\n\n"
                + "--cs_two\nContent-Type: application/http\nContent-ID: 2\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n--cs_two--\n"
                + "--batch_groups\n"
                + "Content-Type: multipart/mixed; boundary=cs_one\n\n"
                + "--cs_one\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n--cs_one--\n--batch_groups--\n";
        StubTransport transport = new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_groups")),
                response.getBytes(StandardCharsets.UTF_8)));
        BatchResponse result = context(transport)
                .batch()
                .addChangeset(new Changeset(List.of(BatchOperation.post("One", bytes("{}")))))
                .addChangeset(new Changeset(List.of(BatchOperation.post("Two", bytes("{}")))))
                .execute();

        assertEquals("1", result.get(0).contentId());
        assertEquals("2", result.get(1).contentId());
        assertEquals("2", result.wireOrder().get(0).contentId());
        assertEquals("1", result.wireOrder().get(1).contentId());
    }

    @Test
    void failedChangesetCollapseIsVisibleForEverySubmittedId() {
        String response = "--batch_c\n"
                + "Content-Type: multipart/mixed; boundary=cs_c\n\n"
                + "--cs_c\nContent-Type: application/http\n\n"
                + "HTTP/1.1 500 Internal Server Error\n\n"
                + "{\"error\":\"atomic failure\"}\n"
                + "--cs_c--\n--batch_c--\n";
        StubTransport transport = new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_c")),
                response.getBytes(StandardCharsets.UTF_8)));
        BatchResponse result = context(transport)
                .batch()
                .addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}")))))
                .execute();

        assertEquals(2, result.size());
        BatchResult<?> first = result.getByContentId("1");
        BatchResult<?> second = result.getByContentId("2");
        assertSame(first, second);
        assertEquals(Set.of("1", "2"), first.relatedContentIds());
        assertNotNull(first.contentIdGroup());
        assertEquals(0, first.wireIndex());
    }

    @Test
    void flatFailedChangesetPartCollapsesForEverySubmittedOperationId() {
        String response = "--batch_flat_id\n"
                + "Content-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 500 Internal Server Error\n\n"
                + "{\"error\":\"atomic failure\"}\n"
                + "--batch_flat_id--\n";
        BatchResponse result = context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_flat_id")),
                response.getBytes(StandardCharsets.UTF_8))))
                .batch()
                .addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}")))))
                .execute();

        BatchResult<?> first = result.getByContentId("1");
        BatchResult<?> second = result.getByContentId("2");
        assertSame(first, second);
        assertEquals(500, first.statusCode());
        assertEquals(Set.of("1", "2"), first.relatedContentIds());
    }

    @Test
    void flatUnkeyedFailedChangesetPartCollapsesForEverySubmittedOperationId() {
        String response = "--batch_flat_unkeyed\n"
                + "Content-Type: application/http\n\n"
                + "HTTP/1.1 500 Internal Server Error\n\n"
                + "{\"error\":\"atomic failure\"}\n"
                + "--batch_flat_unkeyed--\n";
        BatchResponse result = context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_flat_unkeyed")),
                response.getBytes(StandardCharsets.UTF_8))))
                .batch()
                .addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}")))))
                .execute();

        assertEquals(2, result.size());
        assertSame(result.get(0), result.get(1));
        assertEquals(500, result.get(0).statusCode());
        assertEquals(Set.of("1", "2"), result.get(0).relatedContentIds());
    }

    @Test
    void explicitStringContentIdReferencesAreResolvedBeforeServiceRootResolution() {
        BatchOperation customer = BatchOperation.post("Customers", bytes("{}"), "cust");
        BatchOperation order = BatchOperation.post("$cust/Orders", bytes("{}"), "order");
        String responseBody = "--ref_response\n"
                + "Content-Type: multipart/mixed; boundary=ref_changeset\n\n"
                + "--ref_changeset\nContent-Type: application/http\nContent-ID: cust\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n"
                + "--ref_changeset\nContent-Type: application/http\nContent-ID: order\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n"
                + "--ref_changeset--\n--ref_response--\n";
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=ref_response")),
                responseBody.getBytes(StandardCharsets.UTF_8)));

        context(transport).batch()
                .addChangeset(new Changeset(List.of(customer, order)))
                .execute();

        String body = new String(transport.lastRequest.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("POST https://example.com/Customers/Orders HTTP/1.1"), body);
        assertFalse(body.contains("$cust/Orders"), body);
    }

    @Test
    void missingDuplicateAndUnexpectedContentIdsFailValidation() {
        String missing = "--batch_m\n"
                + "Content-Type: multipart/mixed; boundary=cs_m\n\n"
                + "--cs_m\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n--cs_m--\n--batch_m--\n";
        assertThrows(ODataException.class, () -> context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_m")), missing.getBytes(StandardCharsets.UTF_8))))
                .batch().addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}"))))).execute());

        String duplicate = "--batch_d\n"
                + "Content-Type: multipart/mixed; boundary=cs_d\n\n"
                + "--cs_d\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n"
                + "--cs_d\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n--cs_d--\n--batch_d--\n";
        assertThrows(ODataException.class, () -> context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_d")), duplicate.getBytes(StandardCharsets.UTF_8))))
                .batch().addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}"))))).execute());

        String unexpected = "--batch_u\n"
                + "Content-Type: multipart/mixed; boundary=cs_u\n\n"
                + "--cs_u\nContent-Type: application/http\nContent-ID: 99\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n"
                + "--cs_u\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 201 Created\n\n{}\n--cs_u--\n--batch_u--\n";
        assertThrows(ODataException.class, () -> context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_u")), unexpected.getBytes(StandardCharsets.UTF_8))))
                .batch().addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}"))))).execute());
    }

    @Test
    void standaloneResponsesWithoutIdsRemainPositionalAndWireOrderIsObservable() {
        String response = "--batch_p\n"
                + "Content-Type: application/http\n\n"
                + "HTTP/1.1 200 OK\n\n{\"n\":1}\n"
                + "--batch_p\nContent-Type: application/http\n\n"
                + "HTTP/1.1 200 OK\n\n{\"n\":2}\n--batch_p--\n";
        StubTransport transport = new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_p")),
                response.getBytes(StandardCharsets.UTF_8)));
        BatchResponse result = context(transport)
                .batch()
                .add(BatchOperation.get("One"))
                .add(BatchOperation.get("Two"))
                .execute();

        assertEquals(2, result.size());
        assertEquals("{\"n\":1}", result.get(0).getText());
        assertEquals("{\"n\":2}", result.get(1).getText());
        assertEquals(0, result.get(0).wireIndex());
        assertEquals(0, result.wireOrder().get(0).wireIndex());
    }

    @Test
    void changesetReferenceRequiresAnEarlierMatchingId() {
        Changeset changeset = new Changeset(List.of(
                BatchOperation.post("Customers", bytes("{}"), "7"),
                BatchOperation.post("$8/Orders", bytes("{}"), "8")));
        assertThrows(IllegalArgumentException.class,
                () -> context(new StubTransport(emptySuccess())).batch().addChangeset(changeset).execute());
    }

    @Test
    void mediaFactorySetsBinaryContentTypeAndPreservesBytes() {
        byte[] binary = new byte[]{0, (byte) 0xff, 1, 2, (byte) 0x80};
        BatchOperation operation = BatchOperation.media("Media('x')", binary, "image/png");
        assertEquals("image/png", operation.headers().get("Content-Type").get(0));
        String encoded = new String(MultipartHelper.encodeRequest("media_b", List.of(operation)), StandardCharsets.ISO_8859_1);
        assertTrue(encoded.contains("Content-Type: image/png"));
        assertArrayEquals(binary, operation.body());
    }

    @Test
    void requestAndBatchHeadersRejectFramingAndHopByHopFields() {
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("Content-Length", List.of("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("Connection", List.of("close"))));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("Content-Transfer-Encoding", List.of("chunked"))));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("Bad Header", List.of("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("X-Test", List.of("bad\nvalue"))));
    }

    @Test
    void requestHeadersAreValidatedAndContentTypeIsCanonical() {
        HttpRequest request = HttpRequest.builder()
                .url("https://example.com/People")
                .header("content-type", "application/json")
                .header("Content-Type", "application/json;odata.metadata=minimal")
                .build();
        assertEquals(List.of("application/json", "application/json;odata.metadata=minimal"),
                request.headers().get("Content-Type"));
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .url("https://example.com").header("Content-Length", "10"));
    }

    @Test
    void validationErrorsDoNotExposeSecrets() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People?access_token=super-secret\r\nX: y"));
        assertFalse(error.getMessage().contains("super-secret"));
        IllegalArgumentException headerError = assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", Map.of("Authorization", List.of("Bearer super-secret\r\n"))));
        assertFalse(headerError.getMessage().contains("super-secret"));
    }

    @Test
    void leadingSlashKeepsServiceRootAndTraversalCannotEscapeIt() {
        CapturingTransport transport = new CapturingTransport();
        context(transport).batch().add(BatchOperation.get("/People/../Orders")).execute();
        assertTrue(new String(transport.lastRequest.body(), StandardCharsets.ISO_8859_1)
                .contains("https://example.com/Orders"));
        assertThrows(IllegalArgumentException.class, () -> context(new StubTransport(emptySuccess()))
                .batch().add(BatchOperation.get("../../admin")).execute());
    }

    @Test
    void malformedHttpSchemeIsNotTreatedAsAbsolute() {
        CapturingTransport transport = new CapturingTransport();
        assertThrows(IllegalArgumentException.class,
                () -> context(transport).batch().add(BatchOperation.get("httpish://example.com/People")).execute());
    }

    @Test
    void mimeTypeAndBoundaryParametersAreCaseInsensitiveAndTokenAnchored() {
        String response = "--batch_mime\n"
                + "Content-Type: MULTIPART/MIXED; x-boundary=wrong; BoUnDaRy = \"cs_mime\"\n\n"
                + "--cs_mime\nContent-Type: application/http\nContent-ID: 1\n\n"
                + "HTTP/1.1 200 OK\n\n{}\n--cs_mime--\n--batch_mime--\n";
        StubTransport transport = new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_mime")),
                response.getBytes(StandardCharsets.UTF_8)));
        BatchResponse result = context(transport)
                .batch().addChangeset(new Changeset(List.of(BatchOperation.post("Customers", bytes("{}")))))
                .execute();
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).contentId());
    }

    @Test
    void boundaryMatchingRejectsNearMatchesAndInvalidEncoders() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartHelper.encodeRequest("bad boundary\r\n", List.of(BatchOperation.get("People"))));
        assertThrows(IllegalArgumentException.class,
                () -> MultipartHelper.encodeRequest("", List.of(BatchOperation.get("People"))));
        String response = "--boundary-extra\nContent-Type: application/http\n\n"
                + "HTTP/1.1 200 OK\n\n{}\n--boundary--\n";
        assertThrows(ODataException.class,
                () -> MultipartHelper.decodeResponse("boundary", response.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyAndBlankMultipartResponsesFailLoudly() {
        assertThrows(ODataException.class,
                () -> MultipartHelper.decodeResponse("b", new byte[0]));
        assertThrows(ODataException.class,
                () -> MultipartHelper.decodeResponse("b", "--b\r\n\r\n--b--\r\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ODataException.class, () -> context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=b")), new byte[0])))
                .batch().add(BatchOperation.get("People")).execute());
    }

    @Test
    void recordsAreDeeplyImmutable() {
        List<String> values = new ArrayList<>(List.of("one"));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("X-Test", values);
        BatchOperation operation = BatchOperation.post("People", new byte[]{1, 2}, headers);
        values.add("two");
        headers.put("X-New", List.of("x"));
        assertEquals(List.of("one"), operation.headers().get("X-Test"));
        assertFalse(operation.headers().containsKey("X-New"));
        assertThrows(UnsupportedOperationException.class, () -> operation.headers().get("X-Test").add("x"));

        List<String> resultValues = new ArrayList<>(List.of("one"));
        BatchResult<?> result = new BatchResult<>(200, Map.of("X-Test", resultValues), new byte[]{3}, Object.class, "1");
        resultValues.clear();
        assertEquals(List.of("one"), result.headers().get("X-Test"));
        assertThrows(UnsupportedOperationException.class, () -> result.headers().get("X-Test").add("x"));
    }

    @Test
    void nullContentIdLookupIsRejected() {
        BatchResponse response = new BatchResponse(List.of(
                new BatchResult<>(200, Map.of(), null, Object.class, "1")));
        assertThrows(IllegalArgumentException.class, () -> response.getByContentId(null));
    }

    @Test
    void synchronousAsyncFailuresAreReturnedAsFailedFutures() {
        Context context = context(new ThrowingTransport());
        CompletableFuture<BatchResponse> future = context.batch()
                .add(BatchOperation.get("People"))
                .executeAsync();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    void typedBatchEntityReadUsesSchemaInfoAndCapturesEtag() {
        BatchResult<?> raw = new BatchResult<>(200,
                Map.of("etag", List.of("W/\"batch-etag\"")), bytes("{\"@odata.type\":\"Test.Sub\",\"name\":\"n\"}"),
                BaseEntity.class, "1");
        BatchResult<BaseEntity> typed = new BatchResult<>(raw.statusCode(), raw.headers(), raw.body(),
                BaseEntity.class, raw.contentId(), raw.relatedContentIds(), raw.contentIdGroup(), raw.wireIndex());
        BaseEntity entity = typed.getEntity(Serializer.createDefault(), new SchemaInfo() {
            @Override
            public Class<?> getClassFromTypeWithNamespace(String name) {
                return "Test.Sub".equals(name) ? SubEntity.class : null;
            }
        });
        assertInstanceOf(SubEntity.class, entity);
        assertEquals("W/\"batch-etag\"", ((ODataEntityType) entity).getETag().orElse(null));
        assertEquals("1", typed.contentId());
    }

    private static Context context(HttpTransport transport) {
        return Context.builder().baseUrl("https://example.com").transport(transport).build();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static HttpResponse emptySuccess() {
        return new HttpResponse(200, Map.of("Content-Type", List.of("multipart/mixed; boundary=empty_b")),
                "--empty_b\r\nContent-Type: application/http\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n--empty_b--\r\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String response(String value) {
        return value;
    }

    private static class StubTransport implements HttpTransport {
        private final HttpResponse response;

        private StubTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingTransport implements HttpTransport {
        private final HttpResponse response;
        private HttpRequest lastRequest;

        private CapturingTransport() {
            this(emptySuccess());
        }

        private CapturingTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            lastRequest = request;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ThrowingTransport implements HttpTransport {
        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            throw new IllegalStateException("synchronous transport failure");
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    public static class BaseEntity implements ODataEntityType {
        private String name;
        private String etag;

        @JsonProperty("name")
        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
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
        public String odataTypeName() {
            return "Test.Base";
        }

        @Override
        public Map<String, Object> getUnmappedFields() {
            return Map.of();
        }

        @Override
        public io.github.akbarhusain.odata.runtime.entity.ContextPath getContextPath() {
            return null;
        }

        @Override
        public java.util.Optional<String> getETag() {
            return java.util.Optional.ofNullable(etag);
        }

        @Override
        public void applyETagFromResponse(String value) {
            etag = value;
        }
    }

    public static class SubEntity extends BaseEntity {
    }
}
