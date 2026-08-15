package io.github.akbarhusain.odata.runtime.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ODataError {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String code;
    private final String message;
    private final String target;
    private final Map<String, Object> details;

    public ODataError(String code, String message, Map<String, Object> details) {
        this(code, message, null, details);
    }

    public ODataError(String code, String message, String target, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.target = target;
        this.details = details != null ? details : Collections.emptyMap();
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }

    /** The target the error applies to (e.g. the offending property path), if sent. */
    public String getTarget() { return target; }
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
            // The canonical v4 error payload carries structured diagnostics in
            // error.details[] ({code, message, target}) — map them instead of dropping
            if (error.has("details") && error.get("details").isArray()) {
                java.util.List<Map<String, String>> detailList = new java.util.ArrayList<>();
                for (JsonNode detail : error.get("details")) {
                    Map<String, String> entry = new HashMap<>();
                    if (detail.has("code")) entry.put("code", detail.get("code").asText());
                    if (detail.has("message")) entry.put("message", detail.get("message").asText());
                    if (detail.has("target")) entry.put("target", detail.get("target").asText());
                    detailList.add(entry);
                }
                details.put("details", detailList);
            }

            String target = error.has("target") ? error.get("target").asText() : null;
            return new ODataError(code, message, target, details);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ODataError{code='" + code + "', message='" + message + "'}";
    }
}
