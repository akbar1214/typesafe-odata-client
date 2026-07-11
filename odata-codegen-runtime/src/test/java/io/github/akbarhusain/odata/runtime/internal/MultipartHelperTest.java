package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartHelperTest {

    @Test
    void encodeChangesetEncodesOperationsWithNestedBoundary() {
        byte[] body1 = "{\"UserName\":\"newuser\"}".getBytes(StandardCharsets.UTF_8);
        BatchOperation postOp = BatchOperation.post("People", body1);
        byte[] body2 = "{\"FirstName\":\"Updated\"}".getBytes(StandardCharsets.UTF_8);
        BatchOperation patchOp = BatchOperation.patch("People('newuser')", body2);

        String changesetBoundary = "changeset_1";
        byte[] encoded = MultipartHelper.encodeChangeset(changesetBoundary, List.of(postOp, patchOp));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("--changeset_1"), "Should contain changeset boundary start");
        assertTrue(body.contains("Content-Type: application/http"), "Each op should have Content-Type: application/http");
        assertTrue(body.contains("Content-ID: 1"), "First operation should have Content-ID: 1");
        assertTrue(body.contains("Content-ID: 2"), "Second operation should have Content-ID: 2");
        assertTrue(body.contains("POST People HTTP/1.1"), "Should contain POST request line");
        assertTrue(body.contains("PATCH People('newuser') HTTP/1.1"), "Should contain PATCH request line");
        assertTrue(body.contains("{\"UserName\":\"newuser\"}"), "Should contain POST body");
        assertTrue(body.contains("{\"FirstName\":\"Updated\"}"), "Should contain PATCH body");
        assertTrue(body.contains("--changeset_1--"), "Should end with changeset boundary terminator");
    }

    @Test
    void encodeBatchWithChangesetEncodesNestedBody() {
        byte[] postBody = "{\"UserName\":\"newuser\"}".getBytes(StandardCharsets.UTF_8);
        Changeset cs = new Changeset(List.of(BatchOperation.post("People", postBody)));
        BatchOperation getOp = BatchOperation.get("People");

        String boundary = "batch_1";
        byte[] encoded = MultipartHelper.encodeBatchRequest(boundary, List.of(cs, getOp));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("--batch_1"), "Should contain batch boundary");
        assertTrue(body.contains("Content-Type: multipart/mixed; boundary=changeset"), "Changeset should declare nested multipart/mixed");
        assertTrue(body.contains("Content-Type: application/http"), "Operations should have Content-Type: application/http");
        assertTrue(body.contains("POST People HTTP/1.1"), "Changeset operation should be encoded");
        assertTrue(body.contains("GET People HTTP/1.1"), "Standalone operation should be encoded");
        assertTrue(body.contains("--batch_1--"), "Should end with batch boundary terminator");
    }

    @Test
    void decodeResponseWithChangesetReturnsFlattenedResults() {
        String response = """
            --batch_boundary
            Content-Type: multipart/mixed; boundary=cs_boundary

            --cs_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 1

            HTTP/1.1 201 Created
            Content-Type: application/json

            {"UserName":"newuser"}
            --cs_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 2

            HTTP/1.1 200 OK
            Content-Type: application/json

            {"FirstName":"Updated"}
            --cs_boundary--

            --batch_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            HTTP/1.1 200 OK
            Content-Type: application/json

            [{"UserName":"scott"}]
            --batch_boundary--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, results.size(), "Should flatten changeset operations with standalone ops");
        assertEquals(201, results.get(0).statusCode(), "First changeset result should be 201");
        assertEquals(200, results.get(1).statusCode(), "Second changeset result should be 200");
        assertEquals(200, results.get(2).statusCode(), "Standalone result should be 200");
    }

    @Test
    void decodeResponseChangesetOnlyReturnsFlattenedResults() {
        String response = """
            --batch_boundary
            Content-Type: multipart/mixed; boundary=cs_boundary

            --cs_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 1

            HTTP/1.1 204 No Content

            --cs_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary
            Content-ID: 2

            HTTP/1.1 200 OK

            {"deleted":true}
            --cs_boundary--

            --batch_boundary--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, results.size(), "Should decode both operations within changeset");
        assertEquals(204, results.get(0).statusCode());
        assertEquals(200, results.get(1).statusCode());
    }

    @Test
    void generateBoundaryIsUnique() {
        String b1 = MultipartHelper.generateBoundary();
        String b2 = MultipartHelper.generateBoundary();
        assertNotEquals(b1, b2);
    }

    @Test
    void encodeRequestSingleGet() {
        BatchOperation op = BatchOperation.get("People('scott')");
        String boundary = "test_boundary";
        byte[] encoded = MultipartHelper.encodeRequest(boundary, List.of(op));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("--test_boundary"));
        assertTrue(body.contains("Content-Type: application/http"));
        assertTrue(body.contains("GET People('scott') HTTP/1.1"));
        assertTrue(body.contains("--test_boundary--"));
    }

    @Test
    void encodeRequestMultipleOperations() {
        BatchOperation getOp = BatchOperation.get("People('scott')");
        BatchOperation deleteOp = BatchOperation.delete("People('keith')");
        String boundary = "test_boundary";
        byte[] encoded = MultipartHelper.encodeRequest(boundary, List.of(getOp, deleteOp));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("GET People('scott') HTTP/1.1"));
        assertTrue(body.contains("DELETE People('keith') HTTP/1.1"));
    }

    @Test
    void encodeRequestPatchWithBody() {
        byte[] jsonBody = "{\"FirstName\":\"Scotty\"}".getBytes(StandardCharsets.UTF_8);
        BatchOperation patchOp = BatchOperation.patch("People('scott')", jsonBody);
        String boundary = "test_boundary";
        byte[] encoded = MultipartHelper.encodeRequest(boundary, List.of(patchOp));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("PATCH People('scott') HTTP/1.1"));
        assertTrue(body.contains("Content-Type: application/json"));
        assertTrue(body.contains("{\"FirstName\":\"Scotty\"}"));
    }

    @Test
    void encodeRequestPatchWithEtag() {
        byte[] jsonBody = "{\"FirstName\":\"Scotty\"}".getBytes(StandardCharsets.UTF_8);
        BatchOperation patchOp = BatchOperation.patch("People('scott')", jsonBody, "W/\"12345\"");
        String boundary = "test_boundary";
        byte[] encoded = MultipartHelper.encodeRequest(boundary, List.of(patchOp));
        String body = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(body.contains("If-Match: W/\"12345\""));
    }

    @Test
    void decodeResponseSingleOk() {
        String response = """
            --batch_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            HTTP/1.1 200 OK
            Content-Type: application/json

            {"UserName":"scott","FirstName":"Scott"}
            --batch_boundary--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, results.size());
        assertEquals(200, results.get(0).statusCode());
        assertNotNull(results.get(0).body());
        String body = new String(results.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("UserName"));
    }

    @Test
    void decodeResponseMultipleParts() {
        String response = """
            --batch_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            HTTP/1.1 200 OK
            Content-Type: application/json

            {"UserName":"scott"}
            --batch_boundary
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            HTTP/1.1 204 No Content

            --batch_boundary--
            """;
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary",
                response.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, results.size());
        assertEquals(200, results.get(0).statusCode());
        assertEquals(204, results.get(1).statusCode());
    }

    @Test
    void decodeResponseEmpty() {
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary", new byte[0]);
        assertTrue(results.isEmpty());
    }

    @Test
    void decodeResponseNullBody() {
        List<BatchResult<?>> results = MultipartHelper.decodeResponse("batch_boundary", null);
        assertTrue(results.isEmpty());
    }

    @Test
    void roundTripEncodeDecode() {
        // Encoder creates HTTP requests (GET ... HTTP/1.1)
        // Decoder expects HTTP responses (HTTP/1.1 200 OK)
        // Test encode and decode independently with correct formats

        // Test encoding requests
        BatchOperation getOp = BatchOperation.get("People('scott')");
        byte[] jsonBody = "{\"FirstName\":\"Scotty\"}".getBytes(StandardCharsets.UTF_8);
        BatchOperation patchOp = BatchOperation.patch("People('scott')", jsonBody);

        String boundary = MultipartHelper.generateBoundary();
        byte[] encoded = MultipartHelper.encodeRequest(boundary, List.of(getOp, patchOp));
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // Verify encoded content contains expected operations
        String encodedStr = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(encodedStr.contains("GET People('scott') HTTP/1.1"));
        assertTrue(encodedStr.contains("PATCH People('scott') HTTP/1.1"));
        assertTrue(encodedStr.contains("{\"FirstName\":\"Scotty\"}"));

        // Test decoding with proper HTTP response format
        String responseBoundary = "resp_boundary";
        String response = "--resp_boundary\r\n" +
                "Content-Type: application/http\r\n" +
                "Content-Transfer-Encoding: binary\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "\r\n" +
                "{\"UserName\":\"scott\"}\r\n" +
                "--resp_boundary\r\n" +
                "Content-Type: application/http\r\n" +
                "Content-Transfer-Encoding: binary\r\n" +
                "\r\n" +
                "HTTP/1.1 204 No Content\r\n" +
                "\r\n" +
                "--resp_boundary--\r\n";

        List<BatchResult<?>> decoded = MultipartHelper.decodeResponse(responseBoundary,
                response.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, decoded.size());
        assertEquals(200, decoded.get(0).statusCode());
        assertEquals(204, decoded.get(1).statusCode());
    }
}
