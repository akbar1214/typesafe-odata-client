package io.github.akbarhusain.odata.runtime.entity;

/**
 * A generated OData enum member that knows its CSDL wire name. Hostile-but-legal CSDL
 * member names are sanitized into Java identifiers ({@code A-B} → {@code A_B}); URL
 * literals (function parameters, key predicates, filters) must carry the original CSDL
 * name, so generated enums implement this interface and the literal formatters consult
 * it, falling back to {@link Enum#name()} for enums compiled without it.
 */
public interface ODataEnumValue {

    /** The CSDL member name as it appears on the wire (e.g. {@code "A-B"}). */
    String wireName();
}
