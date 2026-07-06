# Error Handling

Complete reference for exception handling.

## Exception Hierarchy

```
ODataException (base)
├── BadRequestException (400)
├── UnauthorizedException (401)
├── ForbiddenException (403)
├── NotFoundException (404)
├── ConflictException (409)
├── PreconditionFailedException (412)
├── RateLimitException (429)
└── ServerException (5xx)
```

## Exception Details

### ODataException

Base exception for all OData errors.

```java
public class ODataException extends RuntimeException {
    private final int statusCode;
    private final String odataError;
    private final String message;

    public int getStatusCode() { ... }
    public String getODataError() { ... }
}
```

### BadRequestException (400)

Invalid request syntax or semantics.

```java
try {
    client.people().filter("invalid filter").get();
} catch (BadRequestException e) {
    System.out.println("Bad request: " + e.getMessage());
}
```

### UnauthorizedException (401)

Missing or invalid authentication.

```java
try {
    client.people().get();
} catch (UnauthorizedException e) {
    // Redirect to login
    redirectToLogin();
}
```

### ForbiddenException (403)

Valid authentication but insufficient permissions.

```java
try {
    client.admin().get();
} catch (ForbiddenException e) {
    showAccessDenied();
}
```

### NotFoundException (404)

Entity or resource not found.

```java
try {
    client.peopleByUserName("nonexistent").get();
} catch (NotFoundException e) {
    System.out.println("Person not found");
}
```

### ConflictException (409)

Entity state conflict (e.g., duplicate key).

```java
try {
    client.people().post(existingPerson);
} catch (ConflictException e) {
    System.out.println("Person already exists");
}
```

### PreconditionFailedException (412)

ETag mismatch during update.

```java
try {
    request.patchWithETag(updated, etag);
} catch (PreconditionFailedException e) {
    Person current = request.get();
    System.out.println("Conflict! Current ETag: " + current.getETag());
}
```

### RateLimitException (429)

Too many requests.

```java
try {
    return client.people().get();
} catch (RateLimitException e) {
    long retryAfter = e.getRetryAfter();
    Thread.sleep(retryAfter * 1000);
    return client.people().get();
}
```

### ServerException (5xx)

Internal server error.

```java
try {
    return client.people().get();
} catch (ServerException e) {
    if (e.getStatusCode() == 503) {
        // Service unavailable, retry later
        Thread.sleep(5000);
        return client.people().get();
    }
    throw e;
}
```

## Catching All Exceptions

```java
try {
    // OData operations
} catch (ODataException e) {
    // Base exception - check status code
    int status = e.getStatusCode();
    String message = e.getMessage();
}
```

## Best Practices

1. **Catch specific exceptions first** — `NotFoundException` before `ODataException`
2. **Handle rate limiting** — Retry after `getRetryAfter()` seconds
3. **Handle ETag conflicts** — Re-fetch entity and retry
4. **Log errors** — Include status code and message
5. **Don't swallow exceptions** — At minimum, log them

## What's Next

- [OData URL Patterns](odata-urls.md) — URL building rules
- [Package Structure](packages.md) — Module organization
