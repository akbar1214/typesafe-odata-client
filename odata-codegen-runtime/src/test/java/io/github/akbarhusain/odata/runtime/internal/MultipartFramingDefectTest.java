package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Multipart decode defects in the body-less and mixed-framing shapes.
 *
 * <p>Both trace to the same root cause that lesson 156 warns about: {@code
 * findHeaderSeparator} was extracted as the single definition of "where do the headers
 * end", but two other sites kept re-deriving it slightly differently.
 */
class MultipartFramingDefectTest {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static BatchResult<?> decodeSingle(String boundary, String partBody) {
        // The trailing blank line before the closing delimiter is the MIME encapsulation
        // boundary, so a real service always emits it. Keeping it here is what makes the
        // body-less cases below reproduce: without it the delimiter's own CRLF is all
        // that follows the status line, decodeParts consumes it, and the buggy slice
        // happens to land on an empty range.
        return decodeSingle(boundary, partBody, true);
    }

    private static BatchResult<?> decodeSingle(String boundary, String partBody, boolean encapsulationBlankLine) {
        var decoded = MultipartHelper.decodeResponseDetailed(
                boundary, ascii("--" + boundary + "\r\n" + partBody
                        + (encapsulationBlankLine ? "\r\n" : "")
                        + "--" + boundary + "--\r\n"));
        List<MultipartHelper.DecodedPart> parts = decoded.parts();
        assertEquals(1, parts.size(), "expected exactly one part");
        return parts.get(0).result();
    }

    // ------------------------------------------------------------------
    // A 204 with no header block is the shape EVERY delete returns
    // ------------------------------------------------------------------

