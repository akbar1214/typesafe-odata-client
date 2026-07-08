package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class ConflictException extends ODataException {

    private final ODataError error;

    public ConflictException(String message) {
        super(409, message);
        this.error = null;
    }

    public ConflictException(HttpResponse response) {
        super(409, "Conflict: " + response.getText());
        this.error = ODataError.fromResponse(response);
    }

    public ODataError getError() {
        return error;
    }
}
