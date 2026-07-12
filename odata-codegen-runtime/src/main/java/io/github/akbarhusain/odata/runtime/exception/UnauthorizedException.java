package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class UnauthorizedException extends ODataException {

    public UnauthorizedException(String message) {
        super(401, message);
    }

    public UnauthorizedException(String message, ODataError error) {
        super(401, "Unauthorized: " + message, error);
    }

    public UnauthorizedException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
