package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5: statuses services actually return fell into the generic bucket. Most notably
 * 428 Precondition Required — TripPin's signature response when PATCH/DELETE omit
 * If-Match (lesson 20) — plus 408 Request Timeout and 410 Gone.
 */
class TypedStatusExceptionTest {

    private static HttpResponse response(int code, String body) {
        return new HttpResponse(code, Map.of("Content-Type", java.util.List.of("application/json")),
                body.getBytes());
    }

    @Test
    void fromResponseMaps428ToPreconditionRequiredException() {
        ODataException ex = ODataException.fromResponse(response(428,
                "{\"error\":{\"code\":\"PreconditionRequired\"}}"));
        assertInstanceOf(PreconditionRequiredException.class, ex,
                "428 must be typed — it is the etag-missing signal on TripPin");
        assertEquals(428, ex.getStatusCode());
        assertNotNull(ex.getError(), "structured error must be carried");
        assertEquals("PreconditionRequired", ex.getError().getCode());
    }

    @Test
    void fromResponseMaps408ToRequestTimeoutException() {
        ODataException ex = ODataException.fromResponse(response(408, "{}"));
        assertInstanceOf(RequestTimeoutException.class, ex);
        assertEquals(408, ex.getStatusCode());
    }

    @Test
    void fromResponseMaps410ToResourceGoneException() {
        ODataException ex = ODataException.fromResponse(response(410, "{}"));
        assertInstanceOf(ResourceGoneException.class, ex);
        assertEquals(410, ex.getStatusCode());
    }

    @Test
    void typedExceptionsRemainCatchableAsODataException() {
        assertThrows(ODataException.class,
                () -> { throw ODataException.fromResponse(response(428, "{}")); });
    }

    @Test
    void unknownClientStatusesStayGeneric() {
        assertInstanceOf(ODataException.class, ODataException.fromResponse(response(418, "{}")));
        assertInstanceOf(ODataException.class, ODataException.fromResponse(response(451, "{}")));
    }
}
