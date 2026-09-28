# The Context Pattern

`Context` is the immutable runtime configuration shared by generated containers and request objects. It owns the service root, serializer, transport, authentication provider, interceptors, and request timeouts.

## Context Shape

```java
public record Context(
    String baseUrl,
    Serializer serializer,
    HttpTransport transport,
    AuthProvider authProvider,
    List<HttpInterceptor> interceptors,
    Duration connectTimeout,
    Duration readTimeout
) {
}
```

The record also exposes `Context.builder()`, `basePath()`, and `batch()`.

The canonical constructor rejects null values, a blank or non-HTTP(S) base URL, and non-positive timeouts. The builder defaults are:

| Component | Default |
|-----------|---------|
| `serializer` | `Serializer.createDefault()` (`JacksonSerializer`) |
| `transport` | `HttpTransport.createDefault()` (`JdkHttpTransport`) |
| `authProvider` | `AuthProvider.none()` |
| `interceptors` | `List.of()` |
| `connectTimeout` | 30 seconds |
| `readTimeout` | 60 seconds |

## Building a Context

```java
import io.github.akbarhusain.odata.runtime.auth.BearerAuthProvider;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer())
    .transport(new JdkHttpTransport())
    .authProvider(new BearerAuthProvider(() -> "token"))
    .interceptors(List.of(new DelegatingInterceptor()))
    .connectTimeout(Duration.ofSeconds(15))
    .readTimeout(Duration.ofSeconds(45))
    .build();
```

`BearerAuthProvider` takes a `Supplier<String>` so a token supplier can refresh credentials between requests.

## Request Flow

```text
Context
  -> generated container accessor
  -> generated collection/entity request
  -> EntityOperations
  -> HttpInterceptor chain
  -> HttpTransport
  -> HttpResponse
```

Model classes do not hold a `Context`. Expanded navigation data is materialized into model fields by Jackson, while subsequent navigation requests start from an entity request object.

## ContextPath

`ContextPath` stores path segments and keys independently. Queries are rendered once after the complete path.

```java
ContextPath path = ctx.basePath()
    .addSegment("People")
    .addKey("UserName", "scottketchum", "Edm.String")
    .addSegment("Trips");

String relative = path.toRelativeUrl();
```

The typed `addKey(name, value, edmType)` form formats the key from its CSDL type. The two-argument overload remains available for direct callers, but generated keyed accessors use the typed form.

## Thread Safety

`Context` is a record with immutable list and object references. A context can be shared by generated requests and multiple threads when its configured `AuthProvider`, serializer, and transport are themselves safe for that use.
