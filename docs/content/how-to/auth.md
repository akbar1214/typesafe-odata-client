# Add Authentication

Configure authentication for OData services.

## No Authentication

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

// No auth needed for public services
DefaultContainer client = new DefaultContainer(ctx);
```

## API Key Authentication

### Using Query Parameter

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new ApiKeyAuthProvider("your-api-key"))
    .build();

DefaultContainer client = new DefaultContainer(ctx);
```

### Using Header

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new ApiKeyAuthProvider("your-api-key", "X-Api-Key"))
    .build();
```

## Bearer Token (OAuth2)

### Static Token

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new BearerTokenAuthProvider("your-oauth-token"))
    .build();
```

### Token Refresh

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new OAuth2AuthProvider(
        "https://auth.example.com/token",
        "client-id",
        "client-secret"
    ))
    .build();
```

## Basic Authentication

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new BasicAuthProvider("username", "password"))
    .build();
```

## Custom Authentication

### Implement AuthProvider

```java
public class CustomAuthProvider implements AuthProvider {
    @Override
    public HttpRequest addAuth(HttpRequest request) {
        String token = getMyToken(); // Your logic here
        return request.withHeader("Authorization", "Bearer " + token);
    }
}
```

### Use Custom Auth

```java
Context ctx = Context.builder()
    .baseUrl("https://your-service.com/V4/odata")
    .auth(new CustomAuthProvider())
    .build();
```

## What's Next

- [Use Custom HTTP Transport](custom-transport.md) — OkHttp, Apache, or your own
- [Handle Errors Gracefully](error-handling.md) — Error handling strategies
