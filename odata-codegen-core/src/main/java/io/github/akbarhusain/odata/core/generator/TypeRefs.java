package io.github.akbarhusain.odata.core.generator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-file resolution of generated-class references. Two schemas may declare types with
 * the same simple name mapped to different output packages; importing both makes every
 * unqualified reference ambiguous. Resolution is deterministic: a simple name claimed by
 * exactly one type is referenced unqualified and imported; a name claimed by more than
 * one type is referenced by fully-qualified name and never imported.
 */
final class TypeRefs {

    private TypeRefs() {
    }

    /**
     * @param typeFqns fully-qualified names of the generated classes this file references
     * @return FQN → Java reference expression (simple name, or the FQN itself when contested)
     */
    static Map<String, String> resolve(Collection<String> typeFqns) {
        Map<String, Set<String>> packagesBySimpleName = new LinkedHashMap<>();
        for (String fqn : typeFqns) {
            packagesBySimpleName.computeIfAbsent(simpleName(fqn), ignored -> new LinkedHashSet<>())
                    .add(fqn);
        }
        Map<String, String> refs = new LinkedHashMap<>();
        for (String fqn : typeFqns) {
            String simple = simpleName(fqn);
            refs.put(fqn, packagesBySimpleName.get(simple).size() > 1 ? fqn : simple);
        }
        return refs;
    }

    static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
