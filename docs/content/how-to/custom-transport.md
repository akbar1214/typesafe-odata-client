# Use a Custom HTTP Transport

The runtime ships one transport implementation: `JdkHttpTransport`, based on the JDK `HttpClient`. Applications can provide another implementation of the two-method `HttpTransport` interface.

## Interface

```java
public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);
    CompletableFuture<InputStream> stream(HttpRequest request);
}
```

`submit(...)` buffers the response body. `stream(...)` returns a caller-owned raw stream for media downloads; the caller must close it.

## A Delegating Implementation

A transport adapter can wrap another transport without changing generated clients:

```java
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public final class DelegatingTransport implements HttpTransport {
    private final HttpTransport delegate;

    public DelegatingTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
        return delegate.submit(request);
    }

    @Override
    public CompletableFuture<InputStream> stream(HttpRequest request) {
        return delegate.stream(request);
    }
}
```

Use it with the context:

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new DelegatingTransport(new JdkHttpTransport()))
    .build();
```

A transport backed by a third-party HTTP library should preserve `HttpRequest.method()`, `url()`, `headers()`, `body()`, and the timeout fields, and should return `HttpResponse(statusCode, headers, body)` records. It must also preserve typed HTTP failures; for `stream(...)`, map an error response through `ODataException.fromResponse(...)` and complete the future exceptionally.

## Timeouts

`Context` carries `connectTimeout` and `readTimeout` values. The builder defaults are 30 seconds for connect and 60 seconds for read; both are passed into the generated `HttpRequest` and are available to custom transports.

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .readTimeout(java.time.Duration.ofSeconds(30))
    .build();
```

## What's Next

- [HTTP Transport Reference](../reference/http-transport.md) — Record and interceptor APIs
- [Handle Errors Gracefully](error-handling.md) — Typed exceptions
