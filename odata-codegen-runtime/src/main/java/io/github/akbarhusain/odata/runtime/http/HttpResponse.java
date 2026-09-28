package io.github.akbarhusain.odata.runtime.http;

import java.util.List;
import java.util.Map;

public record HttpResponse(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body
) {
    public HttpResponse {
        headers = HttpHeaders.immutableResponseCopy(headers);
        body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String getText() {
        byte[] snapshot = body();
        return snapshot != null ? new String(snapshot, java.nio.charset.StandardCharsets.UTF_8) : "";
    }
}
