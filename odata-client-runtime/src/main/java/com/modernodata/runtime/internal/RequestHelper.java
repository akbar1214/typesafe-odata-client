package com.modernodata.runtime.internal;

import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import com.modernodata.runtime.exception.*;
import com.modernodata.runtime.http.*;
import com.modernodata.runtime.paging.CollectionPage;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class RequestHelper {

    private RequestHelper() {}

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
        Map<String, List<String>> headers = new HashMap<>();

        headers.putAll(toMultiMap(context.authProvider().getHeaders()));

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

        HttpTransport transport = context.transport();

        for (HttpInterceptor interceptor : context.interceptors()) {
            HttpResponse response = interceptor.intercept(request, transport);
            return CompletableFuture.completedFuture(response);
        }

        return transport.submit(request);
    }

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
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
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
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.DELETE, path, null,
                headers.isEmpty() ? null : headers);
        checkResponse(response);
    }

    public static void addRef(Context context, ContextPath navigationPath, String targetEntityUrl) {
        ContextPath refPath = navigationPath.addSegment("$ref");
        byte[] body = ("{\"@odata.id\":\"" + targetEntityUrl + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            com.fasterxml.jackson.databind.JavaType listType = mapper.getTypeFactory()
                    .constructCollectionType(java.util.List.class, elementType);

            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode valueNode = root.get("value");

            List<T> items;
            if (valueNode != null && valueNode.isArray()) {
                items = mapper.convertValue(valueNode, listType);
            } else {
                items = List.of();
            }

            String nextLink = null;
            com.fasterxml.jackson.databind.JsonNode nextLinkNode = root.get("@odata.nextLink");
            if (nextLinkNode != null && !nextLinkNode.isNull()) {
                nextLink = nextLinkNode.asText();
            }

            Long count = null;
            com.fasterxml.jackson.databind.JsonNode countNode = root.get("@odata.count");
            if (countNode != null && !countNode.isNull()) {
                count = countNode.asLong();
            }

            return new CollectionPage<>(items, nextLink, count);
        } catch (java.io.IOException e) {
            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
        }
    }

    public static void checkResponse(HttpResponse response) {
        if (response.isSuccessful()) return;

        int code = response.statusCode();
        throw switch (code) {
            case 400 -> new BadRequestException(response);
            case 401 -> new UnauthorizedException(response);
            case 403 -> new ForbiddenException(response);
            case 404 -> new NotFoundException(response);
            case 409 -> new ConflictException(response);
            case 429 -> new RateLimitException(response);
            default -> new ODataException(code, "HTTP " + code + ": " + response.getText());
        };
    }

    private static Map<String, List<String>> toMultiMap(Map<String, String> singleMap) {
        Map<String, List<String>> result = new HashMap<>();
        for (var entry : singleMap.entrySet()) {
            result.put(entry.getKey(), List.of(entry.getValue()));
        }
        return result;
    }
}
