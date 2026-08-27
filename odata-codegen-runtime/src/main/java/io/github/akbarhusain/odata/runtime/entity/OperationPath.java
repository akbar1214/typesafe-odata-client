package io.github.akbarhusain.odata.runtime.entity;

/**
 * Builds the URL fragment for unbound function-import invocation parameters:
 * {@code Name(param=value,param2=value2)} embedded in a single path segment.
 * Parameter literals are formatted by Edm type using exactly the same rules as
 * key predicates ({@link ContextPath#formatTypedValue}) — OData's function-parameter
 * grammar is the same literal syntax as key-predicate values.
 */
public final class OperationPath {

    private OperationPath() {}

    /**
     * Renders an operation name with its parameter pairs inside parentheses:
     * {@code segment("GetNearestAirport", "lat=1", "lon=2")} →
     * {@code GetNearestAirport(lat=1,lon=2)}. A parameterless operation renders
     * {@code Name()} (empty parens are valid and required by some services).
     */
    public static String segment(String operationName, String... nameEqualsValuePairs) {
        if (nameEqualsValuePairs.length == 0) {
            return operationName + "()";
        }
        StringBuilder sb = new StringBuilder(operationName).append('(');
        for (int i = 0; i < nameEqualsValuePairs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(nameEqualsValuePairs[i]);
        }
        return sb.append(')').toString();
    }

    /**
     * Formats one parameter value as its OData literal per the given Edm type
     * (strings quoted with inner quotes doubled, numbers/Guids/dates bare,
     * enums qualified {@code NS.Enum'Member'}, durations {@code duration'...'}).
     *
     * @throws IllegalArgumentException if value is null — generated constructors skip
     *         nullable parameters explicitly; passing null here is a generation bug
     */
    public static String parameter(Object value, String edmType) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "parameter value must not be null; nullable parameters must be omitted "
                            + "from the invocation, not rendered as 'null'");
        }
        return ContextPath.formatTypedValue(value, edmType);
    }

    /**
     * Formats a collection-valued parameter for its PARAMETER ALIAS
     * ({@code Name(param=@p)?@p=['a','b']}): elements comma-joined inside brackets,
     * each formatted with {@link #parameter} by its ELEMENT Edm type. Collection
     * literals cannot be embedded inline in the invocation path — OData's URL
     * conventions require collection parameters to travel as alias query options.
     *
     * @throws IllegalArgumentException if the list or any element is null — generated
     *         constructors omit nullable parameters; nulls here are a generation bug
     */
    public static String collectionParameter(java.util.List<?> values, String elementEdmType) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "collection parameter value must not be null; nullable parameters must be "
                            + "omitted from the invocation, not rendered as 'null'");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parameter(values.get(i), elementEdmType));
        }
        return sb.append(']').toString();
    }
}
