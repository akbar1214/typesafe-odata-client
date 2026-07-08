package com.modernodata.runtime.internal;

import com.modernodata.runtime.auth.AuthProvider;
import com.modernodata.runtime.client.EntityOperations;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.http.HttpInterceptor;
import com.modernodata.runtime.http.HttpMethod;
import com.modernodata.runtime.http.HttpRequest;
import com.modernodata.runtime.http.HttpResponse;
import com.modernodata.runtime.http.HttpTransport;

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
}
