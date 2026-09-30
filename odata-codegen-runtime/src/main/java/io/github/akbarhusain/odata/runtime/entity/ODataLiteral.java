package io.github.akbarhusain.odata.runtime.entity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ODataLiteral {

    private static final Pattern GUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_PATTERN = Pattern.compile("(-?\\d+)-(\\d{2})-(\\d{2})");
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,12}))?)?");
    private static final Pattern DATETIME_PATTERN = Pattern.compile(
            "(-?\\d{4,}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,12})?)?)(Z|[+-]\\d{2}:\\d{2})");
    // durationValue = [ SIGN ] "P" [ 1*DIGIT "D" ] [ "T" [ 1*DIGIT "H" ] [ 1*DIGIT "M" ]
    //                                     [ 1*DIGIT [ "." 1*DIGIT ] "S" ] ]
    // The trailing group is optional, but the "S" inside it is NOT: a bare seconds
    // component with no suffix has no valid reading, so "S" is not optional here.
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "([+-]?)P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.(\\d{1,12}))?S)?)?");
    // decimalValue = [ SIGN ] 1*DIGIT [ "." 1*DIGIT ] [ "e" [ SIGN ] 1*DIGIT ]
    // One production covers Edm.Decimal, Edm.Double and Edm.Single (doubleValue and
    // singleValue are both defined as decimalValue), so all three share this pattern.
    // Digits are required before AND after the "." and before the value; the exponent is
    // optional. "e" is an ABNF literal and therefore case-insensitive.
    private static final Pattern DECIMAL_PATTERN = Pattern.compile(
            "[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    // year = [ "-" ] ( "0" 3DIGIT / oneToNine 3*DIGIT ) — exactly four digits, optional
    // leading minus, never a leading plus and never five or more digits.
    private static final Pattern YEAR_PATTERN = Pattern.compile("-?\\d{4}");
    private static final Pattern BINARY_PATTERN = Pattern.compile("[A-Za-z0-9_-]*={0,2}");
    // decimalValue = [ SIGN ] 1*DIGIT [ "." 1*DIGIT ] [ "e" [ SIGN ] 1*DIGIT ] / nanInfinity —
    // WKT ordinates are doubleValue = decimalValue, so digits are required on BOTH sides
    // of the "." and nanInfinity admits only "NaN" / "-INF" / "INF" (no "+INF").
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|-?INF|NaN");

    private ODataLiteral() {}

    public static String format(Object value, String edmType) {
        if (value == null) {
            throw new IllegalArgumentException("literal value must not be null");
        }
        if (edmType == null) {
            return formatUntyped(value);
        }
        if (edmType.isBlank()) {
            throw new IllegalArgumentException("Edm type must not be blank");
        }
        return switch (edmType) {
            case "Edm.String" -> quote(value);
            case "Edm.Guid" -> formatGuid(value);
            case "Edm.Date" -> formatDate(value);
            case "Edm.DateTimeOffset" -> formatDateTimeOffset(value);
            case "Edm.TimeOfDay" -> formatTime(value);
            case "Edm.Duration" -> formatDuration(value);
            case "Edm.Binary" -> formatBinary(value);
            case "Edm.Boolean" -> formatBoolean(value);
            case "Edm.Byte", "Edm.SByte", "Edm.Int16", "Edm.Int32", "Edm.Int64",
                 "Edm.Single", "Edm.Double", "Edm.Decimal" -> formatNumber(value, edmType);
            default -> {
                if (edmType.startsWith("Edm.Geography") || edmType.startsWith("Edm.Geometry")) {
                    yield formatGeo(value, edmType);
                }
                if (value instanceof Enum<?> e) {
                    yield edmType + "'" + ContextPath.enumWireName(e) + "'";
                }
                if (value instanceof String s) {
                    yield quote(s);
                }
                // Every other branch of this renderer either validates its input or quotes
                // and escapes it. Falling through to a raw String.valueOf did neither: an
                // arbitrary object's toString() was emitted bare into a key predicate,
                // filter or function parameter. Reachable via a keyed accessor for a key
                // property whose Edm type the generator could not resolve (javaType
                // "Object", raw CSDL type string). An enum-typed value must still reach
                // the qualified-literal branch above, so only reject the genuinely
                // unrenderable remainder.
                throw new IllegalArgumentException("No OData literal form for "
                        + value.getClass().getSimpleName() + " value with Edm type '" + edmType
                        + "'; a literal requires a known Edm type, a String, or an enum");
            }
        };
    }

    public static String formatTemporal(Object value) {
        if (value instanceof String s) {
            if (s.startsWith("duration'")) return formatDuration(s);
            if (DATETIME_PATTERN.matcher(s).matches()) return requireDateTime(s);
            if (DATE_PATTERN.matcher(s).matches()) return requireDate(s);
            if (TIME_PATTERN.matcher(s).matches()) return requireTime(s);
            throw new IllegalArgumentException("Value is not a valid OData temporal literal: " + s);
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof OffsetDateTime
                || value instanceof Duration) {
            return format(value, inferredTemporalType(value));
        }
        throw incompatible("temporal", value);
    }

    private static String formatUntyped(Object value) {
        if (value instanceof String s) {
            // Untyped values are quoted Edm.String by default (legacy-compatible) so that
            // date/time-shaped strings are not silently treated as temporal literals; a
            // GUID-shaped string stays bare. Use formatTemporal()/a typed call for temporal intent.
            return isGuid(s) ? s : quote(s);
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof OffsetDateTime
                || value instanceof Duration) {
            return format(value, inferredTemporalType(value));
        }
        if (value instanceof UUID) {
            return formatGuid(value);
        }
        if (value instanceof Number) {
            return formatNumber(value, floatingEdmType(value));
        }
        if (value instanceof Enum<?> e) {
            throw new IllegalArgumentException("enum literals require a qualified Edm type");
        }
        return String.valueOf(value);
    }

    private static String inferredTemporalType(Object value) {
        if (value instanceof LocalDate) return "Edm.Date";
        if (value instanceof LocalTime) return "Edm.TimeOfDay";
        if (value instanceof OffsetDateTime) return "Edm.DateTimeOffset";
        return "Edm.Duration";
    }

    private static String quote(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Edm.String requires a String value");
        }
        return "'" + encodePathValue(s) + "'";
    }

    public static String encodePathValue(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '\'') {
                out.append("''");
            } else if (isPathSafe(codePoint)) {
                out.appendCodePoint(codePoint);
            } else {
                byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append('%');
                    out.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16)));
                    out.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
                }
            }
        }
        return out.toString();
    }

    private static boolean isPathSafe(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "-._~:@!$'()*,;=".indexOf(c) >= 0;
    }

    static boolean isGuid(String value) {
        return value != null && GUID_PATTERN.matcher(value).matches();
    }

    public static String formatGuid(Object value) {
        String text;
        if (value instanceof UUID uuid) {
            text = uuid.toString();
        } else if (value instanceof String s) {
            text = s;
        } else {
            throw new IllegalArgumentException("Edm.Guid requires a UUID or String value");
        }
        if (!GUID_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid Edm.Guid literal: " + text);
        }
        return text;
    }

    private static String formatDate(Object value) {
        if (value instanceof LocalDate date) {
            // LocalDate.toString() renders an expanded "+12345-01-01" form for years
            // outside 0000-9999, and the grammar's year is exactly four digits with no
            // leading plus. Render the parts directly and let requireDate() judge, so the
            // typed path and the String path agree on what is renderable.
            return requireDate(String.format(Locale.ROOT, "%s%04d-%02d-%02d",
                    date.getYear() < 0 ? "-" : "",
                    Math.abs((long) date.getYear()),
                    date.getMonthValue(), date.getDayOfMonth()));
        }
        if (value instanceof String text) {
            return requireDate(text);
        }
        throw incompatible("Edm.Date", value);
    }

    private static String formatDateTimeOffset(Object value) {
        if (value instanceof OffsetDateTime dateTime) {
            return formatDateTime(dateTime);
        }
        if (value instanceof String text) {
            return requireDateTime(text);
        }
        throw incompatible("Edm.DateTimeOffset", value);
    }

    private static String formatTime(Object value) {
        if (value instanceof LocalTime time) {
            return formatTimeValue(time);
        }
        if (value instanceof String text) {
            return requireTime(text);
        }
        throw incompatible("Edm.TimeOfDay", value);
    }

    private static String formatDuration(Object value) {
        if (value instanceof Duration duration) {
            return "duration'" + formatDurationValue(duration) + "'";
        }
        if (value instanceof String text) {
            return "duration'" + requireDuration(text) + "'";
        }
        throw incompatible("Edm.Duration", value);
    }

    private static String formatBinary(Object value) {
        if (value instanceof byte[] bytes) {
            return "binary'" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "'";
        }
        if (value instanceof String text) {
            String body = text;
            if (body.startsWith("binary'") && body.endsWith("'")) {
                body = body.substring(7, body.length() - 1);
            }
            if (!BINARY_PATTERN.matcher(body).matches()) {
                throw new IllegalArgumentException("Invalid Edm.Binary base64url literal");
            }
            int padding = 0;
            while (padding < body.length() && body.charAt(body.length() - padding - 1) == '=') {
                padding++;
            }
            int dataLength = body.length() - padding;
            if (dataLength % 4 == 1 || padding > 2
                    || (padding == 1 && dataLength % 4 != 3)
                    || (padding == 2 && dataLength % 4 != 2)) {
                throw new IllegalArgumentException("Invalid Edm.Binary base64url literal");
            }
            try {
                Base64.getUrlDecoder().decode(body);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid Edm.Binary base64url literal", e);
            }
            return "binary'" + body + "'";
        }
        throw incompatible("Edm.Binary", value);
    }

    private static String formatBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof String s && (s.equals("true") || s.equals("false"))) {
            return s;
        }
        throw incompatible("Edm.Boolean", value);
    }

    private static String formatNumber(Object value, String edmType) {
        if (edmType.equals("Edm.Byte") || edmType.equals("Edm.SByte") || edmType.equals("Edm.Int16")
                || edmType.equals("Edm.Int32") || edmType.equals("Edm.Int64")) {
            BigInteger integer = integerValue(value, edmType);
            checkIntegerRange(integer, edmType);
            return integer.toString();
        }
        if (edmType.equals("Edm.Decimal")) {
            return formatDecimal(value);
        }
        if (value instanceof Double d) {
            return floatingValue(d);
        }
        if (value instanceof Float f) {
            if (Float.isNaN(f)) return "NaN";
            if (Float.isInfinite(f)) return f > 0 ? "INF" : "-INF";
            return Float.toString(f);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof String text) {
            if (isFloatingEdmType(edmType) && (text.equals("INF") || text.equals("-INF") || text.equals("NaN"))) {
                return text;
            }
            if (!DECIMAL_PATTERN.matcher(text).matches()) {
                throw new IllegalArgumentException("Invalid " + edmType + " literal: " + text);
            }
            if (!isFloatingEdmType(edmType)) {
                try {
                    BigInteger integer = new BigInteger(text);
                    checkIntegerRange(integer, edmType);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid " + edmType + " literal: " + text, e);
                }
            }
            return text;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw incompatible(edmType, value);
    }

    private static String formatDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Invalid Edm.Decimal literal: " + value);
            }
            return BigDecimal.valueOf(d).toPlainString();
        }
        if (value instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException("Invalid Edm.Decimal literal: " + value);
            }
            return BigDecimal.valueOf(f.doubleValue()).toPlainString();
        }
        if (value instanceof String text) {
            if (!DECIMAL_PATTERN.matcher(text).matches()) {
                throw new IllegalArgumentException("Invalid Edm.Decimal literal: " + text);
            }
            return text;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).toPlainString();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Edm.Decimal literal: " + value, e);
            }
        }
        throw incompatible("Edm.Decimal", value);
    }

    private static BigInteger integerValue(Object value, String edmType) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer) {
            return integer;
        }
        if (value instanceof String text) {
            try {
                return new BigInteger(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + edmType + " literal: " + text, e);
            }
        }
        throw incompatible(edmType, value);
    }

    private static void checkIntegerRange(BigInteger value, String edmType) {
        BigInteger min;
        BigInteger max;
        switch (edmType) {
            case "Edm.Byte" -> { min = BigInteger.ZERO; max = BigInteger.valueOf(255); }
            case "Edm.SByte" -> { min = BigInteger.valueOf(-128); max = BigInteger.valueOf(127); }
            case "Edm.Int16" -> { min = BigInteger.valueOf(-32768); max = BigInteger.valueOf(32767); }
            case "Edm.Int64" -> { min = BigInteger.valueOf(Long.MIN_VALUE); max = BigInteger.valueOf(Long.MAX_VALUE); }
            default -> { min = BigInteger.valueOf(Integer.MIN_VALUE); max = BigInteger.valueOf(Integer.MAX_VALUE); }
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(edmType + " literal is out of range: " + value);
        }
    }

    private static String floatingValue(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "INF" : "-INF";
        return Double.toString(value);
    }

    private static String floatingEdmType(Object value) {
        if (value instanceof Float) return "Edm.Single";
        if (value instanceof BigDecimal) return "Edm.Decimal";
        return "Edm.Double";
    }

    private static boolean isFloatingEdmType(String type) {
        return type.equals("Edm.Single") || type.equals("Edm.Double") || type.equals("Edm.Decimal");
    }

    private static String formatDateTime(OffsetDateTime value) {
        // The grammar's offset is hour:minute only — dateTimeOffsetValue = year "-" month "-"
        // day "T" timeOfDayValue ( "Z" / SIGN hour ":" minute ). An OffsetDateTime may carry a
        // seconds-precision offset (legal in Java, e.g. historical LMT zones); truncating it
        // would silently shift the instant, so normalize such values to UTC ("Z"), which is
        // expressible and preserves the instant.
        if (value.getOffset().getTotalSeconds() % 60 != 0) {
            value = value.withOffsetSameInstant(ZoneOffset.UTC);
        }
        // year is exactly four digits in the grammar, so an expanded year is rejected here
        // rather than emitted as a %04d-widened (5+ digit) literal.
        if (value.getYear() < -9999 || value.getYear() > 9999) {
            throw new IllegalArgumentException("Edm.DateTimeOffset year must be exactly four digits: "
                    + value.getYear());
        }
        String year = value.getYear() < 0
                ? "-" + String.format(Locale.ROOT, "%04d", -value.getYear())
                : String.format(Locale.ROOT, "%04d", value.getYear());
        String base = year + String.format(Locale.ROOT, "-%02d-%02dT%02d:%02d:%02d",
                value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond());
        if (value.getNano() > 0) {
            base += "." + trimFraction(String.format(Locale.ROOT, "%09d", value.getNano()));
        }
        if (value.getOffset().getTotalSeconds() == 0) {
            return base + "Z";
        }
        int total = value.getOffset().getTotalSeconds();
        char sign = total < 0 ? '-' : '+';
        total = Math.abs(total);
        return base + String.format(Locale.ROOT, "%c%02d:%02d", sign, total / 3600, (total % 3600) / 60);
    }

    private static String formatTimeValue(LocalTime value) {
        String base = String.format(Locale.ROOT, "%02d:%02d:%02d",
                value.getHour(), value.getMinute(), value.getSecond());
        return value.getNano() == 0 ? base : base + "." + String.format(Locale.ROOT, "%09d", value.getNano());
    }

    private static String formatDurationValue(Duration value) {
        boolean negative = value.isNegative();
        Duration absolute = negative ? value.negated() : value;
        long days = absolute.toDays();
        Duration remainder = absolute.minusDays(days);
        long hours = remainder.toHoursPart();
        long minutes = remainder.toMinutesPart();
        long seconds = remainder.getSeconds() % 60;
        int nanos = remainder.getNano();
        StringBuilder out = new StringBuilder(negative ? "-P" : "P");
        if (days > 0) out.append(days).append('D');
        if (hours != 0 || minutes != 0 || seconds != 0 || nanos != 0) {
            out.append('T');
            if (hours != 0) out.append(hours).append('H');
            if (minutes != 0) out.append(minutes).append('M');
            if (seconds != 0 || nanos != 0) {
                out.append(seconds);
                if (nanos != 0) out.append('.').append(trimFraction(String.format(Locale.ROOT, "%09d", nanos)));
                out.append('S');
            }
        }
        if (out.length() == (negative ? 2 : 1)) {
            out.append("T0S");
        }
        return out.toString();
    }

    private static String trimFraction(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '0') end--;
        return value.substring(0, end);
    }

    private static String requireDate(String value) {
        Matcher matcher = DATE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            invalidTemporal(value, "Edm.Date");
        }
        // year = [ "-" ] ( "0" 3DIGIT / oneToNine 3*DIGIT ) — exactly four digits. The
        // regex is deliberately permissive about the width so that a too-long year is
        // rejected by this explicit check rather than slipping through a wider match.
        if (!YEAR_PATTERN.matcher(matcher.group(1)).matches()) {
            invalidTemporal(value, "Edm.Date");
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            invalidTemporal(value, "Edm.Date");
        }
        return value;
    }

    private static String requireTime(String value) {
        Matcher matcher = TIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            invalidTemporal(value, "Edm.TimeOfDay");
        }
        checkTimeParts(value, matcher);
        return value;
    }

    private static String requireDateTime(String value) {
        Matcher matcher = DATETIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            invalidTemporal(value, "Edm.DateTimeOffset");
        }
        requireDate(matcher.group(1));
        requireTime(matcher.group(2));
        String zone = matcher.group(3);
        if (!zone.equals("Z")) {
            int hour = Integer.parseInt(zone.substring(1, 3));
            int minute = Integer.parseInt(zone.substring(4, 6));
            if (hour > 23 || minute > 59) {
                invalidTemporal(value, "Edm.DateTimeOffset");
            }
        }
        // OffsetDateTime.parse() cannot represent a leap second (":60"), which the grammar
        // permits, so it must not be the final word on a time that the checks above
        // already accepted. Substitute a representable second for the parse only.
        boolean leapSecond = matcher.group(2).matches("\\d{2}:\\d{2}:60(?:\\.\\d{1,12})?");
        if (leapSecond) {
            // Second 60 is only legal at 23:59 UTC; any other minute is a typo, not a leap
            // second, and no other zone may be attached to one.
            if (!"23:59".equals(matcher.group(2).substring(0, 5)) || !"Z".equals(zone)) {
                invalidTemporal(value, "Edm.DateTimeOffset");
            }
            return value;
        }
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            invalidTemporal(value, "Edm.DateTimeOffset");
        }
        return value;
    }

    private static void checkTimeParts(String value, Matcher matcher) {
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        // second = zeroToFiftyNine / "60" — 60 is a legal leap second.
        if (hour > 23 || minute > 59 || second > 60) {
            invalidTemporal(value, "Edm.TimeOfDay");
        }
    }

    private static String requireDuration(String value) {
        String body = value;
        if (body.startsWith("duration'") && body.endsWith("'")) {
            body = body.substring(9, body.length() - 1);
        }
        Matcher matcher = DURATION_PATTERN.matcher(body);
        if (!matcher.matches() || body.equals("P") || body.equals("-P") || body.equals("+P")
                || body.contains("'") || body.indexOf('T') >= 0 && matcher.group(6) == null
                && matcher.group(3) == null && matcher.group(4) == null && matcher.group(5) == null) {
            throw new IllegalArgumentException("Invalid Edm.Duration literal: " + value);
        }
        if (body.contains("T") && body.endsWith("T")) {
            throw new IllegalArgumentException("Invalid Edm.Duration literal: " + value);
        }
        return body;
    }

    private static String formatGeo(Object value, String edmType) {
        if (!(value instanceof String text)) {
            throw incompatible(edmType, value);
        }
        String prefix = edmType.startsWith("Edm.Geography") ? "geography" : "geometry";
        String body = text;
        if (body.startsWith(prefix + "'") && body.endsWith("'")) {
            body = body.substring(prefix.length() + 1, body.length() - 1);
        } else if (body.startsWith("geography'") || body.startsWith("geometry'")) {
            throw new IllegalArgumentException("Geo literal prefix does not match " + edmType);
        }
        validateGeo(body, edmType);
        return prefix + "'" + body + "'";
    }

    private static void validateGeo(String body, String edmType) {
        if (body.isBlank() || body.indexOf('\'') >= 0 || body.indexOf('?') >= 0
                || body.indexOf('#') >= 0 || body.indexOf('&') >= 0) {
            throw new IllegalArgumentException("Invalid geographic literal: " + body);
        }
        if (edmType.startsWith("Edm.Geography") && !body.startsWith("SRID=")) {
            throw new IllegalArgumentException("Geographic literals must include an SRID: " + body);
        }
        String shape = body;
        if (shape.startsWith("SRID=")) {
            int semicolon = shape.indexOf(';');
            if (semicolon < 5 || semicolon > 10) {
                throw new IllegalArgumentException("Invalid geographic SRID: " + body);
            }
            String srid = shape.substring(5, semicolon);
            if (!srid.matches("\\d{1,5}")) {
                throw new IllegalArgumentException("Invalid geographic SRID: " + body);
            }
            shape = shape.substring(semicolon + 1);
        }
        int open = shape.indexOf('(');
        int close = shape.lastIndexOf(')');
        if (open <= 0 || close != shape.length() - 1 || close <= open) {
            throw new IllegalArgumentException("Invalid geographic literal: " + body);
        }
        String name = shape.substring(0, open);
        String required = requiredGeoName(edmType);
        if (required != null && !required.equals(name)) {
            throw new IllegalArgumentException("Expected " + required + " for " + edmType);
        }
        String data = shape.substring(open + 1, close);
        if (!validGeoShape(name, data)) {
            throw new IllegalArgumentException("Invalid geographic literal: " + body);
        }
    }

    private static String requiredGeoName(String edmType) {
        if (edmType.endsWith("MultiLineString")) return "MultiLineString";
        if (edmType.endsWith("MultiPolygon")) return "MultiPolygon";
        if (edmType.endsWith("MultiPoint")) return "MultiPoint";
        if (edmType.endsWith("LineString")) return "LineString";
        if (edmType.endsWith("Polygon")) return "Polygon";
        if (edmType.endsWith("Collection")) return "Collection";
        if (edmType.endsWith("Point")) return "Point";
        return null;
    }

    private static boolean validGeoShape(String name, String data) {
        return switch (name) {
            case "Point" -> validPosition(data);
            case "LineString" -> validPositionList(data, false);
            case "MultiPoint" -> validWrappedPositionList(data);
            case "MultiLineString" -> validLineList(data);
            case "Polygon" -> validRingList(data);
            case "MultiPolygon" -> validWrappedRingList(data);
            case "Collection" -> validCollectionList(data);
            default -> false;
        };
    }

    private static boolean validPosition(String data) {
        String[] values = data.strip().split("\\s+");
        // WKT positions are x y, optionally with Z and/or M (2..4 ordinates).
        if (values.length < 2 || values.length > 4) return false;
        for (String value : values) {
            if (!NUMBER_PATTERN.matcher(value).matches()) return false;
        }
        return true;
    }

    /** Position equality by numeric ordinate, so whitespace/representation differences don't matter. */
    private static boolean samePosition(String left, String right) {
        String[] a = left.strip().split("\\s+");
        String[] b = right.strip().split("\\s+");
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!sameOrdinate(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Numeric ordinate equality. {@code nanInfinity} values ("NaN"/"-INF"/"INF") are legal
     * ordinates but have no {@link BigDecimal} form, so they fall back to IEEE comparison
     * ({@code Double.compare} treats NaN as equal to itself, matching position equality).
     */
    private static boolean sameOrdinate(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            return new java.math.BigDecimal(left).compareTo(new java.math.BigDecimal(right)) == 0;
        } catch (NumberFormatException e) {
            return Double.compare(parseOrdinate(left), parseOrdinate(right)) == 0;
        }
    }

    private static double parseOrdinate(String value) {
        return switch (value) {
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> Double.parseDouble(value);
        };
    }

    private static boolean validPositionList(String data, boolean allowEmpty) {
        List<String> parts = splitTopLevel(data);
        if (parts.isEmpty()) return allowEmpty;
        // lineStringData = OPEN positionLiteral 1*( COMMA positionLiteral ) CLOSE — the
        // 1* requires at least one position beyond the first, so an empty list and a
        // single-position list are both invalid. A polygon ring uses a different rule
        // (validWrappedPositionList) and is validated separately.
        if (parts.size() < 2) return false;
        for (String part : parts) {
            if (!validPosition(part)) return false;
        }
        return true;
    }

    private static boolean validWrappedPositionList(String data) {
        List<String> parts = splitTopLevel(data);
        if (parts.isEmpty()) return true;
        for (String part : parts) {
            if (!isWrapped(part) || !validPosition(part.substring(1, part.length() - 1))) return false;
        }
        return true;
    }

    private static boolean validLineList(String data) {
        List<String> parts = splitTopLevel(data);
        if (parts.isEmpty()) return true;
        for (String part : parts) {
            if (!isWrapped(part)
                    || !validPositionList(part.substring(1, part.length() - 1), false)) return false;
        }
        return true;
    }

    private static boolean validRingList(String data) {
        // data is a (possibly multi-ring) polygon coordinate list; each ring must be a
        // wrapped, closed position list of at least four positions (WKT linear ring).
        List<String> rings = splitTopLevel(data);
        if (rings.isEmpty()) return false;
        for (String ring : rings) {
            if (!isWrapped(ring)) return false;
            List<String> positions = splitTopLevel(ring.substring(1, ring.length() - 1));
            if (positions.size() < 4) return false;
            for (String position : positions) {
                if (!validPosition(position)) return false;
            }
            if (!samePosition(positions.get(0), positions.get(positions.size() - 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validWrappedRingList(String data) {
        List<String> parts = splitTopLevel(data);
        if (parts.isEmpty()) return true;
        for (String part : parts) {
            if (!isWrapped(part) || !validRingList(part.substring(1, part.length() - 1))) return false;
        }
        return true;
    }

    private static boolean validCollectionList(String data) {
        List<String> parts = splitTopLevel(data);
        if (parts.isEmpty()) return false;
        for (String part : parts) {
            int open = part.indexOf('(');
            int close = part.lastIndexOf(')');
            if (open <= 0 || close != part.length() - 1 || !balanced(part)) return false;
            String name = part.substring(0, open);
            String nested = part.substring(open + 1, close);
            if (!validGeoShape(name, nested)) return false;
        }
        return true;
    }

    private static List<String> splitTopLevel(String data) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                result.add(data.substring(start, i).strip());
                start = i + 1;
            }
        }
        String last = data.substring(start).strip();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    private static boolean isWrapped(String value) {
        return value.length() >= 2 && value.charAt(0) == '(' && value.charAt(value.length() - 1) == ')'
                && balanced(value.substring(1, value.length() - 1));
    }

    private static boolean balanced(String value) {
        int depth = 0;
        for (char c : value.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    private static boolean isAnyTemporal(String value) {
        return value.matches("[-+]?\\d{4,}-\\d{2}-\\d{2}") || TIME_PATTERN.matcher(value).matches()
                || DATETIME_PATTERN.matcher(value).matches() || DURATION_PATTERN.matcher(value).matches()
                || value.startsWith("duration'");
    }

    private static void invalidTemporal(String value, String type) {
        throw new IllegalArgumentException("Invalid " + type + " literal: " + value);
    }

    private static IllegalArgumentException incompatible(String type, Object value) {
        return new IllegalArgumentException("Value of type " + value.getClass().getName()
                + " is incompatible with " + type);
    }
}
