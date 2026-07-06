package com.modernodata.runtime.exception;

import com.modernodata.runtime.http.HttpResponse;

public class NotFoundException extends ODataException {

    private final ODataError error;

    public NotFoundException(String message, ODataError error) {
        super(404, message);
        this.error = error;
    }

    public NotFoundException(HttpResponse response) {
        super(404, "Resource not found: " + response.getText());
        this.error = ODataError.fromResponse(response);
    }

    public ODataError getError() {
        return error;
    }
}
