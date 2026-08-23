package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M11: the status-line regex required HTTP/1.x form ({@code HTTP/\d.\d}), rejecting
 * {@code HTTP/2 200} — the version some services emit inside batch parts. A valid
 * part must not be discarded as malformed.
 */
class MultipartHttp2StatusLineTest {

    @Test
    void http2StatusLineInsideBatchPartDecodes() {
        String boundary = "batch_h2";
        String body = "--" + boundary + "\r\n"
                + "Content-Type: application/http\r\n"
                + "Content-Transfer-Encoding: binary\r\n"
                + "\r\n"
                + "HTTP/2 200\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{\"value\":[]}\r\n"
                + "--" + boundary + "--\r\n";

        List<BatchResult<?>> results = MultipartHelper.decodeResponse(boundary, body.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, results.size());
        assertEquals(200, results.get(0).statusCode(), "HTTP/2 status line must parse");
    }

    @Test
    void http10StatusLineStillDecodes() {
        String boundary = "batch_h1";
        String body = "--" + boundary + "\r\n"
                + "Content-Type: application/http\r\n"
                + "\r\n"
                + "HTTP/1.1 404 Not Found\r\n"
                + "\r\n"
                + "--" + boundary + "--\r\n";

        List<BatchResult<?>> results = MultipartHelper.decodeResponse(boundary, body.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, results.size());
        assertEquals(404, results.get(0).statusCode());
    }
}
