package io.github.akbarhusain.odata.runtime.query;

public final class StringProperty<E> implements PropertyExpression<E, String> {
    private final String edmName;
    private final Class<E> entityType;

    public StringProperty(String edmName, Class<E> entityType) {
        if (edmName == null || edmName.isBlank()) {
            throw new IllegalArgumentException("string property name must not be blank");
        }
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
    public OrderExpression<E, String> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<E, String> desc() { return cast(new OrderedProperty(edmName, false)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, String> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, String>) expr;
    }

    // Equality operators
    public FilterExpression<E> equalTo(String value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq '" + escape(value) + "'");
    }

    public FilterExpression<E> notEqualTo(String value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne '" + escape(value) + "'");
    }

    // String-specific operators
    public FilterExpression<E> contains(String value) {
        if (value == null) {
            throw new IllegalArgumentException("contains value must not be null");
        }
        return new RawFilterExpression("contains(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> startsWith(String value) {
        if (value == null) {
            throw new IllegalArgumentException("startsWith value must not be null");
        }
        return new RawFilterExpression("startswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> endsWith(String value) {
        if (value == null) {
            throw new IllegalArgumentException("endsWith value must not be null");
        }
        return new RawFilterExpression("endswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> matchesPattern(String regex) {
        if (regex == null) {
            throw new IllegalArgumentException("matchesPattern regex must not be null");
        }
        return new RawFilterExpression("matchesPattern(" + edmName + ",'" + escape(regex) + "')");
    }

    // String functions
    public NumberExpression<Integer, E> length() {
        return new NumberExpression<>("length(" + edmName + ")", entityType, "Edm.Int32");
    }

    public StringProperty<E> toLower() {
        return new StringProperty<>("tolower(" + edmName + ")", entityType);
    }

    public StringProperty<E> toUpper() {
        return new StringProperty<>("toupper(" + edmName + ")", entityType);
    }

    public StringProperty<E> trim() {
        return new StringProperty<>("trim(" + edmName + ")", entityType);
    }

    public StringProperty<E> concat(String value) {
        if (value == null) {
            throw new IllegalArgumentException("concat value must not be null");
        }
        return new StringProperty<>("concat(" + edmName + ",'" + escape(value) + "')", entityType);
    }

    public StringProperty<E> concat(StringProperty<E> other) {
        if (other == null) {
            throw new IllegalArgumentException("concat other property must not be null");
        }
        return new StringProperty<>("concat(" + edmName + "," + other.toODataExpression() + ")", entityType);
    }

    public NumberExpression<Integer, E> indexOf(String value) {
        if (value == null) {
            throw new IllegalArgumentException("indexOf value must not be null");
        }
        return new NumberExpression<>("indexof(" + edmName + ",'" + escape(value) + "')", entityType, "Edm.Int32");
    }

    public StringProperty<E> substring(int start) {
        if (start < 0) throw new IllegalArgumentException("substring start must be >= 0");
        return new StringProperty<>("substring(" + edmName + "," + start + ")", entityType);
    }

    public StringProperty<E> substring(int start, int length) {
        if (start < 0) throw new IllegalArgumentException("substring start must be >= 0");
        if (length < 0) throw new IllegalArgumentException("substring length must be >= 0");
        return new StringProperty<>("substring(" + edmName + "," + start + "," + length + ")", entityType);
    }

    // Lexicographic comparison operators (valid OData for strings)
    public FilterExpression<E> greaterThan(String value) {
        if (value == null) {
            throw new IllegalArgumentException("greaterThan value must not be null");
        }
        return new RawFilterExpression(edmName + " gt '" + escape(value) + "'");
    }

    public FilterExpression<E> greaterThanOrEqualTo(String value) {
        if (value == null) {
            throw new IllegalArgumentException("greaterThanOrEqualTo value must not be null");
        }
        return new RawFilterExpression(edmName + " ge '" + escape(value) + "'");
    }

    public FilterExpression<E> lessThan(String value) {
        if (value == null) {
            throw new IllegalArgumentException("lessThan value must not be null");
        }
        return new RawFilterExpression(edmName + " lt '" + escape(value) + "'");
    }

    public FilterExpression<E> lessThanOrEqualTo(String value) {
        if (value == null) {
            throw new IllegalArgumentException("lessThanOrEqualTo value must not be null");
        }
        return new RawFilterExpression(edmName + " le '" + escape(value) + "'");
    }

    // Null checks
    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}

