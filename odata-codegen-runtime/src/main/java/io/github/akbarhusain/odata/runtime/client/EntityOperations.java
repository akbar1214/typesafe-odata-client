package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.*;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

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
    private static final JavaType MAP_TYPE;
    private static final ConcurrentHashMap<Class<?>, JavaType> LIST_TYPE_CACHE = new ConcurrentHashMap<>();

    static {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        COLLECTION_MAPPER = mapper;
        MAP_TYPE = mapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class);
    }

    private EntityOperations() {}

    public static <T> T executeAndGetEntity(Context context, ContextPath path, Class<T> type) {
        return executeAndGetEntity(context, path, type, null);
    }

    /**
     * GET a single entity with polymorphic deserialization: when the payload carries
     * {@code @odata.type} and the SchemaInfo registry resolves it to a subtype of the
     * declared type, the entity is deserialized as the subtype so subtype properties
     * survive (otherwise they are silently dropped by the lenient mapper).
     */
    public static <T> T executeAndGetEntity(Context context, ContextPath path, Class<T> type,
                                            SchemaInfo schemaInfo) {
        HttpResponse response = executeSync(context, HttpMethod.GET, path, null, null);
        checkResponse(response);
        T entity;
        if (schemaInfo != null && response.body() != null && response.body().length > 0) {
            T polymorphic = deserializePolymorphic(response.body(), context, type, schemaInfo);
            entity = polymorphic != null ? polymorphic : deserializeOrNull(response, context, type);
        } else {
            entity = deserializeOrNull(response, context, type);
        }
        applyEtagHeader(entity, response);
        return entity;
    }

    /**
     * Captures a header-only ETag onto the entity (M7): services that return the
     * concurrency token only as a header would otherwise force users to raw HTTP
     * before any conditional write. The body's {@code @odata.etag} annotation wins.
     */
    private static void applyEtagHeader(Object entity, HttpResponse response) {
        if (!(entity instanceof io.github.akbarhusain.odata.runtime.entity.ODataEntityType odataEntity)
                || odataEntity.getETag().isPresent()) {
            return;
        }
        List<String> etags = response.headers().get("ETag");
        if (etags != null && !etags.isEmpty()) {
            String etag = etags.get(0);
            if (etag != null && !etag.isEmpty()) {
                odataEntity.applyETagFromResponse(etag);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializePolymorphic(byte[] body, Context context, Class<T> declaredType,
                                                SchemaInfo schemaInfo) {
        try {
            var node = COLLECTION_MAPPER.readTree(body);
            if (node != null && node.isObject()) {
                var typeNode = node.get("@odata.type");
                if (typeNode != null && typeNode.isTextual()) {
                    Class<?> actual = schemaInfo.getClassFromTypeWithNamespace(
                            stripTypeAnnotationPrefix(typeNode.asText()));
                    if (actual != null && declaredType.isAssignableFrom(actual)) {
                        return (T) context.serializer().deserialize(body, actual);
                    }
                }
            }
        } catch (IOException e) {
            // fall through to declared-type deserialization below
        }
        return null;
    }

    /** {@code @odata.type} values may be URL fragments: {@code #Namespace.Type}. */
    private static String stripTypeAnnotationPrefix(String typeName) {
        return typeName.startsWith("#") ? typeName.substring(1) : typeName;
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePostEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.POST, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePutEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.PUT, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePutEntityWithETag(Context context, ContextPath path, Object entity,
                                                   Class<T> responseType, String etag) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.PUT, path, body, headers);
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
    }

    /**
     * PATCH bodies honor the entity's tracked {@code changedFields} (populated by Builder
     * and with* copy-on-write) when non-empty — a partial update instead of a full-body
     * merge. Entities deserialized from a GET and mutated via setters track nothing and
     * send the full body (full-body PATCH is legal OData merge semantics either way).
     */
    @SuppressWarnings("unchecked")
    private static <T> byte[] serializeForPatch(Context context, Object entity, Class<T> responseType) {
        if (entity instanceof io.github.akbarhusain.odata.runtime.entity.ODataEntityType odataEntity) {
            java.util.Set<String> changed = odataEntity.getChangedFields();
            if (changed != null && !changed.isEmpty()) {
                return context.serializer().serialize((T) entity, responseType, changed);
            }
        }
        return context.serializer().serialize((T) entity, responseType);
    }

    private static <T> T deserializeOrNull(HttpResponse response, Context context, Class<T> responseType) {
        // Some services return 204 (or an empty body) for POST/PUT/PATCH, and GET can return
        // 204 for gone entities (e.g. TripPin). Return null rather than failing to deserialize
        // an empty payload; the caller already has the entity for writes.
        if (response.body() == null || response.body().length == 0) {
            return null;
        }
        return context.serializer().deserialize(response.body(), responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePatchEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = serializeForPatch(context, entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.PATCH, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePatchEntityWithETag(Context context, ContextPath path, Object entity,
                                                     Class<T> responseType, String etag) {
        byte[] body = serializeForPatch(context, entity, responseType);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.PATCH, path, body, headers);
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
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

    private static boolean isAbsoluteHttpUrl(String url) {
        return url != null && url.length() >= 4 && url.regionMatches(true, 0, "http", 0, 4);
    }

    public static void addRef(Context context, ContextPath navigationPath, String targetEntityUrl) {
        // Validate here rather than letting Map.of NPE deep inside the body build
        if (targetEntityUrl == null || targetEntityUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "targetEntityUrl must not be null or blank (pass the target entity's absolute "
                            + "or root-relative URI, e.g. People('key'))");
        }
        ContextPath refPath = navigationPath.addSegment("$ref");
        // @odata.id must be an ABSOLUTE URI unless the payload carries @odata.context —
        // relative values are rejected by services (TripPin: 500 "relative URI value ...
        // odata.context annotation is missing"). Resolve like batch does (decision 12).
        String absolute = isAbsoluteHttpUrl(targetEntityUrl)
                ? targetEntityUrl
                : trimTrailingSlash(context.baseUrl()) + "/" + trimLeadingSlash(targetEntityUrl);
        byte[] body;
        try {
            body = COLLECTION_MAPPER.writeValueAsBytes(Map.of("@odata.id", absolute));
        } catch (IOException e) {
            throw new ODataException("Failed to build $ref body: " + e.getMessage(), e);
        }
        HttpResponse response = executeSync(context, HttpMethod.POST, refPath, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trimLeadingSlash(String url) {
        return url.startsWith("/") ? url.substring(1) : url;
    }

    public static void removeRef(Context context, ContextPath navigationPath, String targetKey) {
        ContextPath refPath = navigationPath.addSegment("$ref");
        if (targetKey != null && !targetKey.isEmpty()) {
            // Like @odata.id, the $id query parameter must be an absolute entity URI on
            // strict services (TripPin resolves it as a query). Entity paths (containing
            // a '/' or a key predicate) are resolved against the service root; bare key
            // values are passed through as-is for services that accept them.
            String id = targetKey;
            if (!isAbsoluteHttpUrl(targetKey)
                    && (targetKey.indexOf('/') >= 0 || targetKey.indexOf('(') >= 0)) {
                id = trimTrailingSlash(context.baseUrl()) + "/" + trimLeadingSlash(targetKey);
            }
            refPath = refPath.addQuery("$id", id);
        }
        HttpResponse response = executeSync(context, HttpMethod.DELETE, refPath, null, null);
        checkResponse(response);
    }

    @SuppressWarnings("unchecked")
    public static <T> CollectionPage<T> executeAndGetCollection(Context context, ContextPath path,
                                                                  Class<T> elementType) {
        return executeAndGetCollection(context, path, elementType, null);
    }

    /**
     * GET a collection with polymorphic deserialization: elements carrying
     * {@code @odata.type} resolve to their subtype via the SchemaInfo registry.
     */
    public static <T> CollectionPage<T> executeAndGetCollection(Context context, ContextPath path,
                                                                  Class<T> elementType, SchemaInfo schemaInfo) {
        HttpResponse response = executeSync(context, HttpMethod.GET, path, null, null);
        checkResponse(response);

        if (response.body() == null || response.body().length == 0) {
            // 204/empty-bodied 2xx collection responses (documented TripPin behavior) mean no items
            return new CollectionPage<>(List.of(), null, null);
        }

        try {
            Map<String, Object> envelope = COLLECTION_MAPPER.readValue(response.body(), MAP_TYPE);

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
                boolean polymorphic = schemaInfo != null && rawList.stream()
                        .anyMatch(e -> e instanceof Map<?, ?> m && m.containsKey("@odata.type"));
                if (polymorphic) {
                    items = new ArrayList<>(rawList.size());
                    for (Object element : rawList) {
                        items.add(deserializeElement(context, element, elementType, schemaInfo));
                    }
                } else if (context.serializer() instanceof JacksonSerializer) {
                    // Fast path for the default serializer: in-memory conversion, no
                    // re-serialization round trip (profiling lessons 34/37)
                    JavaType listType = LIST_TYPE_CACHE.computeIfAbsent(
                            elementType, t -> COLLECTION_MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, t));
                    items = COLLECTION_MAPPER.convertValue(rawList, listType);
                } else {
                    // Honor a pluggable Serializer: it sees each element's bytes. The
                    // Serializer interface has no tree/convert API, so each element is
                    // re-serialized and delegated (page sizes are small).
                    items = new ArrayList<>(rawList.size());
                    for (Object element : rawList) {
                        try {
                            items.add(context.serializer().deserialize(
                                    COLLECTION_MAPPER.writeValueAsBytes(element), elementType));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
                        }
                    }
                }
            } else {
                items = List.of();
            }

            return new CollectionPage<>(items, nextLink, count);
        } catch (IOException e) {
            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializeElement(Context context, Object element, Class<T> declaredType,
                                            SchemaInfo schemaInfo) {
        Class<?> target = declaredType;
        if (element instanceof Map<?, ?> m) {
            Object typeName = m.get("@odata.type");
            if (typeName instanceof String s) {
                Class<?> actual = schemaInfo.getClassFromTypeWithNamespace(stripTypeAnnotationPrefix(s));
                if (actual != null && declaredType.isAssignableFrom(actual)) {
                    target = actual;
                }
            }
        }
        try {
            if (context.serializer() instanceof JacksonSerializer) {
                return (T) COLLECTION_MAPPER.convertValue(element, target);
            }
            return context.serializer().deserialize(COLLECTION_MAPPER.writeValueAsBytes(element), (Class<T>) target);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
        }
    }

    public static long executeCount(Context context, ContextPath path) {
        ContextPath countPath = path.addCountSegment();
        // /$count returns plain text per the OData spec — never accept application/json
        HttpResponse response = executeSync(context, HttpMethod.GET, countPath, null,
                Map.of("Accept", "text/plain"));
        checkResponse(response);
        String text = response.getText();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ODataException("Failed to parse $count response: (empty body)");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new ODataException("Failed to parse $count response: '" + trimmed + "'", e);
        }
    }

    public static void checkResponse(HttpResponse response) {
        if (response.isSuccessful()) return;
        throw ODataException.fromResponse(response);
    }

    // Media ($value) operations — entity itself is a media stream (HasStream="true") or a
    // property is an Edm.Stream (named stream at <property>/$value). The request layer builds
    // the path with addSegment("$value"); entities (no Context) must go through the request.

    public static InputStream streamMedia(Context context, ContextPath path) {
        try {
            return streamMediaAsync(context, path).join();
        } catch (CompletionException e) {
            rethrowCause(e, "Stream failed");
            throw new ODataException("Stream failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    public static CompletableFuture<InputStream> streamMediaAsync(Context context, ContextPath path) {
        String url = path.toUrl();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (var entry : context.authProvider().getHeaders().entrySet()) {
            headers.put(entry.getKey(), new ArrayList<>(List.of(entry.getValue())));
        }
        // Request the raw media bytes, not JSON metadata
        headers.put("Accept", new ArrayList<>(List.of("*/*")));

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(url)
                .headers(headers)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();

        return buildTransportChain(context, context.transport()).stream(request);
    }

    public static void putMedia(Context context, ContextPath path, byte[] body, String contentType, String etag) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType != null && !contentType.isEmpty()
                ? contentType : "application/octet-stream");
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", etag);
        }
        HttpResponse response = executeSync(context, HttpMethod.PUT, path, body, headers);
        checkResponse(response);
    }

    // Chains are cached per Context (records compare by value, so identical configurations
    // share one chain) — building N wrappers per request was the last per-request
    // allocation hotspot once interceptors are registered (M10)
    private static final Map<Context, HttpTransport> CHAIN_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static HttpTransport buildTransportChain(Context context, HttpTransport real) {
        if (context.interceptors().isEmpty()) {
            return real;
        }
        synchronized (CHAIN_CACHE) {
            HttpTransport cached = CHAIN_CACHE.get(context);
            if (cached != null) {
                return cached;
            }
            HttpTransport transport = real;
            List<HttpInterceptor> interceptors = context.interceptors();
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                HttpInterceptor next = interceptors.get(i);
                HttpTransport delegate = transport;
                transport = new HttpTransport() {
                    @Override
                    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                        // Interceptor failures must complete the future exceptionally, not
                        // escape synchronously — callers compose with exceptionally()/handle()
                        try {
                            return CompletableFuture.completedFuture(next.intercept(request, delegate));
                        } catch (RuntimeException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }

                    @Override
                    public CompletableFuture<InputStream> stream(HttpRequest request) {
                        return next.stream(request, delegate);
                    }
                };
            }
            CHAIN_CACHE.put(context, transport);
            return transport;
        }
    }

    // Internal helpers

    public static HttpResponse executeSync(Context context, HttpMethod method, ContextPath path,
                                            byte[] body, Map<String, String> extraHeaders) {
        try {
            return executeAsync(context, method, path, body, extraHeaders).join();
        } catch (CompletionException e) {
            rethrowCause(e, "Request failed");
            throw new ODataException("Request failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * Unwraps {@code join()} failures. RuntimeExceptions rethrow as-is; an
     * {@link InterruptedException} cause (the async task was interrupted — executor
     * shutdown, cancelled I/O) restores the interrupt flag on the CALLING thread before
     * throwing, so outer code can observe cancellation. Note: {@link
     * CompletableFuture#join} itself is not interruptible (JDK behavior), so this is the
     * only interruption path that reaches sync callers.
     */
    private static void rethrowCause(CompletionException e, String what) {
        Throwable cause = e.getCause();
        if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ODataException(what + ": interrupted (" + ie.getMessage() + ")", ie);
        }
        if (cause instanceof RuntimeException re) throw re;
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
