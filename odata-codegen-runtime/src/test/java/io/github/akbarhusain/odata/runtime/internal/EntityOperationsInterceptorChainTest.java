package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;

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
    void chainWithNoInterceptorsReturnsTheRealTransport() {
        // H4: without interceptors the chain must not wrap the transport — the wrapper's
        // stream() buffers the whole response body through intercept(), defeating streaming.
        HttpTransport transport = new OrderTrackingTransport();
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(transport)
                .build();

        assertSame(transport, EntityOperations.buildTransportChain(ctx, transport),
                "Without interceptors the chain must be the real transport instance");
    }

    @Test
    void streamWithNoInterceptorsDelegatesToTransportStream() {
        List<String> streamedUrls = new ArrayList<>();
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                fail("stream path must not go through submit()");
                return null;
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
                .build();

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                .url("http://example.com/People")
                .build();

        EntityOperations.buildTransportChain(ctx, ctx.transport())
                .stream(request)
                .join();

        assertEquals(List.of("http://example.com/People"), streamedUrls,
                "stream() must reach the underlying transport directly when no interceptors exist");
    }

    @Test
    void interceptorStreamHookCanDelegateTrueStreaming() {
        // An interceptor overriding the stream() hook can pass the request straight to
        // the underlying transport, avoiding the default buffering-through-intercept path.
        List<String> streamedUrls = new ArrayList<>();
        List<String> interceptedStreams = new ArrayList<>();

        HttpInterceptor i1 = new HttpInterceptor() {
            @Override
            public HttpResponse intercept(HttpRequest request, HttpTransport delegate) {
                fail("stream path must not fall back to intercept() when stream() is overridden");
                return null;
            }

            @Override
            public CompletableFuture<InputStream> stream(HttpRequest request, HttpTransport delegate) {
                interceptedStreams.add(request.url());
                return delegate.stream(request);
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
                .url("http://example.com/People/1/$value")
                .build();

        EntityOperations.buildTransportChain(ctx, ctx.transport())
                .stream(request)
                .join();

        assertEquals(List.of("http://example.com/People/1/$value"), interceptedStreams,
                "Interceptor stream hook must be invoked");
        assertEquals(List.of("http://example.com/People/1/$value"), streamedUrls,
                "Underlying transport stream must be reached without buffering");
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

    @Test
    void m14InterceptorStreamDefaultThrowsOnErrorStatus() {
        HttpInterceptor passthrough = (req, delegate) -> delegate.submit(req).join();
        HttpTransport failing = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(404, Map.of(),
                        "{\"error\":{\"code\":\"NotFound\"}}".getBytes()));
            }

            @Override
            public CompletableFuture<InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(failing)
                .interceptors(List.of(passthrough))
                .build();

        assertThrows(NotFoundException.class,
                () -> EntityOperations.streamMedia(ctx, ctx.basePath().addSegment("Media").addKey("Id", 1)),
                "the default interceptor stream() must not turn an HTTP error into a body stream");
    }
}
