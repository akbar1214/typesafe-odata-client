package io.github.akbarhusain.odata.runtime.query;

public final class NumberProperty<E, N extends Number> extends NumberExpression<N, E> implements PropertyExpression<E, N> {

    public NumberProperty(String edmName, Class<E> entityType) {
        super(edmName, entityType);
    }

    @Override
    public String getEdmName() { return toODataExpression(); }
}
