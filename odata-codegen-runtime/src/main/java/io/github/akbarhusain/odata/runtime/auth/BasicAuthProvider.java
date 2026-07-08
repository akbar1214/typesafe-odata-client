package io.github.akbarhusain.odata.runtime.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class BasicAuthProvider implements AuthProvider {

    private final String encodedCredentials;

    public BasicAuthProvider(String username, String password) {
        String credentials = username + ":" + password;
        this.encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, String> getHeaders() {
        return Map.of("Authorization", "Basic " + encodedCredentials);
    }
}
