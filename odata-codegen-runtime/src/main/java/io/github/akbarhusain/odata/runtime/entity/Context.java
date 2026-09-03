package io.github.akbarhusain.odata.runtime.entity;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.batch.BatchRequest;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record Context(
    String baseUrl,
    Serializer serializer,
    HttpTransport transport,
    AuthProvider authProvider,
    List<HttpInterceptor> interceptors,
    Duration connectTimeout,
    Duration readTimeout
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl = "";
        private Serializer serializer = Serializer.createDefault();
        private HttpTransport transport = HttpTransport.createDefault();
        private AuthProvider authProvider = AuthProvider.none();
        private List<HttpInterceptor> interceptors = List.of();
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(60);

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder serializer(Serializer s) { this.serializer = s; return this; }
        public Builder transport(HttpTransport t) { this.transport = t; return this; }
        public Builder authProvider(AuthProvider a) { this.authProvider = a; return this; }
        public Builder interceptors(List<HttpInterceptor> i) { this.interceptors = List.copyOf(i); return this; }
        public Builder connectTimeout(Duration t) { this.connectTimeout = t; return this; }
        public Builder readTimeout(Duration t) { this.readTimeout = t; return this; }

        public Context build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                // failing here beats every request failing later inside URI.create with
                // an opaque message
                throw new IllegalArgumentException(
                        "Context requires a non-blank baseUrl (e.g. https://services.odata.org/V4/TripPinService)");
            }
            Objects.requireNonNull(serializer, "Context serializer must not be null");
            Objects.requireNonNull(transport, "Context transport must not be null");
            Objects.requireNonNull(authProvider, "Context authProvider must not be null");
            Objects.requireNonNull(interceptors, "Context interceptors must not be null (use List.of())");
            requireValidTimeout(connectTimeout, "connectTimeout");
            requireValidTimeout(readTimeout, "readTimeout");
            return new Context(baseUrl, serializer, transport, authProvider, interceptors,
                    connectTimeout, readTimeout);
        }

        private static void requireValidTimeout(Duration timeout, String name) {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "Context " + name + " must be a non-negative Duration");
            }
        }
    }

    public ContextPath basePath() {
        return new ContextPath(baseUrl);
    }

    public BatchRequest batch() {
        return new BatchRequest(this);
    }
}
