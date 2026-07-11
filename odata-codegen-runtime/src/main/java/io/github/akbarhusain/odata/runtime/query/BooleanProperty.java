package io.github.akbarhusain.odata.runtime.query;

public final class BooleanProperty<E> implements PropertyExpression<Boolean> {
    private final String edmName;
    private final Class<E> entityType;

    public BooleanProperty(String edmName, Class<E> entityType) {
        this.edmName = edmName;
        this.entityType = entityType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }

    @Override
    public String toODataExpression() { return edmName; }

    @Override
    public String getODataPath() { return edmName; }

    @Override
    public OrderExpression<Boolean> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<Boolean> desc() { return cast(new OrderedProperty(edmName, false)); }

    @Override
    public OrderExpression<Boolean> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<Boolean> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<Boolean> cast(OrderExpression<?> expr) {
        return (OrderExpression<Boolean>) expr;
    }

    public FilterExpression<E> equalTo(boolean value) {
        return new RawFilterExpression(edmName + " eq " + value);
    }

    public FilterExpression<E> isTrue() {
        return new RawFilterExpression(edmName + " eq true");
    }

    public FilterExpression<E> isFalse() {
        return new RawFilterExpression(edmName + " eq false");
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }
}
