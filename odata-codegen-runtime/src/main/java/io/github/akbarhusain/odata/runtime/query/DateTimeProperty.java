package io.github.akbarhusain.odata.runtime.query;

import io.github.akbarhusain.odata.runtime.entity.ContextPath;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

public final class DateTimeProperty<E> implements PropertyExpression<E, String> {

    private final String edmName;
    private final Class<E> entityType;
    private final String edmType;

    public DateTimeProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null);
    }

    public DateTimeProperty(String edmName, Class<E> entityType, String edmType) {
        this.edmName = requireName(edmName);
        this.entityType = entityType;
        if (edmType != null && edmType.isBlank()) {
            throw new IllegalArgumentException("temporal Edm type must not be blank");
        }
        if (edmType != null
                && !edmType.equals("Edm.Date") && !edmType.equals("Edm.DateTimeOffset")
                && !edmType.equals("Edm.TimeOfDay") && !edmType.equals("Edm.Duration")) {
            throw new IllegalArgumentException("Unsupported temporal Edm type: " + edmType);
        }
        this.edmType = edmType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public String getEdmType() { return edmType; }

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

    public FilterExpression<E> equalTo(Object value) {
        if (value == null) return isNull();
        return new RawFilterExpression(edmName + " eq " + formatLiteral(value));
    }

    public FilterExpression<E> notEqualTo(Object value) {
        if (value == null) return isNotNull();
        return new RawFilterExpression(edmName + " ne " + formatLiteral(value));
    }

    public FilterExpression<E> greaterThan(Object value) {
        if (value == null) throw new IllegalArgumentException("greaterThan value must not be null");
        return new RawFilterExpression(edmName + " gt " + formatLiteral(value));
    }

    public FilterExpression<E> greaterThanOrEqualTo(Object value) {
        if (value == null) throw new IllegalArgumentException("greaterThanOrEqualTo value must not be null");
        return new RawFilterExpression(edmName + " ge " + formatLiteral(value));
    }

    public FilterExpression<E> lessThan(Object value) {
        if (value == null) throw new IllegalArgumentException("lessThan value must not be null");
        return new RawFilterExpression(edmName + " lt " + formatLiteral(value));
    }

    public FilterExpression<E> lessThanOrEqualTo(Object value) {
        if (value == null) throw new IllegalArgumentException("lessThanOrEqualTo value must not be null");
        return new RawFilterExpression(edmName + " le " + formatLiteral(value));
    }

    private String formatLiteral(Object value) {
        return edmType == null ? ContextPath.formatTemporal(value)
                : ContextPath.formatTypedValue(value, edmType);
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    public NumberExpression<Integer, E> year() {
        requireType("year", "Edm.Date", "Edm.DateTimeOffset");
        return new NumberExpression<>("year(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<Integer, E> month() {
        requireType("month", "Edm.Date", "Edm.DateTimeOffset");
        return new NumberExpression<>("month(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<Integer, E> day() {
        requireType("day", "Edm.Date", "Edm.DateTimeOffset");
        return new NumberExpression<>("day(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<Integer, E> hour() {
        requireType("hour", "Edm.DateTimeOffset", "Edm.TimeOfDay");
        return new NumberExpression<>("hour(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<Integer, E> minute() {
        requireType("minute", "Edm.DateTimeOffset", "Edm.TimeOfDay");
        return new NumberExpression<>("minute(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<Integer, E> second() {
        requireType("second", "Edm.DateTimeOffset", "Edm.TimeOfDay");
        return new NumberExpression<>("second(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<BigDecimal, E> fractionalSeconds() {
        requireType("fractionalseconds", "Edm.DateTimeOffset", "Edm.TimeOfDay");
        return new NumberExpression<>("fractionalseconds(" + edmName + ")", entityType, "Edm.Decimal");
    }

    public NumberExpression<Integer, E> totalOffsetMinutes() {
        requireType("totaloffsetminutes", "Edm.DateTimeOffset");
        return new NumberExpression<>("totaloffsetminutes(" + edmName + ")", entityType, "Edm.Int32");
    }

    public NumberExpression<BigDecimal, E> totalSeconds() {
        requireType("totalseconds", "Edm.Duration");
        return new NumberExpression<>("totalseconds(" + edmName + ")", entityType, "Edm.Decimal");
    }

    public DateTimeProperty<E> date() {
        requireType("date", "Edm.DateTimeOffset");
        return new DateTimeProperty<>("date(" + edmName + ")", entityType, "Edm.Date");
    }

    public DateTimeProperty<E> time() {
        requireType("time", "Edm.DateTimeOffset");
        return new DateTimeProperty<>("time(" + edmName + ")", entityType, "Edm.TimeOfDay");
    }

    private void requireType(String function, String... allowedTypes) {
        if (edmType == null) return;
        for (String allowed : allowedTypes) {
            if (allowed.equals(edmType)) return;
        }
        throw new IllegalStateException(function + "() is not valid for " + edmType);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("date/time property name must not be blank");
        }
        return value;
    }
}
