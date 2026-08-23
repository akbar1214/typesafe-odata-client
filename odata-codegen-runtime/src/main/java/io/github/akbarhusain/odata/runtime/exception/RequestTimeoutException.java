package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

/**
 * HTTP 408 Request Timeout — the server gave up waiting for the request. Unlike a
 * client-side timeout this comes from the service; retrying with an unchanged body
 * is generally safe for idempotent methods.
 */
public class RequestTimeoutException extends ODataException {

    public RequestTimeoutException(String message) {
        super(408, message);
    }

    public RequestTimeoutException(String message, ODataError error) {
        super(408, "Request timeout: " + message, error);
    }

    public RequestTimeoutException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
