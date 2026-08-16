package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ODataExceptionTest {

    private static HttpResponse response(int statusCode) {
        return new HttpResponse(statusCode, Map.of(), ("HTTP " + statusCode).getBytes());
    }

    @Test
    void fromResponseMaps400ToBadRequestException() {
        assertInstanceOf(BadRequestException.class, ODataException.fromResponse(response(400)));
    }

    @Test
    void fromResponseMaps401ToUnauthorizedException() {
        assertInstanceOf(UnauthorizedException.class, ODataException.fromResponse(response(401)));
    }

    @Test
    void fromResponseMaps403ToForbiddenException() {
        assertInstanceOf(ForbiddenException.class, ODataException.fromResponse(response(403)));
    }

    @Test
    void fromResponseMaps404ToNotFoundException() {
        assertInstanceOf(NotFoundException.class, ODataException.fromResponse(response(404)));
    }

    @Test
    void fromResponseMaps409ToConflictException() {
        assertInstanceOf(ConflictException.class, ODataException.fromResponse(response(409)));
    }

    @Test
    void fromResponseMaps429ToRateLimitException() {
        assertInstanceOf(RateLimitException.class, ODataException.fromResponse(response(429)));
    }

    @Test
    void fromResponseMaps412ToPreconditionFailedException() {
        assertInstanceOf(PreconditionFailedException.class, ODataException.fromResponse(response(412)));
    }

    @Test
    void fromResponseMaps500ToServerException() {
        assertInstanceOf(ServerException.class, ODataException.fromResponse(response(500)));
    }

    @Test
    void fromResponseMaps503ToServerException() {
        ServerException ex = (ServerException) ODataException.fromResponse(response(503));
        assertEquals(503, ex.getStatusCode());
    }

    @Test
    void fromResponseMaps418ToGenericODataException() {
        assertInstanceOf(ODataException.class, ODataException.fromResponse(response(418)));
    }

    @Test
    void fromResponseSurfacesODataErrorInTypedExceptions() {
        String json = "{\"error\":{\"code\":\"ResourceNotFound\",\"message\":\"Does not exist\"}}";
        HttpResponse response = new HttpResponse(404, Map.of(), json.getBytes());

        ODataException ex = ODataException.fromResponse(response);
        assertInstanceOf(NotFoundException.class, ex);
        assertNotNull(ex.getError(), "Base ODataException should carry the parsed ODataError");
        assertEquals("ResourceNotFound", ex.getError().getCode());
        assertEquals("Does not exist", ex.getError().getMessage());
    }

    @Test
    void fromResponseSurfacesODataErrorInServerException() {
        String json = "{\"error\":{\"code\":\"InternalServerError\",\"message\":\"Something went wrong\"}}";
        HttpResponse response = new HttpResponse(500, Map.of(), json.getBytes());

        ODataException ex = ODataException.fromResponse(response);
        assertInstanceOf(ServerException.class, ex);
        assertNotNull(ex.getError());
        assertEquals("InternalServerError", ex.getError().getCode());
        assertEquals("Something went wrong", ex.getError().getMessage());
    }

    @Test
    void fromResponseHandlesMissingErrorBody() {
        HttpResponse response = new HttpResponse(404, Map.of(), new byte[0]);
        ODataException ex = ODataException.fromResponse(response);
        assertNull(ex.getError());
    }

    @Test
    void typedExceptionGetErrorMatchesBaseGetError() {
        String json = "{\"error\":{\"code\":\"BadRequest\",\"message\":\"Invalid filter\"}}";
        HttpResponse response = new HttpResponse(400, Map.of(), json.getBytes());

        BadRequestException ex = (BadRequestException) ODataException.fromResponse(response);
        assertNotNull(ex.getError());
        assertSame(ex.getError(), ((ODataException) ex).getError());
    }

    @Test
    void l2ErrorTargetAndDetailsArrayAreMapped() {
        String json = "{\"error\":{"
                + "\"code\":\"123\","
                + "\"message\":\"Bad request\","
                + "\"target\":\"Line/Name\","
                + "\"details\":["
                + "{\"code\":\"d1\",\"message\":\"Name is required\",\"target\":\"Name\"},"
                + "{\"code\":\"d2\",\"message\":\"Line is invalid\"}"
                + "]}}";
        HttpResponse response = new HttpResponse(400, java.util.Map.of(),
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ODataError error = ODataError.fromResponse(response);

        assertNotNull(error);
        assertEquals("Line/Name", error.getTarget(), "error.target must not be dropped");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, String>> details =
                (java.util.List<java.util.Map<String, String>>) error.getDetails().get("details");
        assertNotNull(details, "error.details[] must be mapped");
        assertEquals(2, details.size());
        assertEquals("d1", details.get(0).get("code"));
        assertEquals("Name is required", details.get(0).get("message"));
        assertEquals("Name", details.get(0).get("target"));
    }
}
