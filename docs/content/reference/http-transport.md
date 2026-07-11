# HTTP Transport

Configure HTTP execution for OData requests.

## HttpTransport Interface

```java
public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);
    CompletableFuture<InputStream> stream(HttpRequest request);
}
```

## Built-in Implementations

### JdkHttpTransport (Default)

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new JdkHttpTransport())
    .build();
```

**Features:**

- Uses `java.net.http.HttpClient`
- No external dependencies
- Async by default
- HTTP/2 support

### JavaNetHttpTransport

```java
import io.github.akbarhusain.odata.runtime.http.JavaNetHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new JavaNetHttpTransport())
    .build();
```

**Features:**

- Uses Apache HttpClient
- Connection pooling
- Configurable timeouts
- Proxy support

## Custom Implementations

### Implement HttpTransport

```java
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class OkHttpTransport implements HttpTransport {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
        Request.Builder builder = new Request.Builder()
            .url(request.url())
            .method(request.method(), toRequestBody(request));

        request.headers().forEach((key, values) ->
            values.forEach(value -> builder.addHeader(key, value))
        );

        return CompletableFuture.supplyAsync(() -> {
            try (Response response = client.newCall(builder.build()).execute()) {
                return toHttpResponse(response);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<InputStream> stream(HttpRequest request) {
        // Similar implementation
    }
}
```

### Use Custom Transport

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new OkHttpTransport())
    .build();
```

## HttpRequest

Immutable record representing an HTTP request:

```java
public record HttpRequest(
    String url,
    String method,
    Map<String, List<String>> headers,
    byte[] body,
    Duration timeout
) {
    public HttpRequest withHeader(String name, String value) { ... }
    public HttpRequest withTimeout(Duration timeout) { ... }
}
```

## HttpResponse

Immutable record representing an HTTP response:

```java
public record HttpResponse(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body
) {
    public String header(String name) { ... }
    public <T> T deserializeBody(Class<T> type, Serializer serializer) { ... }
}
```

## HttpInterceptor

Add cross-cutting concerns to all requests:

```java
public class LoggingInterceptor implements HttpInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public HttpRequest beforeRequest(HttpRequest request) {
        log.debug("Request: {} {}", request.method(), request.url());
        return request;
    }

    @Override
    public HttpResponse afterResponse(HttpRequest request, HttpResponse response) {
        log.debug("Response: {} for {}", response.statusCode(), request.url());
        return response;
    }
}
```

### Add Interceptor

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .addInterceptor(new LoggingInterceptor())
    .build();
```

## Timeouts

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .property("connectTimeout", "5000")  // 5 seconds
    .property("readTimeout", "30000")    // 30 seconds
    .build();
```

## What's Next

- [Serialization](serialization.md) — JSON library options
- [Error Handling](error-handling.md) — Typed exceptions
