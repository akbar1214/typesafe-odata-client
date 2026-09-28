# HTTP Transport

The runtime separates HTTP execution from generated request APIs.

## `HttpTransport`

```java
public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);
    CompletableFuture<InputStream> stream(HttpRequest request);
}
```

Both methods are asynchronous. `submit(...)` returns a buffered response; `stream(...)` returns a caller-owned raw stream for media downloads.

## Built-in Transport

`JdkHttpTransport` is the only transport implementation bundled with the runtime. It uses `java.net.http.HttpClient`, adds default OData headers when the caller has not supplied them, and supports native PATCH.

```java
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new JdkHttpTransport())
    .connectTimeout(Duration.ofSeconds(15))
    .readTimeout(Duration.ofSeconds(45))
    .build();
```

The transport accepts an injected `Executor` and optional client-cache size for applications that need to control concurrency. The default executor is a bounded daemon pool; it is not the common ForkJoin pool.

## `HttpRequest`

`HttpRequest` is an immutable record with defensive copies of headers and body bytes:

```java
public record HttpRequest(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body,
    Duration connectTimeout,
    Duration readTimeout
) {
    public static Builder builder();
}
```

Build one with the actual runtime types:

```java
HttpRequest request = HttpRequest.builder()
    .method(HttpMethod.GET)
    .url("https://services.odata.org/V4/TripPinService/People")
    .header("Accept", "application/json")
    .connectTimeout(Duration.ofSeconds(15))
    .readTimeout(Duration.ofSeconds(45))
    .build();
```

The builder also provides `headers(Map<String, List<String>>)`, `body(byte[])`, and repeated `header(name, value)`. Header lookup is case-insensitive. Request validation rejects blank/control-character URLs, malformed percent-encoded controls, fragments, traversal outside the target, and prohibited hop-by-hop framing headers.

## `HttpResponse`

```java
public record HttpResponse(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body
) {
    public boolean isSuccessful();
    public String getText();
}
```

`headers()` is a case-insensitive immutable view, and `body()` returns a defensive copy. `getText()` decodes the body as UTF-8 and returns an empty string for a null body. The runtime maps non-2xx responses through `ODataException.fromResponse(...)`; `HttpResponse` does not provide a serializer-specific `deserializeBody` method.

## `HttpInterceptor`

An interceptor wraps a request/response exchange:

```java
public interface HttpInterceptor {
    HttpResponse intercept(HttpRequest request, HttpTransport delegate);
    CompletableFuture<InputStream> stream(HttpRequest request, HttpTransport delegate);
}
```

The runtime supplies a default `stream(...)` implementation that buffers through `intercept(...)` and maps non-success responses to typed exceptions. An interceptor that wants true streaming should override it and delegate to `delegate.stream(request)`.

A minimal pass-through interceptor can be implemented as:

```java
public final class DelegatingInterceptor implements HttpInterceptor {
    @Override
    public HttpResponse intercept(HttpRequest request, HttpTransport transport) {
        return transport.submit(request).join();
    }

    @Override
    public CompletableFuture<InputStream> stream(
            HttpRequest request, HttpTransport transport) {
        return transport.stream(request);
    }
}
```

Register interceptors through `Context.builder().interceptors(List.of(...))`. The runtime builds a middleware chain in registration order and caches the chain per context configuration.

## Custom Implementations

A custom transport receives the fully populated `HttpRequest` and returns `HttpResponse` records. It must preserve the request method, URL, headers, body, and timeout values, and must deliver failures through the returned future. See [Use a Custom HTTP Transport](../how-to/custom-transport.md) for a delegating implementation.

## What's Next

- [Serialization](serialization.md)
- [Error Handling](error-handling.md)
