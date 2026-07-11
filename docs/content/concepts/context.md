# The Context Pattern

`Context` is the central configuration object that holds HTTP transport, serialization, authentication, and interceptors.

## What is Context?

`Context` is a Java record that encapsulates everything needed to execute OData requests:

```java
public record Context(
    String baseUrl,
    Serializer serializer,
    HttpTransport transport,
    AuthProvider authProvider,
    List<HttpInterceptor> interceptors
) { ... }
```

## Building a Context

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build(); // Uses defaults
```

### Defaults

| Component | Default |
|-----------|---------|
| `serializer` | `JacksonSerializer` |
| `transport` | `JdkHttpTransport` |
| `authProvider` | `AuthProvider.none()` (no auth) |
| `interceptors` | Empty list |

### Custom Configuration

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new JacksonSerializer())
    .transport(new JavaNetHttpTransport())
    .authProvider(new BearerAuthProvider("token"))
    .interceptors(List.of(new LoggingInterceptor()))
    .build();
```

## Context Flow

```
Context
    ↓
DefaultContainer (holds Context)
    ↓
PeopleCollectionRequest (holds Context)
    ↓
EntityOperations.submit(context, request)
    ↓
HttpTransport.submit(httpRequest)
    ↓
HttpResponse
```

## Path Construction

`ContextPath` builds URLs from segments and keys:

```java
ContextPath path = ctx.basePath()
    .segment("People")
    .key("scottketchum")
    .segment("Trips");

// Produces: People('scottketchum')/Trips
```

### Key Rules

- **Single-key:** Omit the key name — `People('scottketchum')`
- **Composite keys:** Include names — `OrderDetails(OrderId=1,ProductId=5)`
- **URL encoding:** Spaces → `%20`, preserve OData characters (`$`, `'`, `()`)

## Thread Safety

`Context` is immutable (a record). All fields are final. It's safe to share across threads.

## What's Next

- [Type-Safe Query Building](query-builder.md) — Expression hierarchy
- [Entity Immutability](immutability.md) — Why records matter
