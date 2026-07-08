package com.modernodata.runtime.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import com.modernodata.runtime.exception.ODataException;
import com.modernodata.runtime.http.*;
import com.modernodata.runtime.paging.CollectionPage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public class EntityOperations {

    private static final ObjectMapper COLLECTION_MAPPER;
    private static final ConcurrentHashMap<Class<?>, JavaType> LIST_TYPE_CACHE = new ConcurrentHashMap<>();

    static {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        COLLECTION_MAPPER = mapper;
    }

    private EntityOperations() {}

    public static <T> T executeAndGetEntity(Context context, ContextPath path, Class<T> type) {
        HttpResponse response = executeSync(context, HttpMethod.GET, path, null, null);
        checkResponse(response);
        return context.serializer().deserialize(response.body(), type);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePostEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.POST, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return context.serializer().deserialize(response.body(), responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePatchEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.PATCH, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return context.serializer().deserialize(response.body(), responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePatchEntityWithETag(Context context, ContextPath path, Object entity,
                                                     Class<T> responseType, String etag) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.PATCH, path, body, headers);
        checkResponse(response);
        return context.serializer().deserialize(response.body(), responseType);
    }

    public static void executeDelete(Context context, ContextPath path) {
        HttpResponse response = executeSync(context, HttpMethod.DELETE, path, null, null);
        checkResponse(response);
    }

    public static void executeDeleteWithETag(Context context, ContextPath path, String etag) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.DELETE, path, null,
                headers.isEmpty() ? null : headers);
        checkResponse(response);
    }

    public static void addRef(Context context, ContextPath navigationPath, String targetEntityUrl) {
        ContextPath refPath = navigationPath.addSegment("$ref");
        byte[] body = new StringBuilder(targetEntityUrl.length() + 20)
                .append("{\"@odata.id\":\"")
                .append(targetEntityUrl)
                .append("\"}")
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse response = executeSync(context, HttpMethod.POST, refPath, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
    }

    public static void removeRef(Context context, ContextPath navigationPath, String targetKey) {
        ContextPath refPath = navigationPath.addSegment("$ref");
        if (targetKey != null && !targetKey.isEmpty()) {
            refPath = refPath.addQuery("$id", targetKey);
        }
        HttpResponse response = executeSync(context, HttpMethod.DELETE, refPath, null, null);
        checkResponse(response);
    }

    @SuppressWarnings("unchecked")
    public static <T> CollectionPage<T> executeAndGetCollection(Context context, ContextPath path,
                                                                  Class<T> elementType) {
        HttpResponse response = executeSync(context, HttpMethod.GET, path, null, null);
        checkResponse(response);

        try {
            JavaType mapType = COLLECTION_MAPPER.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class);
            Map<String, Object> envelope = COLLECTION_MAPPER.readValue(response.body(), mapType);

            String nextLink = null;
            Object nextLinkObj = envelope.get("@odata.nextLink");
            if (nextLinkObj instanceof String s && !s.isEmpty()) {
                nextLink = s;
            }

            Long count = null;
            Object countObj = envelope.get("@odata.count");
            if (countObj instanceof Number n) {
                count = n.longValue();
            }

            List<T> items;
            Object valueObj = envelope.get("value");
            if (valueObj instanceof List<?> rawList && !rawList.isEmpty()) {
                JavaType listType = LIST_TYPE_CACHE.computeIfAbsent(
                        elementType, t -> COLLECTION_MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, t));
                items = COLLECTION_MAPPER.convertValue(rawList, listType);
            } else {
                items = List.of();
            }

            return new CollectionPage<>(items, nextLink, count);
        } catch (IOException e) {
            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
        }
    }

    public static void checkResponse(HttpResponse response) {
        if (response.isSuccessful()) return;
        throw ODataException.fromResponse(response);
    }

    public static HttpTransport buildTransportChain(Context context, HttpTransport real) {
        HttpTransport transport = real;
        List<HttpInterceptor> interceptors = context.interceptors();
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            HttpInterceptor next = interceptors.get(i);
            HttpTransport delegate = transport;
            transport = new HttpTransport() {
                @Override
                public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                    return CompletableFuture.completedFuture(next.intercept(request, delegate));
                }

                @Override
                public CompletableFuture<InputStream> stream(HttpRequest request) {
                    throw new UnsupportedOperationException("Interceptors do not support stream()");
                }
            };
        }
        return transport;
    }

    // Internal helpers

    public static HttpResponse executeSync(Context context, HttpMethod method, ContextPath path,
                                            byte[] body, Map<String, String> extraHeaders) {
        try {
            return executeAsync(context, method, path, body, extraHeaders).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new ODataException("Request failed: " + cause.getMessage(), cause);
        }
    }

    public static CompletableFuture<HttpResponse> executeAsync(Context context, HttpMethod method,
                                                                ContextPath path, byte[] body,
                                                                Map<String, String> extraHeaders) {
        String url = path.toUrl();
        Map<String, String> authHeaders = context.authProvider().getHeaders();
        int headerCount = authHeaders.size() + (extraHeaders != null ? extraHeaders.size() : 0);
        Map<String, List<String>> headers = new LinkedHashMap<>(Math.max(headerCount + 1, 4));

        for (var entry : authHeaders.entrySet()) {
            headers.put(entry.getKey(), new ArrayList<>(List.of(entry.getValue())));
        }

        if (extraHeaders != null) {
            for (var entry : extraHeaders.entrySet()) {
                headers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        HttpRequest request = HttpRequest.builder()
                .method(method)
                .url(url)
                .headers(headers)
                .body(body)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();

        HttpTransport transport = buildTransportChain(context, context.transport());

        return transport.submit(request);
    }
}
