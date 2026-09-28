package io.github.akbarhusain.odata.runtime.auth;

import io.github.akbarhusain.odata.runtime.http.HttpHeaders;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class BearerAuthProvider implements AuthProvider {

    private final Supplier<String> tokenSupplier;

    public BearerAuthProvider(Supplier<String> tokenSupplier) {
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
    }

    @Override
    public Map<String, String> getHeaders() {
        String token = tokenSupplier.get();
        if (token == null) {
            throw new IllegalArgumentException("Bearer token must not be null");
        }
        String value = "Bearer " + token;
        HttpHeaders.requireName("Authorization");
        HttpHeaders.requireValue("Authorization", value);
        return Map.of("Authorization", value);
    }
}
