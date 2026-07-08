package com.modernodata.runtime.exception;

import com.modernodata.runtime.http.HttpResponse;

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
}
