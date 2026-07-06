package com.modernodata.runtime.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernodata.runtime.http.HttpResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ODataError {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String code;
    private final String message;
    private final Map<String, Object> details;

    public ODataError(String code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = details != null ? details : Collections.emptyMap();
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public Map<String, Object> getDetails() { return details; }

    public static ODataError fromResponse(HttpResponse response) {
        try {
            byte[] body = response.body();
            if (body == null || body.length == 0) return null;

            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.get("error");
            if (error == null) return null;

            String code = error.has("code") ? error.get("code").asText() : null;
            String message = error.has("message") ? error.get("message").asText() : null;

            Map<String, Object> details = new HashMap<>();
            if (error.has("innererror")) {
                JsonNode inner = error.get("innererror");
                inner.fields().forEachRemaining(entry ->
                        details.put(entry.getKey(), entry.getValue().asText()));
            }

            return new ODataError(code, message, details);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ODataError{code='" + code + "', message='" + message + "'}";
    }
}
