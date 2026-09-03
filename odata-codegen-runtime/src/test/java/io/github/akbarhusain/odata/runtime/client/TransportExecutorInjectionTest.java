package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch C12): the executor-injecting {@code JdkHttpTransport}
 * constructor is package-private, so external users cannot isolate transports
 * from the shared static pool. Must be public. Lives outside the {@code http}
 * package on purpose — the same-package test cannot see the visibility bug.
 */
class TransportExecutorInjectionTest {

    @Test
    void submitRunsOnInjectedExecutor() {
        AtomicBoolean injectedUsed = new AtomicBoolean();
        var real = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-odata-io");
            t.setDaemon(true);
            return t;
        });
        try {
            Executor recording = command -> {
                injectedUsed.set(true);
                real.execute(command);
            };
            JdkHttpTransport transport = new JdkHttpTransport(recording);
            Context ctx = Context.builder()
                    .baseUrl("http://127.0.0.1:9")
                    .transport(transport)
                    .connectTimeout(Duration.ofMillis(100))
                    .build();
            // Connection refused is EXPECTED (nothing listens) — the attempt itself,
            // served by the injected executor, is what this test proves.
            assertThrows(RuntimeException.class, () -> EntityOperations.executeSync(
                    ctx, HttpMethod.GET, ctx.basePath().addSegment("People"), null, null));
            assertTrue(injectedUsed.get(),
                    "the submission must run on the injected executor, not the shared pool");
        } finally {
            real.shutdownNow();
        }
    }
}
