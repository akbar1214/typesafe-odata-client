package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class BadRequestException extends ODataException {

    private final ODataError error;

    public BadRequestException(String message) {
        super(400, message);
        this.error = null;
    }

    public BadRequestException(HttpResponse response) {
        super(400, "Bad request: " + response.getText());
        this.error = ODataError.fromResponse(response);
    }

    public ODataError getError() {
        return error;
    }
}
