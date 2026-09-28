package io.github.akbarhusain.odata.runtime.auth;

import io.github.akbarhusain.odata.runtime.http.HttpHeaders;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class ApiKeyAuthProvider implements AuthProvider {

    private final Supplier<String> apiKeySupplier;
    private final String headerName;

    public ApiKeyAuthProvider(Supplier<String> apiKeySupplier, String headerName) {
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier must not be null");
        this.headerName = HttpHeaders.requireName(headerName);
    }

    public ApiKeyAuthProvider(Supplier<String> apiKeySupplier) {
        this(apiKeySupplier, "x-api-key");
    }

    @Override
    public Map<String, String> getHeaders() {
        String key = apiKeySupplier.get();
        if (key == null) {
            throw new IllegalArgumentException("API key must not be null");
        }
        HttpHeaders.requireValue(headerName, key);
        return Map.of(headerName, key);
    }
}
