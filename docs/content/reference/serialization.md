# Serialization

Configure JSON serialization for entity conversion.

## Serializer Interface

```java
public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);
    <T> T deserialize(byte[] data, Class<T> type);
}
```

## Built-in Implementations

### JacksonSerializer (Default)

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer())
    .build();
```

**Features:**

- Uses Jackson ObjectMapper
- Java 8+ module support
- Handles records automatically
- No annotations required on entities

**OData JSON format contract** (`JacksonSerializer.newODataMapper()` — the one
configuration every runtime mapper shares, including action bodies and parameter aliases):

- Temporal values are ISO 8601 strings: `Edm.DateTimeOffset` → `"2014-01-01T00:00:00Z"`,
  `Edm.Date` → `"2014-01-01"`, `Edm.TimeOfDay` → `"09:05:07"`, `Edm.Duration` → `"PT1H30M"`
  (never Jackson's numeric timestamps or `[y, m, d]` arrays).
- The offset a service sends in an `Edm.DateTimeOffset` is preserved on read (no
  normalization to UTC).
- Enum members serialize as their CSDL member name (`@JsonValue` on `wireName()`), even
  when the Java constant had to be sanitized.
- Derived entity and complex types carry `"@odata.type": "#Namespace.Type"` so services
  materialize the subtype (JSON Format §4.5.3); root types stay annotation-free.
- Partial PATCH bodies keep `@`-prefixed control information alongside the tracked fields.

A custom `Serializer` must honor the same wire format.

> Only `JacksonSerializer` ships as a built-in implementation. To use Gson or
> Jakarta JSON-B, implement the `Serializer` interface yourself — see
> [Custom Implementations](#custom-implementations) below.

## Custom Implementations

### Implement Serializer

```java
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

public class CustomSerializer implements Serializer {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public <T> byte[] serialize(T value, Class<T> type) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new ODataException("Serialization failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new ODataException("Deserialization failed", e);
        }
    }
}
```

### Use Custom Serializer

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new CustomSerializer())
    .build();
```

## Configuration Options

### Jackson Configuration

`JacksonSerializer` is configured internally (FAIL_ON_UNKNOWN_PROPERTIES disabled,
JavaTime + Jdk8 modules registered). You can supply your own `Serializer`
implementation if you need a different ObjectMapper:

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer())
    .build();
```

### Gson Configuration

Gson is not built in. Implement the `Serializer` interface with a Gson-backed
mapper and register it via `Context.builder().serializer(...)`:

```java
import java.nio.charset.StandardCharsets;

public class GsonSerializer implements Serializer {
    private final Gson gson = new GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create();

    @Override
    public <T> byte[] serialize(T value, Class<T> type) {
        return gson.toJson(value, type).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        return gson.fromJson(new String(data, StandardCharsets.UTF_8), type);
    }
}
```

## OData Response Format

The serializer handles OData response wrapping:

```json
{
    "value": [
        {"UserName": "scott", "FirstName": "Scott"},
        {"UserName": "keith", "FirstName": "Keith"}
    ],
    "@odata.count": 8,
    "@odata.nextLink": "..."
}
```

The `EntityOperations` extracts the `value` array and passes it to the serializer.

## What's Next

- [Error Handling](error-handling.md) — Typed exceptions
- [OData URL Patterns](odata-urls.md) — URL building rules
