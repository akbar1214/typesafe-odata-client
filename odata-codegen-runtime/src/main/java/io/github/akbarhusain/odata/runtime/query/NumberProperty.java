package io.github.akbarhusain.odata.runtime.query;

public final class NumberProperty<E, N extends Number> extends NumberExpression<N, E> implements PropertyExpression<E, N> {

    private final String edmType;

    public NumberProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null);
    }

    public NumberProperty(String edmName, Class<E> entityType, String edmType) {
        super(edmName, entityType);
        this.edmType = edmType;
    }

    @Override
    public String getEdmName() { return toODataExpression(); }

    @Override
    public NumberExpression<N, E> divide(N value) {
        if (value == null) {
            throw new IllegalArgumentException("divide value must not be null");
        }
        // OData 'div' is truncating integer division; Double/Decimal/Single must use 'divby'
        // Heuristic must use property's Edm type, not just value type
        boolean floatingProperty = edmType != null && isFloatingEdmType(edmType);
        boolean floatingValue = value instanceof Double || value instanceof Float
                || value instanceof java.math.BigDecimal;
        String op = (floatingProperty || floatingValue) ? " divby " : " div ";
        // For non-floating property but floating value, divby is correct
        // For floating property even with integer value, divby is correct
        // Otherwise use original heuristic for backward compat when edmType unknown
        if (edmType == null) {
            op = (value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte) ? " div " : " divby ";
        }
        return new NumberExpression<>("(" + toODataExpression() + op + formatValue(value) + ")", getEntityType());
    }

    private static boolean isFloatingEdmType(String edmType) {
        return "Edm.Double".equals(edmType) || "Edm.Single".equals(edmType)
                || "Edm.Decimal".equals(edmType);
    }

    private static String formatValue(Object value) {
        return String.valueOf(value);
    }
}
