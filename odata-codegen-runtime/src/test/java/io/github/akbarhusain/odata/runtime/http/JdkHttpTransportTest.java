package io.github.akbarhusain.odata.runtime.http;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JdkHttpTransportTest {

    @Test
    void usesInjectedExecutorInsteadOfCommonPool() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Executor executor = r -> {
            Thread t = new Thread(r, "custom-io-exec");
            seen.add(t.getName());
            t.start();
        };

        // Package-private constructor accepting an executor (added by the fix).
        JdkHttpTransport transport = new JdkHttpTransport(executor);

        Context ctx = Context.builder()
                .baseUrl("http://127.0.0.1:1") // non-routable; the task still runs on the executor
                .transport(transport)
                .build();

        try {
            EntityOperations.executeSync(ctx, HttpMethod.GET,
                    ctx.basePath().addSegment("x"), null, null);
        } catch (Exception ignored) {
            // connection refused is expected; we only care which thread ran the task
        }

        assertTrue(seen.contains("custom-io-exec"),
                "Request should execute on the injected executor, not ForkJoinPool.commonPool");
    }

    @Test
    void m13ClientsCachedPerConnectTimeoutAndDistinctAcrossDurations() {
        JdkHttpTransport transport = new JdkHttpTransport();
        java.net.http.HttpClient default30 = transport.clientFor(java.time.Duration.ofSeconds(30));
        java.net.http.HttpClient also30 = transport.clientFor(java.time.Duration.ofSeconds(30));
        java.net.http.HttpClient five = transport.clientFor(java.time.Duration.ofSeconds(5));
        java.net.http.HttpClient nullDuration = transport.clientFor(null);

        assertSame(default30, also30, "same connect timeout must reuse the cached client");
        assertSame(default30, nullDuration, "null request timeout falls back to the 30s default client");
        assertNotSame(default30, five, "connect timeout is per-HttpClient; different durations need distinct clients");
    }

    @Test
    void m10ClientCacheIsIsolatedPerTransportInstance() {
        // The cache used to be static — clients were shared across transport instances,
        // a footgun the moment per-instance configuration (proxy/TLS) is introduced.
        JdkHttpTransport t1 = new JdkHttpTransport();
        JdkHttpTransport t2 = new JdkHttpTransport();
        assertSame(t1.clientFor(java.time.Duration.ofSeconds(30)), t1.clientFor(java.time.Duration.ofSeconds(30)),
                "cache still applies within one instance");
        assertNotSame(t1.clientFor(java.time.Duration.ofSeconds(30)), t2.clientFor(java.time.Duration.ofSeconds(30)),
                "clients must not leak across transport instances");
    }
}
