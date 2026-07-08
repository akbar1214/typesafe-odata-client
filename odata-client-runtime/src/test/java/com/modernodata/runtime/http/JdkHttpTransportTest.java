package com.modernodata.runtime.http;

import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.internal.RequestHelper;

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
            RequestHelper.executeSync(ctx, HttpMethod.GET,
                    ctx.basePath().addSegment("x"), null, null);
        } catch (Exception ignored) {
            // connection refused is expected; we only care which thread ran the task
        }

        assertTrue(seen.contains("custom-io-exec"),
                "Request should execute on the injected executor, not ForkJoinPool.commonPool");
    }
}
