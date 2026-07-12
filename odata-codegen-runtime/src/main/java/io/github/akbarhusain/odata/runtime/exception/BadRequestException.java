package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class BadRequestException extends ODataException {

    public BadRequestException(String message) {
        super(400, message);
    }

    public BadRequestException(String message, ODataError error) {
        super(400, "Bad request: " + message, error);
    }

    public BadRequestException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
