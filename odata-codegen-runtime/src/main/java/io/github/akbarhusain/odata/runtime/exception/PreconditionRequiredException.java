package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

/**
 * HTTP 428 Precondition Required — the service demands a conditional request header
 * (typically {@code If-Match} with an ETag). TripPin returns this for PATCH/DELETE
 * without an etag; catch it to prompt an etag-aware retry.
 */
public class PreconditionRequiredException extends ODataException {

    public PreconditionRequiredException(String message) {
        super(428, message);
    }

    public PreconditionRequiredException(String message, ODataError error) {
        super(428, "Precondition required: " + message, error);
    }

    public PreconditionRequiredException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
