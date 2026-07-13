package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class PreconditionFailedException extends ODataException {

    public PreconditionFailedException(String message) {
        super(412, message);
    }

    public PreconditionFailedException(String message, ODataError error) {
        super(412, "Precondition failed: " + message, error);
    }

    public PreconditionFailedException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
