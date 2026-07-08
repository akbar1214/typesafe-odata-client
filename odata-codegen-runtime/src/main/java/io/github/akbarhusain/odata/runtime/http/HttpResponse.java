package io.github.akbarhusain.odata.runtime.http;

import java.util.List;
import java.util.Map;

public record HttpResponse(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body
) {
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String getText() {
        return body != null ? new String(body, java.nio.charset.StandardCharsets.UTF_8) : "";
    }
}
