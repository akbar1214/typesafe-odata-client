package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class NotFoundException extends ODataException {

    public NotFoundException(String message) {
        super(404, message);
    }

    public NotFoundException(String message, ODataError error) {
        super(404, "Resource not found: " + message, error);
    }

    public NotFoundException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
