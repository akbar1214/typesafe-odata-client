package com.modernodata.runtime.internal;

import com.modernodata.runtime.batch.BatchOperation;
import com.modernodata.runtime.batch.BatchResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartHelperTest {

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
