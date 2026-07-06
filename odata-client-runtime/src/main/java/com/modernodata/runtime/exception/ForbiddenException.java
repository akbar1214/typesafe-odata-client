package com.modernodata.runtime.exception;

import com.modernodata.runtime.http.HttpResponse;

public class ForbiddenException extends ODataException {

    private final ODataError error;

    public ForbiddenException(String message) {
        super(403, message);
        this.error = null;
    }

    public ForbiddenException(HttpResponse response) {
        super(403, "Forbidden: " + response.getText());
        this.error = ODataError.fromResponse(response);
    }

    public ODataError getError() {
        return error;
    }
}
