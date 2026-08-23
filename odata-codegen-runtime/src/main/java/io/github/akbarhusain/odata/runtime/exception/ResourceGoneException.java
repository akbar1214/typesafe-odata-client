package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

/** HTTP 410 Gone — the resource existed but is permanently unavailable. */
public class ResourceGoneException extends ODataException {

    public ResourceGoneException(String message) {
        super(410, message);
    }

    public ResourceGoneException(String message, ODataError error) {
        super(410, "Resource gone: " + message, error);
    }

    public ResourceGoneException(HttpResponse response) {
        this(response.getText(), ODataError.fromResponse(response));
    }
}
