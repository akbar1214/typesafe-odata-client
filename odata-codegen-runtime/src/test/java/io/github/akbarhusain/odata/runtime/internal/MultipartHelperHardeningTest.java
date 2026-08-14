package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResponse;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-3 review findings M1-M6: changeset Content-ID correlation, batch-wide Content-ID
 * uniqueness, loud failures on malformed responses, line-anchored delimiter matching,
 * CRLF-injection rejection, and quoted/case-insensitive boundary parameters.
 */
class MultipartHelperHardeningTest {

    // ------------------------------------------------------------------
    // M1: part-level Content-ID must be propagated into BatchResult
    // ------------------------------------------------------------------

    @Test
    void m1ChangesetContentIdsPropagateToResults() {
        String response = """
            --batch_b
            Content-Type: multipart/mixed; boundary=cs_b

            --cs_b
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 1

            HTTP/1.1 201 Created

            {"UserName":"newuser"}
            --cs_b
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 2

            HTTP/1.1 200 OK

            {"FirstName":"Updated"}
            --cs_b--

            --batch_b
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            HTTP/1.1 200 OK

            [{"UserName":"scott"}]
            --batch_b--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_b",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, results.size());
        assertEquals("1", results.get(0).contentId(), "changeset part Content-ID must be propagated");
        assertEquals("2", results.get(1).contentId(), "changeset part Content-ID must be propagated");
        assertNull(results.get(2).contentId(), "standalone part without Content-ID stays null");
        assertEquals("1", results.get(0).getHeader("Content-ID"),
                "Content-ID must also be visible via getHeader");
    }

    @Test
    void m1BatchResponseGetByContentIdFindsResult() {
        String response = """
            --batch_b
            Content-Type: multipart/mixed; boundary=cs_b

            --cs_b
            Content-Type: application/http
            Content-ID: 7

            HTTP/1.1 404 Not Found

            --cs_b
            Content-Type: application/http
            Content-ID: 8

            HTTP/1.1 201 Created

            {"id":2}
            --cs_b--

            --batch_b--
            """;
        BatchResponse batchResponse = new BatchResponse(
                MultipartHelper.decodeResponse("batch_b", response.getBytes(StandardCharsets.UTF_8)));

        assertEquals(404, batchResponse.getByContentId("7").statusCode(),
                "failed changeset results must be correlatable by Content-ID");
        assertEquals(201, batchResponse.getByContentId("8").statusCode());
        assertNull(batchResponse.getByContentId("unknown"));
    }

    // ------------------------------------------------------------------
    // M2: Content-ID numbering must be unique across the whole batch
    // ------------------------------------------------------------------

    @Test
    void m2MultipleChangesetsGetUniqueContentIds() {
        Changeset first = new Changeset(List.of(
                BatchOperation.post("People", "{\"a\":1}".getBytes()),
                BatchOperation.post("People", "{\"a\":2}".getBytes())));
        Changeset second = new Changeset(List.of(
                BatchOperation.post("People", "{\"a\":3}".getBytes()),
                BatchOperation.post("People", "{\"a\":4}".getBytes())));

        String encoded = new String(
                MultipartHelper.encodeBatchRequest("batch_b", List.of(first, second)),
                StandardCharsets.UTF_8);

        assertTrue(encoded.contains("Content-ID: 3"),
                "second changeset must continue the batch-wide numbering. Got:\n" + encoded);
        assertTrue(encoded.contains("Content-ID: 4"),
                "second changeset must continue the batch-wide numbering");
        assertEquals(4, countOccurrences(encoded, "Content-ID:"),
                "exactly one unique Content-ID per changeset operation");
    }

    // ------------------------------------------------------------------
    // M3: malformed responses must fail loudly, not return empty/partial results
    // ------------------------------------------------------------------

    @Test
    void m3UndecodablePartThrowsInsteadOfSilentDrop() {
        String response = """
            --batch_b
            Content-Type: application/http

            THIS IS NOT AN HTTP RESPONSE
            --batch_b--
            """;
        assertThrows(ODataException.class,
                () -> MultipartHelper.decodeResponse("batch_b", response.getBytes(StandardCharsets.UTF_8)),
                "an unparseable part must throw, not be silently dropped");
    }

    @Test
    void m3MissingClosingBoundaryThrowsInsteadOfTruncating() {
        String response = """
            --batch_b
            Content-Type: application/http

            HTTP/1.1 200 OK

            {"a":1}
            --batch_b
            Content-Type: application/http

            HTTP/1.1 200 OK

            {"a":2}
            """;
        assertThrows(ODataException.class,
                () -> MultipartHelper.decodeResponse("batch_b", response.getBytes(StandardCharsets.UTF_8)),
                "a truncated multipart body (no closing delimiter) must throw");
    }

    // ------------------------------------------------------------------
    // M4: delimiters are only valid at the start of a line
    // ------------------------------------------------------------------

    @Test
    void m4BoundaryBytesInsideBodyDoNotSplitParts() {
        String response = """
            --batch_b
            Content-Type: application/http

            HTTP/1.1 200 OK

            {"note":"contains --batch_b mid-line, must not split"}
            --batch_b--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_b",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, results.size(), "a delimiter not at line start must not split the part");
        String body = new String(results.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("must not split"), "body must be intact. Got: " + body);
    }

    // ------------------------------------------------------------------
    // M5: CRLF injection rejected; no duplicate Content-Type
    // ------------------------------------------------------------------

    @Test
    void m5UrlWithLineBreakIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.get("People\r\nX-Injected: 1"),
                "CR/LF in a batch URL must be rejected (header/request injection)");
        assertThrows(IllegalArgumentException.class,
                () -> BatchOperation.post("People", new byte[] {'x'},
                        Map.of("X-Custom", List.of("v\r\nHost: evil"))),
                "CR/LF in a batch header value must be rejected");
    }

    @Test
    void m5UserContentTypeNotDuplicated() {
        byte[] body = "plain".getBytes(StandardCharsets.UTF_8);
        BatchOperation op = BatchOperation.post("People", body,
                Map.of("Content-Type", List.of("text/plain")));

        String encoded = new String(MultipartHelper.encodeRequest("b", List.of(op)),
                StandardCharsets.UTF_8);

        assertTrue(encoded.contains("Content-Type: text/plain"),
                "the user-supplied Content-Type must be used");
        assertFalse(encoded.contains("application/json"),
                "no implicit application/json Content-Type when the user supplied one");
    }

    // ------------------------------------------------------------------
    // M6: quoted boundary values in nested multipart Content-Type
    // ------------------------------------------------------------------

    @Test
    void m6QuotedNestedBoundaryIsHandled() {
        String response = """
            --batch_b
            Content-Type: multipart/mixed; boundary="cs_quoted"

            --cs_quoted
            Content-Type: application/http
            Content-ID: 1

            HTTP/1.1 201 Created

            {"a":1}
            --cs_quoted
            Content-Type: application/http
            Content-ID: 2

            HTTP/1.1 200 OK

            {"a":2}
            --cs_quoted--

            --batch_b--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_b",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, results.size(), "quoted boundary parameter must be honored");
        assertEquals("1", results.get(0).contentId());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
