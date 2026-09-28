package io.github.akbarhusain.odata.runtime.entity;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.batch.BatchRequest;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.net.URI;
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
    public Context {
        baseUrl = requireServiceRoot(baseUrl);
        serializer = Objects.requireNonNull(serializer, "Context serializer must not be null");
        transport = Objects.requireNonNull(transport, "Context transport must not be null");
        authProvider = Objects.requireNonNull(authProvider, "Context authProvider must not be null");
        interceptors = List.copyOf(Objects.requireNonNull(interceptors,
                "Context interceptors must not be null (use List.of())"));
        connectTimeout = requirePositiveTimeout(connectTimeout, "connectTimeout");
        readTimeout = requirePositiveTimeout(readTimeout, "readTimeout");
    }

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

        public Builder baseUrl(String u) {
            this.baseUrl = requireServiceRoot(u);
            return this;
        }
        public Builder serializer(Serializer s) { this.serializer = s; return this; }
        public Builder transport(HttpTransport t) { this.transport = t; return this; }
        public Builder authProvider(AuthProvider a) { this.authProvider = a; return this; }
        public Builder interceptors(List<HttpInterceptor> i) {
            this.interceptors = List.copyOf(Objects.requireNonNull(i,
                    "Context interceptors must not be null (use List.of())"));
            return this;
        }
        public Builder connectTimeout(Duration t) {
            this.connectTimeout = requirePositiveTimeout(t, "connectTimeout");
            return this;
        }
        public Builder readTimeout(Duration t) {
            this.readTimeout = requirePositiveTimeout(t, "readTimeout");
            return this;
        }

        public Context build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException(
                        "Context requires a non-blank baseUrl (e.g. https://services.odata.org/V4/TripPinService)");
            }
            return new Context(baseUrl, serializer, transport, authProvider, interceptors,
                    connectTimeout, readTimeout);
        }
    }

    public ContextPath basePath() {
        return new ContextPath(baseUrl);
    }

    public BatchRequest batch() {
        return new BatchRequest(this);
    }

    private static String requireServiceRoot(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Context requires a non-blank baseUrl (e.g. https://services.odata.org/V4/TripPinService)");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Context baseUrl must be a valid absolute HTTP(S) URL");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Context baseUrl must be an absolute HTTP(S) service root "
                    + "without query, fragment or userinfo");
        }
        return value;
    }

    private static Duration requirePositiveTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Context " + name + " must be a positive Duration");
        }
        return timeout;
    }
}
