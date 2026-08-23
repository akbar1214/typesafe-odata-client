package io.github.akbarhusain.odata.runtime.query;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Property for OData date/time types (Edm.DateTimeOffset, Edm.Date, Edm.Duration, Edm.TimeOfDay).
 * Generates unquoted datetime literals in OData filter expressions.
 *
 * OData datetime literals are NOT quoted:
 *   OrderDate ge 1998-01-01T00:00:00Z
 *   not: OrderDate ge '1998-01-01T00:00:00Z'
 *
 * String literals are validated against the OData v4 ABNF grammar and reject anything
 * else (prevents arbitrary text — including injected predicates — from being concatenated
 * into $filter). Typed overloads ({@link LocalDate}, {@link OffsetDateTime}, {@link LocalTime},
 * {@link Duration}) format the literal per the ABNF automatically.
 */
public final class DateTimeProperty<E> implements PropertyExpression<E, String> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME_OF_DAY_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    private static final Pattern DATETIME_OFFSET_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern DURATION_PATTERN = Pattern.compile("duration'[pP][^']*'");

    private final String edmName;
    private final Class<E> entityType;

    public DateTimeProperty(String edmName, Class<E> entityType) {
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

    // OData datetime comparisons - values are NOT quoted, and validated against the ABNF.
    // The value may be a pre-formatted String (validated) or a typed LocalDate /
    // OffsetDateTime / LocalTime / Duration (formatted per the ABNF).
    public FilterExpression<E> equalTo(Object value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq " + formatLiteral(value));
    }

    public FilterExpression<E> notEqualTo(Object value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne " + formatLiteral(value));
    }

    public FilterExpression<E> greaterThan(Object value) {
        return new RawFilterExpression(edmName + " gt " + formatLiteral(value));
    }

    public FilterExpression<E> greaterThanOrEqualTo(Object value) {
        return new RawFilterExpression(edmName + " ge " + formatLiteral(value));
    }

    public FilterExpression<E> lessThan(Object value) {
        return new RawFilterExpression(edmName + " lt " + formatLiteral(value));
    }

    public FilterExpression<E> lessThanOrEqualTo(Object value) {
        return new RawFilterExpression(edmName + " le " + formatLiteral(value));
    }

    private static String formatLiteral(Object value) {
        if (value instanceof String s) {
            return requireLiteral(s);
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toString();
        }
        if (value instanceof LocalTime t) {
            return formatTime(t);
        }
        if (value instanceof Duration d) {
            return "duration'" + d + "'";
        }
        throw new IllegalArgumentException(
                "Unsupported date/time literal type " + value.getClass().getName()
                        + "; use String, LocalDate, OffsetDateTime, LocalTime, or Duration.");
    }

    /** LocalTime#toString omits seconds when zero; OData TimeOfDay requires HH:mm:ss. */
    private static String formatTime(LocalTime value) {
        String formatted = value.format(TIME_FORMATTER);
        if (value.getNano() > 0) {
            // Full 9-digit zero-padded fraction: 1 nano -> .000000001 (not ".1"),
            // 1_000_000 -> .001000000 (not ".1000000")
            formatted += "." + String.format("%09d", value.getNano());
        }
        return formatted;
    }

    private static String requireLiteral(String value) {
        boolean valid = DATE_PATTERN.matcher(value).matches()
                || TIME_OF_DAY_PATTERN.matcher(value).matches()
                || DATETIME_OFFSET_PATTERN.matcher(value).matches()
                || DURATION_PATTERN.matcher(value).matches();
        if (!valid) {
            throw new IllegalArgumentException(
                    "Not a valid OData date/time literal: '" + value + "'. Expected forms: "
                            + "2024-01-01 (Date), 2024-01-01T10:15:30Z (DateTimeOffset), "
                            + "10:15:30 (TimeOfDay), or duration'PT2H' (Duration); "
                            + "or use the typed overloads (LocalDate, OffsetDateTime, LocalTime, Duration).");
        }
        return value;
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    // Date/time extraction functions
    public NumberExpression<Integer, E> year() {
        return new NumberExpression<>("year(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> month() {
        return new NumberExpression<>("month(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> day() {
        return new NumberExpression<>("day(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> hour() {
        return new NumberExpression<>("hour(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> minute() {
        return new NumberExpression<>("minute(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> second() {
        return new NumberExpression<>("second(" + edmName + ")", entityType);
    }

    public DateTimeProperty<E> date() {
        return new DateTimeProperty<>("date(" + edmName + ")", entityType);
    }

    public DateTimeProperty<E> time() {
        return new DateTimeProperty<>("time(" + edmName + ")", entityType);
    }
}
