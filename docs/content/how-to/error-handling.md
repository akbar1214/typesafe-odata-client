# Handle Errors Gracefully

Catch specific exceptions and handle errors properly.

## Exception Hierarchy

```
ODataException (base)
├── BadRequestException (400)
├── UnauthorizedException (401)
├── ForbiddenException (403)
├── NotFoundException (404)
├── RequestTimeoutException (408)
├── ConflictException (409)
├── ResourceGoneException (410)
├── PreconditionFailedException (412)
├── PreconditionRequiredException (428)
├── RateLimitException (429)
└── ServerException (5xx)
```

## Basic Error Handling

```java
import io.github.akbarhusain.odata.runtime.exception.*;

try {
    Person person = client.people().personByUserName("nonexistent").get();
} catch (NotFoundException e) {
    System.out.println("Person not found: " + e.getMessage());
}
```

## Handle Multiple Exceptions

```java
try {
    CollectionPage<Person> people = client.people().get();
} catch (UnauthorizedException e) {
    // Redirect to login
    redirectToLogin();
} catch (ForbiddenException e) {
    // Show access denied
    showAccessDenied();
} catch (RateLimitException e) {
    // Wait and retry (getRetryAfter() is an Instant)
    Thread.sleep(Duration.between(Instant.now(), e.getRetryAfter()).toMillis());
    return client.people().get();
} catch (ODataException e) {
    // Generic error
    System.out.println("OData error: " + e.getMessage());
}
```

## Rate Limiting

```java
try {
    return client.people().get();
} catch (RateLimitException e) {
    // getRetryAfter() is an Instant; when the server sent no Retry-After header,
    // hasServerRetryAfter() is false and the value is a client-side default (now + 60s)
    Instant retryAt = e.getRetryAfter();
    System.out.println("Rate limited. Server-specified retry: " + e.hasServerRetryAfter());
    Thread.sleep(Duration.between(Instant.now(), retryAt).toMillis());
    return client.people().get(); // Retry
}
```

Structured error details are available on every `ODataException` via `getError()`:
`error.code`, `error.message`, `error.target`, and `error.details[]` (a list of
`{code, message, target}` maps) when the service sends the canonical OData error body.

## Precondition Failed (ETag Conflict)

```java
try {
    request.patchWithETag(updated, etag);
} catch (PreconditionFailedException e) {
    // Entity was modified by someone else
    Person current = request.get();
    System.out.println("Conflict! Current version: " + current.getETag().orElse(null));
    // Handle conflict resolution
}
```

## Precondition Required (Missing ETag)

Services that *require* conditional writes — notably TripPin — return
**428 Precondition Required** when a PATCH/DELETE is sent without `If-Match`.
It has its own exception so the etag-missing case is distinguishable from a
genuine conflict (412):

```java
try {
    request.patch(updated);          // no etag sent
} catch (PreconditionRequiredException e) {
    // Service demands If-Match: re-GET to obtain a fresh etag, then retry
    Person current = request.get();  // etag captured automatically (see etag.md)
    request.patchWithETag(updated, current.getETag().orElse(null));
}
```

## Server Errors

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

## What's Next

- [Error Handling Reference](../reference/error-handling.md) — Complete error details
- [Maven Plugin Configuration](../reference/maven-plugin.md) — Build configuration
