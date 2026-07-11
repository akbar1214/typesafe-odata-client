package io.github.akbarhusain.odata.runtime.auth;

import java.util.Map;
import java.util.function.Supplier;

public class BearerAuthProvider implements AuthProvider {

    private final Supplier<String> tokenSupplier;

    public BearerAuthProvider(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public Map<String, String> getHeaders() {
        String token = tokenSupplier.get();
        if (token == null) {
            throw new IllegalArgumentException("Bearer token must not be null");
        }
        return Map.of("Authorization", "Bearer " + token);
    }
}
