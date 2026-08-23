package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4a: the default {@link HttpInterceptor#stream} threw synchronously out of the future
 * chain — callers doing future composition saw exceptions escape at call time instead of
 * through the returned future. Errors must be delivered as a failed future.
 *
 * M4b: sync-over-async wrappers (.join() + unwrap) swallowed thread interruption as a
 * generic ODataException without restoring the interrupt flag.
 */
class SyncAsyncErrorContractTest {

    @Test
    void m4a_defaultStreamErrorIsFailedFutureNotSyncThrow() {
        HttpInterceptor passthrough = (req, delegate) -> delegate.submit(req).join();
        HttpTransport failing = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(404, Map.of(), new byte[0]));
            }

            @Override
            public CompletableFuture<InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        HttpRequest request = HttpRequest.builder().method(HttpMethod.GET)
                .url("http://example.com/Media").build();

        // Must RETURN a future (not throw), and the failure must ride the future
        CompletableFuture<InputStream> future = assertDoesNotThrow(
                () -> passthrough.stream(request, failing),
                "default stream() must not throw synchronously");
        var completion = assertThrows(java.util.concurrent.CompletionException.class, future::join,
                "the failure must surface through the future");
        assertInstanceOf(NotFoundException.class, completion.getCause());
    }

    @Test
    void m4b_executeSyncRestoresInterruptFlagWhenTaskWasInterrupted() {
        // CompletableFuture.join() is not interruptible (JDK behavior), so the only
        // interruption path that reaches the sync wrapper is the async TASK failing
        // with InterruptedException (executor shutdown-now, future.cancel during I/O).
        // The wrapper must restore the flag on the CALLING thread and keep the cause typed.
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(new HttpTransport() {
                    @Override
                    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                        return CompletableFuture.failedFuture(new InterruptedException("executor shutdown"));
                    }

                    @Override
                    public CompletableFuture<InputStream> stream(HttpRequest request) {
                        return CompletableFuture.failedFuture(new UnsupportedOperationException());
                    }
                })
                .authProvider(AuthProvider.none())
                .build();
        ContextPath path = ctx.basePath().addSegment("People");

        ODataException ex = assertThrows(ODataException.class, () ->
                        EntityOperations.executeSync(ctx, HttpMethod.GET, path, null, null),
                "interruption must surface as an exception naming the interruption");
        assertInstanceOf(InterruptedException.class, ex.getCause(),
                "the InterruptedException must remain reachable via getCause()");
        assertTrue(Thread.interrupted(),
                "the interrupt flag must be RESTORED so outer code can observe cancellation");
    }

    @Test
    void m4b_streamSyncRestoresInterruptFlagWhenTaskWasInterrupted() {
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(new HttpTransport() {
                    @Override
                    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                        return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
                    }

                    @Override
                    public CompletableFuture<InputStream> stream(HttpRequest request) {
                        return CompletableFuture.failedFuture(new InterruptedException("executor shutdown"));
                    }
                })
                .authProvider(AuthProvider.none())
                .build();

        assertThrows(ODataException.class, () ->
                EntityOperations.streamMedia(ctx, ctx.basePath().addSegment("M")));
        assertTrue(Thread.interrupted(), "interrupt flag must be restored on the stream path too");
    }
}
