package io.github.akbarhusain.odata.runtime.entity;

import java.util.HashSet;
import java.util.Set;

public final class EntityUtil {

    private EntityUtil() {}

    public static Set<String> mergeChanged(Set<String> existing, String field) {
        Set<String> result = new HashSet<>(existing);
        result.add(field);
        return result;
    }
}
