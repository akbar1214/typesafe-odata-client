package io.github.akbarhusain.odata.runtime.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthProviderNullTest {

    @Test
    void bearerAuthRejectsNullToken() {
        BearerAuthProvider auth = new BearerAuthProvider(() -> null);
        assertThrows(IllegalArgumentException.class, auth::getHeaders);
    }

    @Test
    void apiKeyAuthRejectsNullKey() {
        ApiKeyAuthProvider auth = new ApiKeyAuthProvider(() -> null);
        assertThrows(IllegalArgumentException.class, auth::getHeaders);
    }

    @Test
    void basicAuthRejectsNullUsername() {
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthProvider(null, "pass"));
    }

    @Test
    void basicAuthRejectsNullPassword() {
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthProvider("user", null));
    }
}
