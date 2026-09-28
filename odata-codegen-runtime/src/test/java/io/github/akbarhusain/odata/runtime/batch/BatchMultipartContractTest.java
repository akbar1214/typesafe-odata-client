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
import java.util.LinkedHashMap;
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
    void queryOptionsInOperationUrlsAreNotTreatedAsContentIdReferences() {
        BatchOperation read = BatchOperation.get("People?$skiptoken=abc&$top=5");
        BatchOperation computed = BatchOperation.get("People?$compute=Price%20mul%202%20as%20DoublePrice");
        String body = new String(MultipartHelper.encodeBatchRequest("batch_x", List.of(read, computed)),
                StandardCharsets.UTF_8);

        assertTrue(body.contains("People?$skiptoken=abc&$top=5"), body);
        assertTrue(body.contains("People?$compute=Price"), body);
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
    void objectHeaderOverloadFailsFastOnWronglyTypedMaps() {
        IllegalArgumentException stringValues = assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", (Object) Map.of("X-Test", "not-a-list")));
        assertTrue(stringValues.getMessage().contains("X-Test"));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", (Object) Map.of("X-Test", List.of(1))));
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People", (Object) Map.of(1, List.of("x"))));
        assertDoesNotThrow(() -> BatchOperation.get("People", (Object) Map.of("X-Test", List.of("ok"))));
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

    /**
     * OData v4.01 Part 1 §11.7.4 canonical failed-change-set response: the service
     * answers the whole change set with a SINGLE {@code application/http} part carrying
     * {@code 424 Failed Dependency} and NO {@code Content-ID} (the spec only requires the
     * echo when the request supplied one, and a collapsed error part has no id to echo).
     * For a one-operation change set the collapsed shape is indistinguishable from the
     * success shape by count alone, so it must be recognised by the absence of the id.
     */
    @Test
    void singleOperationFailedChangesetWithoutContentIdIsCorrelated() {
        String body = "--batch_r\r\n"
                + "Content-Type: multipart/mixed; boundary=cs_r\r\n\r\n"
                + "--cs_r\r\nContent-Type: application/http\r\n\r\n"
                + "HTTP/1.1 424 Failed Dependency\r\n\r\n"
                + "{\"error\":{\"message\":\"atomic change set failed\"}}\r\n"
                + "--cs_r--\r\n"
                + "--batch_r--\r\n";

        BatchResponse result = context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_r")),
                bytes(body))))
                .batch()
                .addChangeset(new Changeset(List.of(BatchOperation.post("Customers", bytes("{}")))))
                .execute();

        assertEquals(1, result.size());
        assertEquals(424, result.get(0).statusCode());
        // The collapsed part carries no Content-ID of its own — that is what makes the
        // shape identifiable — so the correlation is exposed through relatedContentIds.
        assertEquals(Set.of("1"), result.get(0).relatedContentIds());
        assertNull(result.get(0).contentId());
        assertEquals("1", result.getByContentId("1").relatedContentIds().iterator().next());
    }

    /**
     * A collapsed change-set failure must never be taken from a standalone operation's
     * part. {@code associateUnkeyedFlatFailedParts} picks "the first failing unkeyed part,
     * anywhere" and removes it from the positional pool, so a coincidentally failing
     * standalone GET is stolen and every later positional result shifts by one — returning
     * another operation's status code and body with no error at all.
     */
    @Test
    void collapsedChangesetFailureIsNotTakenFromAFailingStandaloneOperation() {
        String body = "--batch_r\r\n"
                + "Content-Type: application/http\r\n\r\n"
                + "HTTP/1.1 404 Not Found\r\n\r\n{\"err\":\"GET failed\"}\r\n"
                + "--batch_r\r\n"
                + "Content-Type: application/http\r\n\r\n"
                + "HTTP/1.1 500 Internal Server Error\r\n\r\n{\"err\":\"changeset failed\"}\r\n"
                + "--batch_r--\r\n";

        BatchResponse result = context(new StubTransport(new HttpResponse(200,
                Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_r")),
                bytes(body))))
                .batch()
                .add(BatchOperation.get("People('nobody')"))
                .addChangeset(new Changeset(List.of(
                        BatchOperation.post("Customers", bytes("{}")),
                        BatchOperation.post("Orders", bytes("{}")))))
                .execute();

        assertEquals(404, result.get(0).statusCode(),
                "the standalone GET must keep its own result, not the change set's failure");
        assertEquals(500, result.getByContentId("1").statusCode(),
                "the collapsed change-set failure belongs to Content-IDs 1 and 2");
        assertEquals(500, result.getByContentId("2").statusCode());
    }

    /**
     * A referenced operation's URL is spliced into the middle of the referring path. When
     * the referenced URL carries a query string the splice produces
     * {@code POST Customers?$expand=Orders/Orders HTTP/1.1} — a syntactically invalid
     * request target the service rejects with an opaque 400. Fail locally instead.
     */
    @Test
    void contentIdReferenceToAnOperationCarryingAQueryIsRejected() {
        Changeset changeset = new Changeset(List.of(
                BatchOperation.post("Customers?$expand=Orders", bytes("{}"), "1"),
                BatchOperation.post("$1/Orders", bytes("{}"), "2")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MultipartHelper.encodeBatchRequest("batch_x", List.of(changeset)));
        assertTrue(failure.getMessage().contains("Customers"), failure.getMessage());
    }

    /**
     * RFC 9110 §15.2: "A client MUST be able to parse one or more 1xx responses received
     * prior to a final response." Taking {@code lines[0]} as the final status line turns a
     * {@code 100 Continue} into the operation's result and pushes the real status and body
     * into the body — and {@code isSuccessful()} then reports false for a request the
     * service accepted.
     */
    @Test
    void interimResponseLineBeforeTheFinalResponseIsSkipped() {
        String body = "--batch_r\r\n"
                + "Content-Type: application/http\r\n\r\n"
                + "HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 201 Created\r\n\r\n"
                + "{}\r\n"
                + "--batch_r--\r\n";

        List<BatchResult<?>> decoded = MultipartHelper.decodeResponse("batch_r", bytes(body));

        assertEquals(1, decoded.size());
        assertEquals(201, decoded.get(0).statusCode());
        assertTrue(decoded.get(0).isSuccessful());
        assertEquals("{}", new String(decoded.get(0).body(), StandardCharsets.UTF_8));
    }

    /**
     * The {@code catch (IllegalArgumentException)} that reports "Invalid multipart
     * response boundary" also covers part-header parsing, so a malformed embedded header
     * is misattributed to the boundary. Keep the boundary diagnosis specific to the
     * boundary.
     */
    @Test
    void malformedPartHeaderIsNotReportedAsABoundaryProblem() {
        String body = "--batch_r\r\n"
                + "Content-Type: application/http\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nBad Header: x\r\n\r\n"
                + "{}\r\n"
                + "--batch_r--\r\n";

        ODataException failure = assertThrows(ODataException.class,
                () -> context(new StubTransport(new HttpResponse(200,
                        Map.of("Content-Type", List.of("multipart/mixed; boundary=batch_r")),
                        bytes(body))))
                        .batch().add(BatchOperation.get("People")).execute());

        assertTrue(failure.getMessage().toLowerCase().contains("header"), failure.getMessage());
        assertFalse(failure.getMessage().contains("boundary"), failure.getMessage());
    }

    /**
     * A service that emits two {@code Content-Type} field lines differing only in the
     * boundary spelling ({@code boundary=abc} vs {@code boundary="abc"}) is tolerated by
     * the batch layer, which compares the extracted boundaries. A response-side rejection
     * of differing Content-Type values fires first, inside {@code new HttpResponse(...)},
     * making that comparison unreachable and reporting a legal response as a request
     * failure. RFC 9110 §5.3 folds duplicate field lines into one comma-joined value, so
     * construction must not reject them; the batch layer decides.
     */
    @Test
    void batchResponseWithQuotedBoundaryInSecondContentTypeIsDecodedNotRejected() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(
                "multipart/mixed; boundary=batch_r",
                "multipart/mixed; boundary=\"batch_r\""));
        String body = "--batch_r\r\n"
                + "Content-Type: application/http\r\n\r\n"
                + "HTTP/1.1 200 OK\r\n\r\n"
                + "{}\r\n"
                + "--batch_r--\r\n";

        BatchResponse result = assertDoesNotThrow(() -> context(new StubTransport(
                new HttpResponse(200, headers, bytes(body))))
                .batch().add(BatchOperation.get("People")).execute());

        assertEquals(200, result.get(0).statusCode());
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
