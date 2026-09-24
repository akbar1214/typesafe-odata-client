package io.github.akbarhusain.odata.runtime.http;

import io.github.akbarhusain.odata.runtime.exception.ODataException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class JdkHttpTransport implements HttpTransport, AutoCloseable {

    private static final int DEFAULT_MAX_THREADS = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final int DEFAULT_MAX_CLIENTS = 8;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong THREAD_COUNTER = new AtomicLong();
    private static final AtomicLong TIMEOUT_THREAD_COUNTER = new AtomicLong();
    private static final ThreadPoolExecutor DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            DEFAULT_MAX_THREADS,
            DEFAULT_MAX_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
            daemonThreadFactory("odata-http-"),
            new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor READ_TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;
    private final int maxClients;
    private final LinkedHashMap<Duration, HttpClient> clientsByConnectTimeout =
            new LinkedHashMap<>(16, 0.75f, true);

    public JdkHttpTransport() {
        this(DEFAULT_EXECUTOR, DEFAULT_MAX_CLIENTS, false);
    }

    public JdkHttpTransport(Executor executor) {
        this(executor, DEFAULT_MAX_CLIENTS, false);
    }

    public JdkHttpTransport(Executor executor, int maxClients) {
        this(executor, maxClients, false);
    }

    private JdkHttpTransport(Executor executor, int maxClients, boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive");
        }
        this.maxClients = maxClients;
        this.ownedExecutor = ownsExecutor && executor instanceof ThreadPoolExecutor pool ? pool : null;
    }

    public JdkHttpTransport(int maxThreads, int queueCapacity) {
        this(createBoundedExecutor(maxThreads, queueCapacity), DEFAULT_MAX_CLIENTS, true);
    }

    @Override
    public void close() {
        if (ownedExecutor == null) {
            return;
        }
        ownedExecutor.shutdown();
        try {
            if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ownedExecutor.shutdownNow();
                ownedExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            ownedExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    HttpClient clientFor(Duration connectTimeout) {
        Duration effective = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        if (effective.isZero() || effective.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        synchronized (clientsByConnectTimeout) {
            HttpClient existing = clientsByConnectTimeout.get(effective);
            if (existing != null) {
                return existing;
            }
            HttpClient created = createClient(effective);
            clientsByConnectTimeout.put(effective, created);
            while (clientsByConnectTimeout.size() > maxClients) {
                clientsByConnectTimeout.remove(clientsByConnectTimeout.keySet().iterator().next());
            }
            return created;
        }
    }

    @Override
    public CompletableFuture<HttpResponse> submit(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return execute(request);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ODataException("HTTP request interrupted", e);
                } catch (ODataException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ODataException("HTTP request failed: " + e.getMessage(), e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<InputStream> stream(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        CompletableFuture<InputStream> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    InputStream stream = openStream(request);
                    if (!result.complete(stream)) {
                        closeQuietly(stream);
                    }
                } catch (ODataException e) {
                    result.completeExceptionally(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(new ODataException("HTTP stream interrupted", e));
                } catch (Exception e) {
                    result.completeExceptionally(new ODataException(
                            "HTTP stream failed: " + e.getMessage(), e));
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private HttpResponse execute(io.github.akbarhusain.odata.runtime.http.HttpRequest request) throws Exception {
        java.net.http.HttpResponse<byte[]> response = clientFor(request.connectTimeout()).send(
                buildJdkRequest(request).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        Map<String, List<String>> headers = new HashMap<>();
        response.headers().map().forEach(headers::put);
        return new HttpResponse(response.statusCode(), headers, response.body());
    }

    private InputStream openStream(io.github.akbarhusain.odata.runtime.http.HttpRequest request) throws Exception {
        java.net.http.HttpResponse<InputStream> response = clientFor(request.connectTimeout()).send(
                buildJdkRequest(request).build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        if (!isSuccessful(response.statusCode())) {
            byte[] errorBody;
            try {
                errorBody = body == null ? new byte[0] : body.readAllBytes();
            } finally {
                closeQuietly(body);
            }
            Map<String, List<String>> responseHeaders = new HashMap<>();
            response.headers().map().forEach(responseHeaders::put);
            throw ODataException.fromResponse(new HttpResponse(response.statusCode(), responseHeaders, errorBody));
        }
        if (body == null) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new ReadTimeoutInputStream(body, request.readTimeout());
    }

    private java.net.http.HttpRequest.Builder buildJdkRequest(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.readTimeout());

        boolean hasMaxVersion = request.headers().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase("OData-MaxVersion"));
        boolean hasVersion = request.headers().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase("OData-Version"));
        boolean hasAccept = request.headers().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase("Accept"));
        if (!hasMaxVersion) {
            builder.header("OData-MaxVersion", "4.01");
        }
        if (!hasVersion) {
            builder.header("OData-Version", "4.0");
        }
        if (!hasAccept) {
            builder.header("Accept", "application/json");
        }
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        byte[] body = request.body();
        return switch (request.method()) {
            case GET -> builder.GET();
            case DELETE -> builder.DELETE();
            case POST -> builder.POST(body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            case PUT -> builder.PUT(body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            case PATCH -> builder.method("PATCH", body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            default -> builder.method(request.method().name(), body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
        };
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static HttpClient createClient(Duration connectTimeout) {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static ThreadPoolExecutor createBoundedExecutor(int maxThreads, int queueCapacity) {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("maxThreads must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        return new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonThreadFactory("odata-http-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable,
                    "odata-http-timeout-" + TIMEOUT_THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ReadTimeoutInputStream extends InputStream {
        private final InputStream delegate;
        private final Duration timeout;
        private final Object lock = new Object();
        private ScheduledFuture<?> pending;
        private boolean closed;
        private boolean timedOut;

        private ReadTimeoutInputStream(InputStream delegate, Duration timeout) {
            this.delegate = delegate;
            this.timeout = timeout;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            synchronized (lock) {
                if (closed) {
                    throw new IOException("stream is closed");
                }
            }
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException e) {
                nanos = Long.MAX_VALUE;
            }
            ScheduledFuture<?> future = READ_TIMEOUT_EXECUTOR.schedule(this::expire, nanos, TimeUnit.NANOSECONDS);
            synchronized (lock) {
                if (closed) {
                    future.cancel(false);
                    throw new IOException("stream is closed");
                }
                pending = future;
            }
            try {
                int result = delegate.read(bytes, offset, length);
                if (timedOut) {
                    throw new SocketTimeoutException("Response body read timed out after " + timeout);
                }
                return result;
            } catch (IOException e) {
                if (timedOut) {
                    throw new SocketTimeoutException("Response body read timed out after " + timeout);
                }
                throw e;
            } finally {
                synchronized (lock) {
                    if (pending == future) {
                        pending = null;
                    }
                }
                future.cancel(false);
            }
        }

        @Override
        public void close() throws IOException {
            ScheduledFuture<?> future;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                future = pending;
                pending = null;
            }
            if (future != null) {
                future.cancel(false);
            }
            delegate.close();
        }

        private void expire() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                timedOut = true;
            }
            try {
                delegate.close();
            } catch (IOException ignored) {
            }
        }
    }
}
