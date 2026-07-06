package com.modernodata.runtime.exception;

public class ODataException extends RuntimeException {

    private final int statusCode;

    public ODataException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public ODataException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public ODataException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ODataException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
