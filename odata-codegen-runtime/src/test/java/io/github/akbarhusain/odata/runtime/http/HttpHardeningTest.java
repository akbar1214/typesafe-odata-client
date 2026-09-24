package io.github.akbarhusain.odata.runtime.http;

import com.sun.net.httpserver.HttpServer;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHardeningTest {

    @Test
    void requestAndResponseAreDeepDefensiveSnapshots() {
        byte[] requestBody = {1};
        List<String> requestValues = new ArrayList<>(List.of("one"));
        Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("X-Test", requestValues);
        HttpRequest request = new HttpRequest(HttpMethod.GET, "https://example.test/a",
                requestHeaders, requestBody, Duration.ofSeconds(1), Duration.ofSeconds(1));

        requestBody[0] = 2;
        requestValues.add("mutated");
        requestHeaders.clear();
        assertEquals(1, request.body()[0]);
        assertEquals(List.of("one"), request.headers().get("x-test"));
        request.body()[0] = 3;
        assertThrows(UnsupportedOperationException.class,
                () -> request.headers().get("X-TEST").add("again"));
        assertEquals(1, request.body()[0]);
        assertEquals(List.of("one"), request.headers().get("X-Test"));
        assertThrows(UnsupportedOperationException.class, () -> request.headers().clear());

        byte[] responseBody = {4};
        List<String> responseValues = new ArrayList<>(List.of("two"));
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("X-Result", responseValues);
        HttpResponse response = new HttpResponse(200, responseHeaders, responseBody);

        responseBody[0] = 5;
        responseValues.add("mutated");
        responseHeaders.clear();
        assertEquals(4, response.body()[0]);
        assertEquals(List.of("two"), response.headers().get("x-result"));
        response.body()[0] = 6;
        assertThrows(UnsupportedOperationException.class,
                () -> response.headers().get("X-RESULT").add("again"));
        assertEquals(4, response.body()[0]);
        assertEquals(List.of("two"), response.headers().get("X-Result"));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().clear());
    }

    @Test
    void headersAreCaseInsensitiveSnapshotsAndTimeoutMustBePositive() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-One", new ArrayList<>(List.of("a")));
        headers.put("x-one", new ArrayList<>(List.of("b")));
        HttpRequest request = new HttpRequest(HttpMethod.GET, "https://example.test", headers,
                null, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertEquals(List.of("a", "b"), request.headers().get("X-ONE"));
        assertThrows(IllegalArgumentException.class, () -> new HttpRequest(HttpMethod.GET,
                "https://example.test", Map.of(), null, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new HttpRequest(HttpMethod.GET,
                "https://example.test", Map.of(), null, Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .connectTimeout(Duration.ZERO));
    }

    @Test
    void requestBoundaryRejectsMalformedHeaderTokens() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .url("https://example.test").header("Bad Header", "value"));
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .url("https://example.test").header("X-Test", "bad\rvalue"));
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .url("https://example.test").header("X-Test", "bad\nvalue"));
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder()
                .url("https://example.test").header("X-Test", "bad\0value"));
    }

    @Test
    void requestToStringRedactsHeadersQuerySecretsAndBody() {
        HttpRequest request = new HttpRequest(HttpMethod.POST,
                "https://example.test/a?access_token=query-secret&safe=value",
                Map.of("Authorization", List.of("Bearer header-secret"),
                        "X-Api-Key", List.of("api-secret"),
                        "Cookie", List.of("session=cookie-secret")),
                "body-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        String text = request.toString();
        assertFalse(text.contains("header-secret"));
        assertFalse(text.contains("api-secret"));
        assertFalse(text.contains("cookie-secret"));
        assertFalse(text.contains("query-secret"));
        assertFalse(text.contains("body-secret"));
        assertTrue(text.contains("access_token"));
    }

    @Test
    void rejectedExecutorIsReturnedAsFailedFuture() {
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        JdkHttpTransport transport = new JdkHttpTransport(rejecting);
        HttpRequest request = request();

        CompletableFuture<HttpResponse> submit = transport.submit(request);
        assertTrue(submit.isCompletedExceptionally());
        assertThrows(ExecutionException.class, submit::get);

        CompletableFuture<InputStream> stream = transport.stream(request);
        assertTrue(stream.isCompletedExceptionally());
        assertThrows(ExecutionException.class, stream::get);
    }

    @Test
    void streamTreatsRedirectAsErrorAndClosesErrorBody() throws Exception {
        TrackingInputStream errorBody = new TrackingInputStream("failure".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor queuedExecutor = queued::set;
        JdkHttpTransport transport = new JdkHttpTransport(queuedExecutor) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return fakeClient(302, errorBody);
            }
        };

        CompletableFuture<InputStream> future = transport.stream(request());
        queued.get().run();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class, future::get);
        assertTrue(errorBody.closed);

        TrackingInputStream closeBody = new TrackingInputStream("failure".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        transport = new JdkHttpTransport(queuedExecutor) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return fakeClient(404, closeBody);
            }
        };
        future = transport.stream(request());
        queued.get().run();
        assertThrows(ExecutionException.class, future::get);
        assertTrue(closeBody.closed);
    }

    @Test
    void cancellationClosesStreamProducedAfterCancellation() throws Exception {
        TrackingInputStream lateBody = new TrackingInputStream("late".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<Runnable> queued = new AtomicReference<>();
        JdkHttpTransport transport = new JdkHttpTransport(queued::set) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return fakeClient(200, lateBody);
            }
        };

        CompletableFuture<InputStream> future = transport.stream(request());
        assertTrue(future.cancel(false));
        queued.get().run();
        assertTrue(lateBody.closed);
    }

    @Test
    void streamWrapsIoFailureAsODataExceptionAndKeepsCause() throws Exception {
        IOException failure = new IOException("socket failed");
        JdkHttpTransport transport = new JdkHttpTransport(Runnable::run) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return new FakeHttpClient() {
                    @Override
                    public <T> java.net.http.HttpResponse<T> send(
                            java.net.http.HttpRequest request,
                            java.net.http.HttpResponse.BodyHandler<T> handler)
                            throws IOException {
                        throw failure;
                    }
                };
            }
        };

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> transport.stream(request()).get());
        assertInstanceOf(ODataException.class, error.getCause());
        assertSame(failure, error.getCause().getCause());
    }

    @Test
    void streamWrapsInterruptionAndRestoresStatusWithoutLosingCause() throws Exception {
        InterruptedException failure = new InterruptedException("stopped");
        JdkHttpTransport transport = new JdkHttpTransport(Runnable::run) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return new FakeHttpClient() {
                    @Override
                    public <T> java.net.http.HttpResponse<T> send(
                            java.net.http.HttpRequest request,
                            java.net.http.HttpResponse.BodyHandler<T> handler)
                            throws IOException, InterruptedException {
                        throw failure;
                    }
                };
            }
        };

        Thread.interrupted();
        try {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> transport.stream(request()).get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(ODataException.class, error.getCause());
            assertSame(failure, error.getCause().getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void syncStreamRestoresCallerInterruptForWrappedTransportFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wrapped-interrupt-test");
            thread.setDaemon(true);
            return thread;
        });
        JdkHttpTransport transport = new JdkHttpTransport(executor) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return new FakeHttpClient() {
                    @Override
                    public <T> java.net.http.HttpResponse<T> send(
                            java.net.http.HttpRequest request,
                            java.net.http.HttpResponse.BodyHandler<T> handler)
                            throws IOException, InterruptedException {
                        throw new InterruptedException("worker stopped");
                    }
                };
            }
        };
        Context context = Context.builder()
                .baseUrl("https://example.test")
                .transport(transport)
                .build();

        Thread.interrupted();
        try {
            assertThrows(ODataException.class, () -> EntityOperations.streamMedia(context,
                    context.basePath().addSegment("Media")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }

    @Test
    void internallyCreatedWorkerPoolHasExplicitShutdownLifecycle() throws Exception {
        JdkHttpTransport transport = new JdkHttpTransport(1, 8);
        try {
            assertInstanceOf(AutoCloseable.class, transport);
            var field = Arrays.stream(JdkHttpTransport.class.getDeclaredFields())
                    .filter(candidate -> !java.lang.reflect.Modifier.isStatic(candidate.getModifiers()))
                    .filter(candidate -> ThreadPoolExecutor.class.isAssignableFrom(candidate.getType()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(field, "an internally created pool must be owned and closeable");
            field.setAccessible(true);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(transport);
            assertNotNull(pool);
            ((AutoCloseable) transport).close();
            assertTrue(pool.isShutdown());
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            if (transport instanceof AutoCloseable) {
                ((AutoCloseable) transport).close();
            }
        }
    }

    @Test
    void closingTransportLeavesInjectedExecutorOwnedByCaller() throws Exception {
        ThreadPoolExecutor injected = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
        JdkHttpTransport transport = new JdkHttpTransport(injected);
        try {
            if (transport instanceof AutoCloseable) {
                ((AutoCloseable) transport).close();
            }
            assertFalse(injected.isShutdown());
        } finally {
            injected.shutdownNow();
        }
    }

    @Test
    void interruptedSendRestoresInterruptStatus() throws Exception {
        Executor direct = Runnable::run;
        JdkHttpTransport transport = new JdkHttpTransport(direct) {
            @Override
            HttpClient clientFor(Duration ignored) {
                return new FakeHttpClient() {
                    @Override
                    public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
                                                      java.net.http.HttpResponse.BodyHandler<T> handler)
                            throws IOException, InterruptedException {
                        throw new InterruptedException("stopped");
                    }
                };
            }
        };

        Thread.interrupted();
        CompletableFuture<HttpResponse> future = transport.submit(request());
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(future.isCompletedExceptionally());
        Thread.interrupted();
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void responseBodyReadTimeoutIsEnforced() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            JdkHttpTransport transport = new JdkHttpTransport();
            HttpRequest request = HttpRequest.builder()
                    .url("http://127.0.0.1:" + server.getAddress().getPort())
                    .readTimeout(Duration.ofMillis(250))
                    .build();
            InputStream stream = transport.stream(request).get(2, TimeUnit.SECONDS);
            assertEquals('x', stream.read());
            assertThrows(IOException.class, stream::read);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void defaultExecutorIsBounded() throws Exception {
        var field = JdkHttpTransport.class.getDeclaredField("DEFAULT_EXECUTOR");
        field.setAccessible(true);
        Object executor = field.get(null);
        assertInstanceOf(ThreadPoolExecutor.class, executor);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        assertTrue(pool.getMaximumPoolSize() > 0);
        assertTrue(pool.getQueue().remainingCapacity() < Integer.MAX_VALUE);
    }

    private static HttpRequest request() {
        return HttpRequest.builder().url("https://example.test").build();
    }

    private static HttpClient fakeClient(int status, InputStream body) {
        return new FakeHttpClient() {
            @Override
            public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
                                              java.net.http.HttpResponse.BodyHandler<T> handler) {
                @SuppressWarnings("unchecked")
                java.net.http.HttpResponse<T> response = (java.net.http.HttpResponse<T>) new FakeResponse(status, body, request);
                return response;
            }
        };
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static class FakeResponse implements java.net.http.HttpResponse<InputStream> {
        private final int status;
        private final InputStream body;
        private final java.net.http.HttpRequest request;

        private FakeResponse(int status, InputStream body, java.net.http.HttpRequest request) {
            this.status = status;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode() { return status; }
        @Override public java.net.http.HttpRequest request() { return request; }
        @Override public Optional<java.net.http.HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public InputStream body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private abstract static class FakeHttpClient extends HttpClient {
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(30)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request,
                                                                       java.net.http.HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request,
                                                                       java.net.http.HttpResponse.BodyHandler<T> handler,
                                                                       java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
