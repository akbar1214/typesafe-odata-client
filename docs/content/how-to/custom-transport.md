# Use Custom HTTP Transport

Switch from the built-in JDK transport to your own implementation.

## Default: JDK HTTP

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build(); // Uses JdkHttpTransport by default
```

## Custom Transport (example: OkHttp)

The runtime ships one transport implementation: the JDK `HttpClient`-based
`JdkHttpTransport`. Any other stack (OkHttp, Apache HttpClient) plugs in by implementing
the two-method `HttpTransport` interface — add the HTTP library of your choice as a
dependency first (none is bundled).

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
        Request okRequest = toOkRequest(request); // same builder logic as submit()
        return CompletableFuture.supplyAsync(() -> {
            try (Response response = client.newCall(okRequest).execute()) {
                if (response.code() >= 400) {
                    byte[] errorBody = response.body().bytes();
                    throw io.github.akbarhusain.odata.runtime.exception.ODataException
                            .fromResponse(new HttpResponse(response.code(),
                                    response.headers().toMultimap(), errorBody));
                }
                return responseBodyAsStream(response); // buffer or pipe per your needs
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }
}
```

### Use It

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new OkHttpTransport())
    .build();
```

## What's Next

- [Handle Errors Gracefully](error-handling.md) — Error handling strategies
- [HTTP Transport Reference](../reference/http-transport.md) — API details
