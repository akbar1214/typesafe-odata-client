package io.github.akbarhusain.odata.runtime.http;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record HttpRequest(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body,
    Duration connectTimeout,
    Duration readTimeout
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private HttpMethod method = HttpMethod.GET;
        private String url = "";
        private final java.util.HashMap<String, List<String>> headers = new java.util.HashMap<>();
        private byte[] body;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(60);

        public Builder method(HttpMethod method) { this.method = method; return this; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder header(String name, String value) {
            headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
            return this;
        }
        public Builder headers(Map<String, List<String>> headers) {
            for (var entry : headers.entrySet()) {
                this.headers.computeIfAbsent(entry.getKey(), k -> new java.util.ArrayList<>())
                        .addAll(entry.getValue());
            }
            return this;
        }
        public Builder body(byte[] body) { this.body = body; return this; }
        public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
        public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }

        public HttpRequest build() {
            return new HttpRequest(method, url, Map.copyOf(headers),
                    body != null ? body.clone() : null, connectTimeout, readTimeout);
        }
    }
}
