package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

/**
 * Exception for server-side errors (HTTP 5xx).
 */
public class ServerException extends ODataException {

    public ServerException(String message) {
        super(500, message);
    }

    public ServerException(int statusCode, String message) {
        super(statusCode, message);
    }

    public ServerException(int statusCode, String message, ODataError error) {
        super(statusCode, "Server error: " + message, error);
    }

    public ServerException(HttpResponse response) {
        this(response.statusCode(), response.getText(), ODataError.fromResponse(response));
    }
}
