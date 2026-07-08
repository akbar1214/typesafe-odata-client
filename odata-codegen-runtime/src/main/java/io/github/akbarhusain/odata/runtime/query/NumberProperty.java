package io.github.akbarhusain.odata.runtime.query;

public final class NumberProperty<E, N extends Number> extends NumberExpression<N, E> {
    private final String edmName;

    public NumberProperty(String edmName, Class<E> entityType) {
        super(edmName, entityType);
        this.edmName = edmName;
    }

    public String getEdmName() { return edmName; }
}
