package io.github.akbarhusain.odata.runtime.query;

import java.util.regex.Pattern;

/**
 * Filterable/orderable property for {@code Edm.Guid} values. Guid literals are the bare
 * 8-4-4-4-12 value — {@code Id eq 0c5a...} — never quoted strings (an OData type error)
 * and never the {@code guid'...'} literal (rejected by modern services). Values are
 * accepted as Strings, matching the generated Java type for Edm.Guid, and validated
 * against the GUID shape before being embedded in an expression.
 */
public final class GuidProperty<E> implements PropertyExpression<E, String> {

    private static final Pattern GUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final String edmName;
    private final Class<E> entityType;

    public GuidProperty(String edmName, Class<E> entityType) {
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

    @Override
    public OrderExpression<E, String> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<E, String> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, String> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, String>) expr;
    }

    // Equality operators — the only comparisons defined for Edm.Guid
    public FilterExpression<E> equalTo(String guid) {
        if (guid == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq " + requireGuid(guid));
    }

    public FilterExpression<E> notEqualTo(String guid) {
        if (guid == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne " + requireGuid(guid));
    }

    // Null checks
    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    private static String requireGuid(String value) {
        if (!GUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Not a valid Edm.Guid literal (expected 8-4-4-4-12 hex digits): " + value);
        }
        return value;
    }
}