    @Test
    void noContentResponseWithNoHeaderBlockDecodesToANullBody() {
        // "HTTP/1.1 204 No Content" with no headers and no blank line of its own. The
        // MIME encapsulation blank line is consumed one layer up by decodeParts, so
        // decodeSinglePart still sees a CRLF-terminated block. Those trailing bytes are
        // framing, not content.
        BatchResult<?> result = decodeSingle("b1",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 204 No Content\r\n");

        assertEquals(204, result.statusCode());
        assertNull(result.body(), "a body-less 204 must not decode to a CRLF \"body\"");
    }

    @Test
    void noContentResponseWithNoTrailingLineAtAllAlsoDecodesToANullBody() {
        // The same part with the encapsulation blank line omitted. The buggy slice is
        // clean here only by accident (nothing is left to slice), which is exactly why
        // the case needed a shape that does leave bytes behind.
        BatchResult<?> result = decodeSingle("b2",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 204 No Content\r\n", false);

        assertEquals(204, result.statusCode());
        assertNull(result.body());
    }

    @Test
    void noContentResponseWithLfOnlyFramingAlsoDecodesToANullBody() {
        BatchResult<?> result = MultipartHelper.decodeResponseDetailed("b3", ascii(
                "--b3\n"
                        + "Content-Type: application/http\nContent-ID: 1\n\n"
                        + "HTTP/1.1 204 No Content\n\n"
                        + "--b3--\n")).parts().get(0).result();

        assertEquals(204, result.statusCode());
        assertNull(result.body(), "the LF-only shape has the same framing-vs-content confusion");
    }

    @Test
    void noContentWithATrailingContentLengthHeaderStillHasNoBody() {
        BatchResult<?> result = decodeSingle("b4",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");

        assertEquals(204, result.statusCode());
        assertNull(result.body());
    }

    @Test
    void aStatusLineOnlyOkResponseHasNoBodyEither() {
        BatchResult<?> result = decodeSingle("b5",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\n");

        assertEquals(200, result.statusCode());
        assertNull(result.body());
    }

    @Test
    void aBodyAfterASeparatorIsStillPreserved() {
        // Guard against over-trimming: the fix must not swallow a real body.
        BatchResult<?> result = decodeSingle("b6",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"a\":1}\r\n");

        assertEquals(200, result.statusCode());
        assertEquals("{\"a\":1}", result.getText().strip(),
                "a real body must survive the framing fix");
    }

    @Test
    void aNullBodyDoesNotThrowWhenDeserialized() {
        // The user-visible failure this fixes: BatchResponse.getEntity on a deleted
        // entity's 204 part raised "No content to map due to end-of-input" instead of
        // returning null, because body was the two framing bytes rather than absent.
        BatchResult<?> result = decodeSingle("b7",
                "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 204 No Content\r\n");
        assertNull(result.body(),
                "so that a typed read of this part returns null rather than throwing");
    }

    // ------------------------------------------------------------------
    // Mixed CRLF/LF framing
    // ------------------------------------------------------------------

    @Test
    void partHeadersWithBareLfAndAnEmbeddedCrlfBlockStillDecode() {
        // A proxy that normalises line endings in the MIME part but not inside the
        // embedded HTTP message produces exactly this. The separator search must take
        // the EARLIER of the two blank lines; preferring CRLFCRLF picked the one inside
        // the embedded block, split after the status line, and parseHeaders then
        // rejected the blank line it found there -- "blank header line" for a part that
        // is perfectly decodable.
        var decoded = MultipartHelper.decodeResponseDetailed("b10", ascii(
                "--b10\n"
                        + "Content-Type: application/http\nContent-ID: 1\n\n"
                        + "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"a\":1}\n"
                        + "--b10--\n"));

        List<MultipartHelper.DecodedPart> parts = decoded.parts();
        assertEquals(1, parts.size(), "the part is decodable; the old split point was wrong");
        assertEquals(200, parts.get(0).result().statusCode());
        assertEquals("{\"a\":1}", parts.get(0).result().getText().strip());
    }

    @Test
    void consistentLfFramingIsUnaffected() {
        var decoded = MultipartHelper.decodeResponseDetailed("b11", ascii(
                "--b11\n"
                        + "Content-Type: application/http\nContent-ID: 1\n\n"
                        + "HTTP/1.1 200 OK\nContent-Type: application/json\n\n{\"a\":1}\n"
                        + "--b11--\n"));
        assertEquals(1, decoded.parts().size());
        assertEquals("{\"a\":1}", decoded.parts().get(0).result().getText().strip());
    }

    @Test
    void consistentCrlfFramingIsUnaffected() {
        var decoded = MultipartHelper.decodeResponseDetailed("b8", ascii(
                "--b8\r\n"
                        + "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"a\":1}\r\n"
                        + "--b8--\r\n"));
        assertEquals(1, decoded.parts().size());
        assertEquals("{\"a\":1}", decoded.parts().get(0).result().getText().strip());
    }

    @Test
    void aPartWithNoHeaderSeparatorStillFailsLoudly() {
        // The blank line IS the framing contract; without one the part really is
        // malformed, and tolerating it would resurrect the silent-partial-batch failure
        // that decisions 98/55 removed.
        assertThrows(ODataException.class, () -> MultipartHelper.decodeResponseDetailed(
                "b9", ascii("--b9\r\nContent-Type: application/http\r\n--b9--\r\n")));
    }

    @Test
    void nestedChangeSetStillDecodes() {
        // The nested path routes through the same split point one level down, so the
        // change must not disturb a nested multipart body.
        var decoded = MultipartHelper.decodeResponseDetailed("b12", ascii(
                "--b12\r\n"
                        + "Content-Type: multipart/mixed; boundary=inner\r\n"
                        + "Content-ID: 1\r\n\r\n"
                        + "--inner\r\n"
                        + "Content-Type: application/http\r\nContent-ID: 1\r\n\r\n"
                        + "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n\r\n{\"id\":1}\r\n"
                        + "--inner--\r\n"
                        + "--b12--\r\n"));
        assertEquals(1, decoded.parts().size());
        assertEquals(201, decoded.parts().get(0).result().statusCode());
        assertEquals("{\"id\":1}", decoded.parts().get(0).result().getText().strip());
    }
}
