package io.github.akbarhusain.odata.runtime.query;

public final class EnumProperty<E, V extends Enum<V>> implements PropertyExpression<V> {
    private final String edmName;
    private final Class<E> entityType;
    private final Class<V> enumType;

    public EnumProperty(String edmName, Class<E> entityType, Class<V> enumType) {
        this.edmName = edmName;
        this.entityType = entityType;
        this.enumType = enumType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public Class<V> getEnumType() { return enumType; }

    @Override
    public String toODataExpression() { return edmName; }

    @Override
    public String getODataPath() { return edmName; }

    @Override
    public OrderExpression<V> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<V> desc() { return cast(new OrderedProperty(edmName, false)); }

    @Override
    public OrderExpression<V> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<V> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    public FilterExpression equalTo(V value) {
        return new RawFilterExpression(edmName + " " + getODataEnumName(value));
    }

    public FilterExpression notEqualTo(V value) {
        return new RawFilterExpression(edmName + " ne " + getODataEnumName(value));
    }

    public FilterExpression isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    private String getODataEnumName(V value) {
        return enumType.getSimpleName() + "'" + value.name() + "'";
    }

    @SuppressWarnings("unchecked")
    private OrderExpression<V> cast(OrderExpression<?> expr) {
        return (OrderExpression<V>) expr;
    }
}
