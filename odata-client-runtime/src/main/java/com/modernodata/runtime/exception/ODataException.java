package com.modernodata.runtime.exception;

import com.modernodata.runtime.http.HttpResponse;

public class ODataException extends RuntimeException {

    private final int statusCode;

    public ODataException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public ODataException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public ODataException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ODataException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public static ODataException fromResponse(HttpResponse response) {
        int code = response.statusCode();
        return switch (code) {
            case 400 -> new BadRequestException(response);
            case 401 -> new UnauthorizedException(response);
            case 403 -> new ForbiddenException(response);
            case 404 -> new NotFoundException(response);
            case 409 -> new ConflictException(response);
            case 429 -> new RateLimitException(response);
            default -> new ODataException(code, "HTTP " + code + ": " + response.getText());
        };
    }
}
