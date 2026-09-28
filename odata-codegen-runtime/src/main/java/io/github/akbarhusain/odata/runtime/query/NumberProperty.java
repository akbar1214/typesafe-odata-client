package io.github.akbarhusain.odata.runtime.query;

public final class NumberProperty<E, N extends Number> extends NumberExpression<N, E> implements PropertyExpression<E, N> {

    public NumberProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null);
    }

    public NumberProperty(String edmName, Class<E> entityType, String edmType) {
        super(edmName, entityType, edmType);
    }

    @Override
    public String getEdmName() { return toODataExpression(); }

    @Override
    public NumberExpression<N, E> divide(N value) {
        if (value == null) {
            throw new IllegalArgumentException("divide value must not be null");
        }
        boolean floatingProperty = "Edm.Double".equals(getEdmType()) || "Edm.Single".equals(getEdmType())
                || "Edm.Decimal".equals(getEdmType());
        boolean floatingValue = value instanceof Double || value instanceof Float
                || value instanceof java.math.BigDecimal;
        String op = (floatingProperty || floatingValue) ? " divby " : " div ";
        return new NumberExpression<>("(" + toODataExpression() + op + formatValue(value) + ")",
                getEntityType(), getEdmType());
    }
}
