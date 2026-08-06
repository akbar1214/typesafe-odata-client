package io.github.akbarhusain.odata.runtime.http;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record HttpResponse(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body
) {
    public HttpResponse {
        // JDK's HttpHeaders.map() lower-cases header keys. Normalize to a
        // case-insensitive map so lookups like headers().get("Retry-After")
        // work regardless of the case of the key that was stored.
        Map<String, List<String>> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            caseInsensitive.putAll(headers);
        }
        headers = caseInsensitive;
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String getText() {
        return body != null ? new String(body, java.nio.charset.StandardCharsets.UTF_8) : "";
    }
}
