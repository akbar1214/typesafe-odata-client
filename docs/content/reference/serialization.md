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

### GsonSerializer

```java
import io.github.akbarhusain.odata.runtime.serialization.GsonSerializer;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new GsonSerializer())
    .build();
```

**Features:**

- Uses Gson
- No annotations required on entities
- Lightweight

### JakartaJsonBSerializer

```java
import io.github.akbarhusain.odata.runtime.serialization.JakartaJsonBSerializer;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JakartaJsonBSerializer())
    .build();
```

**Features:**

- Uses Jakarta JSON Binding
- Standard Java EE API
- Works with any JSON-B implementation

## Custom Implementations

### Implement Serializer

```java
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

public class CustomSerializer implements Serializer {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public <T> byte[] serialize(T value, Class<T> type) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new SerializationException("Serialization failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new SerializationException("Deserialization failed", e);
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

```java
ObjectMapper mapper = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    .registerModule(new JavaTimeModule());

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer(mapper))
    .build();
```

### Gson Configuration

```java
Gson gson = new GsonBuilder()
    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    .create();

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new GsonSerializer(gson))
    .build();
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
