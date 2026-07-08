package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class UnauthorizedException extends ODataException {

    private final ODataError error;

    public UnauthorizedException(String message) {
        super(401, message);
        this.error = null;
    }

    public UnauthorizedException(HttpResponse response) {
        super(401, "Unauthorized: " + response.getText());
        this.error = ODataError.fromResponse(response);
    }

    public ODataError getError() {
        return error;
    }
}
