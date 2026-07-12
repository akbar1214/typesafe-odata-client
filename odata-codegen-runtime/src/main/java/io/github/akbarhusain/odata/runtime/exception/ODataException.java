package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class ODataException extends RuntimeException {

    private final int statusCode;
    private final ODataError error;

    public ODataException(String message) {
        this(-1, message, null, null);
    }

    public ODataException(String message, Throwable cause) {
        this(-1, message, cause, null);
    }

    public ODataException(int statusCode, String message) {
        this(statusCode, message, null, null);
    }

    public ODataException(int statusCode, String message, Throwable cause) {
        this(statusCode, message, cause, null);
    }

    public ODataException(int statusCode, String message, ODataError error) {
        this(statusCode, message, null, error);
    }

    public ODataException(int statusCode, String message, Throwable cause, ODataError error) {
        super(message, cause);
        this.statusCode = statusCode;
        this.error = error;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the structured OData error from the response body, if one was parsed.
     */
    public ODataError getError() {
        return error;
    }

    public static ODataException fromResponse(HttpResponse response) {
        int code = response.statusCode();
        ODataError error = ODataError.fromResponse(response);
        String text = response.getText();
        return switch (code) {
            case 400 -> new BadRequestException(text, error);
            case 401 -> new UnauthorizedException(text, error);
            case 403 -> new ForbiddenException(text, error);
            case 404 -> new NotFoundException(text, error);
            case 409 -> new ConflictException(text, error);
            case 429 -> new RateLimitException(response);
            default -> new ODataException(code, "HTTP " + code + ": " + text, error);
        };
    }
}
