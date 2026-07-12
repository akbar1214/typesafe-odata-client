package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class ConflictException extends ODataException {

    public ConflictException(String message) {
        super(409, message);
    }

    public ConflictException(String message, ODataError error) {
        super(409, "Conflict: " + message, error);
    }

    public ConflictException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
