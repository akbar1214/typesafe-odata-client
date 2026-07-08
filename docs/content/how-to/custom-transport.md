# Use Custom HTTP Transport

Switch from JDK HTTP to OkHttp, Apache, or your own implementation.

## Default: JDK HTTP

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build(); // Uses JdkHttpTransport by default
```

## Apache HttpClient

```java
import io.github.akbarhusain.odata.runtime.http.ApacheHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new ApacheHttpTransport())
    .build();
```

## OkHttp

### Implement HttpTransport

```java
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;

public class OkHttpTransport implements HttpTransport {
    private final OkHttpClient client = new OkHttpClient();

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
        Request.Builder builder = new Request.Builder()
            .url(request.url())
            .method(request.method(), requestBody(request));

        request.headers().forEach((key, values) ->
            values.forEach(value -> builder.addHeader(key, value))
        );

        return CompletableFuture.supplyAsync(() -> {
            try (Response response = client.newCall(builder.build()).execute()) {
                return new HttpResponse(
                    response.code(),
                    response.headers().toMultimap(),
                    response.body().bytes()
                );
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<InputStream> stream(HttpRequest request) {
        // Similar implementation for streaming
    }
}
```

### Use OkHttp

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new OkHttpTransport())
    .build();
```

## What's Next

- [Handle Errors Gracefully](error-handling.md) — Error handling strategies
- [HTTP Transport Reference](../reference/http-transport.md) — API details
