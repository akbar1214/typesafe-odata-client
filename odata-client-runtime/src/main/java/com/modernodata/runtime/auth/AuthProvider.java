package com.modernodata.runtime.auth;

import java.util.Map;

public interface AuthProvider {
    Map<String, String> getHeaders();

    static AuthProvider none() {
        return Map::of;
    }
}
