package io.github.akbarhusain.odata.runtime.query;

public final class EnumProperty<E, V extends Enum<V>> implements PropertyExpression<E, V> {
    private final String edmName;
    private final Class<E> entityType;
    private final Class<V> enumType;
    private final String typeName;

    public EnumProperty(String edmName, Class<E> entityType, Class<V> enumType) {
        this(edmName, entityType, enumType, null);
    }

    public EnumProperty(String edmName, Class<E> entityType, Class<V> enumType, String typeName) {
        this.edmName = edmName;
        this.entityType = entityType;
        this.enumType = enumType;
        this.typeName = typeName;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public Class<V> getEnumType() { return enumType; }
    public String getTypeName() { return typeName; }

    @Override
    public String toODataExpression() { return edmName; }

    @Override
    public String getODataPath() { return edmName; }

    @Override
    public OrderExpression<E, V> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<E, V> desc() { return cast(new OrderedProperty(edmName, false)); }

    @Override
    public OrderExpression<E, V> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<E, V> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    public FilterExpression<E> equalTo(V value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq " + getODataEnumName(value));
    }

    public FilterExpression<E> notEqualTo(V value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne " + getODataEnumName(value));
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    /** Flags membership operator: {@code Gender has NS.PersonGender'Male'}. */
    public FilterExpression<E> has(V value) {
        if (value == null) {
            throw new IllegalArgumentException("has(...) requires a non-null enum value");
        }
        return new RawFilterExpression(edmName + " has " + getODataEnumName(value));
    }

    private String getODataEnumName(V value) {
        if (typeName == null) {
            // The simple-name fallback produced `Color'Red'` — rejected by strict services,
            // which require the fully qualified `NS.Color'Red'`. There is no reliable way to
            // derive the CSDL namespace from a Java class, so fail fast instead.
            throw new IllegalStateException(
                    "EnumProperty '" + edmName + "' has no fully qualified type name; construct it with "
                            + "EnumProperty(edmName, entityType, enumType, \"Namespace.EnumName\"). "
                            + "Generated property constants always pass the qualified name.");
        }
        return typeName + "'" + value.name() + "'";
    }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, V> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, V>) expr;
    }
}
