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
    void fromResponseMaps500ToGenericODataException() {
        assertInstanceOf(ODataException.class, ODataException.fromResponse(response(500)));
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
    void fromResponseSurfacesODataErrorInBaseExceptionForUnknownStatus() {
        String json = "{\"error\":{\"code\":\"InternalServerError\",\"message\":\"Something went wrong\"}}";
        HttpResponse response = new HttpResponse(500, Map.of(), json.getBytes());

        ODataException ex = ODataException.fromResponse(response);
        assertEquals(ODataException.class, ex.getClass());
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
}
