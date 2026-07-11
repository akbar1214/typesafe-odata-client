package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsInterceptorChainTest {

    static final class OrderTrackingTransport implements HttpTransport {
        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            return CompletableFuture.completedFuture(InputStream.nullInputStream());
        }
    }

    @Test
    void multipleInterceptorsAllRunInRegistrationOrder() {
        List<String> order = new ArrayList<>();

        HttpInterceptor i1 = (req, delegate) -> {
            order.add("i1");
            return delegate.submit(req).join();
        };
        HttpInterceptor i2 = (req, delegate) -> {
            order.add("i2");
            return delegate.submit(req).join();
        };

        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(new OrderTrackingTransport())
                .interceptors(List.of(i1, i2))
                .build();

        EntityOperations.executeAsync(ctx, HttpMethod.GET,
                ctx.basePath().addSegment("People"), null, null);

        assertEquals(List.of("i1", "i2"), order,
                "Both interceptors must run in registration order");
    }

    @Test
    void streamGoesThroughInterceptorChain() {
        List<String> order = new ArrayList<>();
        List<String> streamedUrls = new ArrayList<>();

        HttpInterceptor i1 = (req, delegate) -> {
            order.add("i1");
            // Stream path: delegate.stream returns an InputStream; wrap as HttpResponse
            InputStream is = delegate.stream(req).join();
            try {
                byte[] body = is.readAllBytes();
                return new HttpResponse(200, Map.of(), body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
            }

            @Override
            public CompletableFuture<InputStream> stream(HttpRequest request) {
                streamedUrls.add(request.url());
                return CompletableFuture.completedFuture(InputStream.nullInputStream());
            }
        };

        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(transport)
                .interceptors(List.of(i1))
                .build();

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                .url("http://example.com/People")
                .build();

        EntityOperations.buildTransportChain(ctx, ctx.transport())
                .stream(request)
                .join();

        assertEquals(List.of("i1"), order, "Interceptor must run for the stream path");
        assertEquals(List.of("http://example.com/People"), streamedUrls,
                "Underlying transport stream must be invoked through the chain");
    }
}
