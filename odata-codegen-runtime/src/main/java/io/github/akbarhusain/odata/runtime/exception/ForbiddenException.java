package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class ForbiddenException extends ODataException {

    public ForbiddenException(String message) {
        super(403, message);
    }

    public ForbiddenException(String message, ODataError error) {
        super(403, "Forbidden: " + message, error);
    }

    public ForbiddenException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
