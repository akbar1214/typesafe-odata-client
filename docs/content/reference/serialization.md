# Serialization

The runtime uses a small `Serializer` interface. `JacksonSerializer` is the default and is the implementation used by the generated model annotations.

## `Serializer`

```java
public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);

    default <T> byte[] serialize(
        T value, Class<T> type, Set<String> includeFields) {
        return serialize(value, type);
    }

    <T> T deserialize(byte[] data, Class<T> type);
    <T> T deserialize(byte[] data, Type type);
}
```

The three-argument serialization method is used for tracked PATCH fields. Its default implementation sends the full value, so custom serializers can opt into partial PATCH behavior later.

## `JacksonSerializer`

```java
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer())
    .build();
```

The runtime creates its wire mappers through `JacksonSerializer.newODataMapper()`. That configuration:

- serializes `Edm.Date`, `Edm.DateTimeOffset`, `Edm.TimeOfDay`, and `Edm.Duration` as ISO 8601 strings;
- preserves the offset supplied by a service on reads;
- tolerates unknown JSON properties;
- serializes generated enum wire names through their `@JsonValue` method; and
- omits lifecycle metadata and empty collections from ordinary write bodies.

`serializeIncludeNulls()`, `toJson(Object)`, and `serializeToString(Object)` are convenience methods on `JacksonSerializer`; the ordinary runtime write path uses `serialize(...)`.

## Generated Model Annotations

Generated model classes use `@JsonProperty` on public setters for declared properties, navigations, and `@odata.etag`. Derived types emit a getter-only `@JsonProperty("@odata.type")`. Open types use Jackson any-getter/any-setter annotations for dynamic properties.

The serializer is therefore pluggable at the interface level, but a replacement must honor the generated model annotations and OData JSON conventions if it relies on Jackson-style mapping. A Gson or JSON-B implementation is not bundled.

## Custom Serializer

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.lang.reflect.Type;

public final class CustomSerializer implements Serializer {
    private final ObjectMapper mapper = JacksonSerializer.newODataMapper();

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

    @Override
    public <T> T deserialize(byte[] data, Type type) {
        try {
            return mapper.readValue(data, mapper.getTypeFactory().constructType(type));
        } catch (Exception e) {
            throw new ODataException("Deserialization failed", e);
        }
    }
}
```

Register it with `Context.builder().serializer(new CustomSerializer())`. Custom serializers must also deserialize the entity element bodies used by collection reads and must preserve OData temporal and enum wire formats.

## OData Response Shape

Collection responses are JSON objects with a `value` array and optional annotations such as `@odata.count` and `@odata.nextLink`. The runtime unwraps that envelope. The default `JacksonSerializer` path uses the shared mapper's typed conversion fast path; a custom serializer receives each element's bytes through `deserialize(...)`.

## What's Next

- [Error Handling](error-handling.md)
- [OData URL Patterns](odata-urls.md)
