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
