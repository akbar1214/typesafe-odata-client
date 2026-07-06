# The Context Pattern

`Context` is the central configuration object that holds HTTP transport, serialization, and authentication.

## What is Context?

`Context` is a Java record that encapsulates everything needed to execute OData requests:

```java
public record Context(
    String baseUrl,
    Serializer serializer,
    HttpTransport transport,
    AuthProvider auth,
    SchemaInfo schemas,
    List<HttpInterceptor> interceptors,
    Map<String, String> properties
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
| `auth` | `null` (no auth) |
| `schemas` | Generated `ServiceSchemaInfo` |
| `interceptors` | Empty list |
| `properties` | Empty map |

### Custom Configuration

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .serializer(new GsonSerializer())
    .transport(new ApacheHttpTransport())
    .auth(new BearerTokenAuthProvider("token"))
    .addInterceptor(new LoggingInterceptor())
    .property("connectTimeout", "5000")
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
RequestHelper.submit(context, request)
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
