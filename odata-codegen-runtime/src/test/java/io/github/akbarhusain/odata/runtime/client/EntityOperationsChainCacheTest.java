package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H12: buildTransportChain WeakHashMap get/put race (non-atomic).
 * Two threads racing on same Context may both see null and create distinct wrapper chains.
 * Expected after fix: computeIfAbsent or synchronized block ensures single instance.
 * Currently: get then put not atomic -> may create duplicates under concurrency.
 */
class EntityOperationsChainCacheTest {

    static class NoopTransport implements HttpTransport {
        @Override public CompletableFuture<HttpResponse> submit(HttpRequest request) { return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0])); }
        @Override public CompletableFuture<InputStream> stream(HttpRequest request) { return CompletableFuture.completedFuture(InputStream.nullInputStream()); }
    }

    static class CountingInterceptor implements HttpInterceptor {
        final AtomicInteger count = new AtomicInteger();
        @Override public HttpResponse intercept(HttpRequest request, HttpTransport delegate) {
            count.incrementAndGet();
            return new HttpResponse(200, Map.of(), new byte[0]);
        }
    }

    @Test
    void concurrentBuildTransportChainReturnsSameInstance() throws Exception {
        HttpTransport real = new NoopTransport();
        var interceptor = new CountingInterceptor();
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(real)
                .interceptors(List.of(interceptor))
                .build();

        // Clear cache via reflection to ensure race window
        var cacheField = EntityOperations.class.getDeclaredField("CHAIN_CACHE");
        cacheField.setAccessible(true);
        ((Map<?,?>) cacheField.get(null)).clear();

        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        Set<HttpTransport> results = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    HttpTransport t = EntityOperations.buildTransportChain(ctx, real);
                    results.add(t);
                } catch (Throwable e) { errors.add(e); }
                finally { done.countDown(); }
            }).start();
        }
        done.await();

        assertTrue(errors.isEmpty(), "no thread should error: " + errors);
        // H12: with non-atomic get/put, concurrent threads may create distinct wrapper instances
        assertEquals(1, results.size(),
                "H12: concurrent buildTransportChain with same Context must return single cached instance, but got distinct instances: " + results.size());
    }

    @Test
    void sequentialCallsAreCached() {
        HttpTransport real = new NoopTransport();
        var interceptor = new CountingInterceptor();
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(real)
                .interceptors(List.of(interceptor))
                .build();

        HttpTransport a = EntityOperations.buildTransportChain(ctx, real);
        HttpTransport b = EntityOperations.buildTransportChain(ctx, real);
        assertSame(a, b, "sequential calls should be cached");
    }
}
