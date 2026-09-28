# Error Handling

OData Codegen maps non-2xx responses to typed runtime exceptions. All HTTP exceptions inherit the structured error accessor from `ODataException`.

## Exception Hierarchy

```text
ODataException
├── BadRequestException             400
├── UnauthorizedException           401
├── ForbiddenException              403
├── NotFoundException               404
├── RequestTimeoutException         408
├── ConflictException               409
├── ResourceGoneException           410
├── PreconditionFailedException     412
├── PreconditionRequiredException   428
├── RateLimitException              429
└── ServerException                 5xx
```

Unmapped status codes still produce an `ODataException` with the HTTP status and parsed error when one can be read.

## `ODataException`

```java
public class ODataException extends RuntimeException {
    public int getStatusCode();
    public ODataError getError();
    public static ODataException fromResponse(HttpResponse response);
}
```

`getError()` returns `null` for an empty body, invalid JSON, or a response without an `error` property. A syntactically valid but structurally malformed error shape, such as `{"error":"not an object"}`, produces a non-null `ODataError` with null code/message/target and empty details. Always null-check before dereferencing. When present, `ODataError` exposes:

```java
String getCode();
String getMessage();
String getTarget();
Map<String, Object> getDetails();
```

`details` contains parsed `details[]` entries and any supported inner-error fields.

## Basic Handling

```java
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.exception.ODataError;

try {
    client.people("missing").get();
} catch (NotFoundException e) {
    ODataError error = e.getError();
    System.out.println(error == null ? e.getMessage() : error.getMessage());
} catch (ODataException e) {
    System.out.println(e.getStatusCode());
}
```

## Rate Limiting

`RateLimitException.getRetryAfter()` returns an `Instant`. The runtime does not retry automatically. `hasServerRetryAfter()` distinguishes a parsed server `Retry-After` value from the runtime's client-side default of approximately one minute.

```java
try {
    return client.people().get();
} catch (RateLimitException e) {
    Instant retryAt = e.getRetryAfter();
    if (retryAt.isAfter(Instant.now())) {
        Thread.sleep(Duration.between(Instant.now(), retryAt).toMillis());
    }
    return client.people().get();
}
```

## ETag Conflicts

```java
try {
    request.patchWithETag(updated, etag);
} catch (PreconditionFailedException e) {
    Person current = request.get();
    System.out.println(current.getETag().orElse(null));
}
```

A service that requires `If-Match` can return 428 instead of 412. Catch `PreconditionRequiredException` separately when the service makes that distinction.

## Server Errors

```java
try {
    return client.people().get();
} catch (ServerException e) {
    if (e.getStatusCode() == 503) {
        throw new IllegalStateException("retry later", e);
    }
    throw e;
}
```

## Batch Errors

A successful outer batch response can contain failed individual results. Inspect each `BatchResult.isSuccessful()` and `statusCode()`; `BatchRequest` throws a typed exception when the outer HTTP response itself is non-2xx or malformed.

## What's Next

- [OData URL Patterns](odata-urls.md)
- [Package Structure](packages.md)
