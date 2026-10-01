package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.*;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public class EntityOperations {

    // JDK System.Logger (no dependency): deliberately-swallowed fallbacks below
    // log at DEBUG so they are diagnosable without changing error semantics
    private static final System.Logger LOG = System.getLogger(EntityOperations.class.getName());

    private static final ObjectMapper COLLECTION_MAPPER;
    private static final ConcurrentHashMap<Class<?>, JavaType> LIST_TYPE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LIFECYCLE_JSON_NAMES = Set.of(
            "changedfields", "unmappedfields", "contextpath", "etag", "key",
            "getchangedfields", "getunmappedfields", "getcontextpath", "getetag", "getkey",
            "odatatypename", "odatatypeannotation", "odatatype");

    static {
        // One OData-format configuration for every wire mapper (ISO temporal strings,
        // offsets preserved) — action bodies and parameter aliases go through this mapper
        // rather than the entity Serializer, so it must not drift from it
        ObjectMapper mapper = JacksonSerializer.newODataMapper();
        COLLECTION_MAPPER = mapper;
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

    public static <T> T deserializeBatchEntity(byte[] body, io.github.akbarhusain.odata.runtime.serialization.Serializer serializer,
                                               Type declaredType, SchemaInfo schemaInfo) {
        Objects.requireNonNull(serializer, "serializer must not be null");
        if (body == null || body.length == 0 || declaredType == null) {
            return null;
        }
        if (schemaInfo == null || !(declaredType instanceof Class<?> declaredClass)) {
            return serializer.deserialize(body, declaredType);
        }
        try {
            JsonNode node = COLLECTION_MAPPER.readTree(body);
            Class<?> target = declaredClass;
            JsonNode typeNode = node != null && node.isObject() ? node.get("@odata.type") : null;
            if (typeNode != null && typeNode.isTextual()) {
                Class<?> actual = schemaInfo.getClassFromTypeWithNamespace(
                        stripTypeAnnotationPrefix(typeNode.asText()));
                if (actual != null && declaredClass.isAssignableFrom(actual)) {
                    target = actual;
                }
            }
            return (T) serializer.deserialize(body, target);
        } catch (IOException e) {
            throw new ODataException("Failed to parse batch response: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializePolymorphic(byte[] body, Context context, Class<T> declaredType,
                                                 SchemaInfo schemaInfo) {
        try {
            JsonNode node = COLLECTION_MAPPER.readTree(body);
            if (node == null || node.isNull()) {
                return null;
            }
            return (T) deserializeNode(node, declaredType, context, schemaInfo);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Polymorphic @odata.type sniffing failed; deserializing as declared", e);
            return null;
        }
    }

    private static String stripTypeAnnotationPrefix(String typeName) {
        if (typeName == null) {
            return null;
        }
        int hash = typeName.lastIndexOf('#');
        return hash >= 0 ? typeName.substring(hash + 1) : typeName;
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePostEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.POST, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeWriteResponse(response, context, responseType, entity);
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePutEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = context.serializer().serialize((T) entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.PUT, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeWriteResponse(response, context, responseType, entity);
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
        return deserializeWriteResponse(response, context, responseType, entity);
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

    private static <T> T deserializeWriteResponse(HttpResponse response, Context context,
                                                   Class<T> responseType, Object submittedEntity) {
        T result = deserializeOrNull(response, context, responseType);
        applyEtagHeader(result != null ? result : submittedEntity, response);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <T> T executePatchEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        byte[] body = serializeForPatch(context, entity, responseType);
        HttpResponse response = executeSync(context, HttpMethod.PATCH, path, body,
                Map.of("Content-Type", "application/json"));
        checkResponse(response);
        return deserializeWriteResponse(response, context, responseType, entity);
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
        return deserializeWriteResponse(response, context, responseType, entity);
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
        String absolute = ContextPath.isAbsoluteHttpUrl(targetEntityUrl)
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
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException(
                    "targetKey must not be null or blank (pass the target entity's absolute "
                            + "or root-relative URI, e.g. People('key'))");
        }
        ContextPath refPath = navigationPath.addSegment("$ref");
        String id = targetKey;
        if (!ContextPath.isAbsoluteHttpUrl(targetKey)
                && (targetKey.indexOf('/') >= 0 || targetKey.indexOf('(') >= 0)) {
            id = trimTrailingSlash(context.baseUrl()) + "/" + trimLeadingSlash(targetKey);
        }
        refPath = refPath.addQuery("$id", id);
        HttpResponse response = executeSync(context, HttpMethod.DELETE, refPath, null, null);
        checkResponse(response);
    }

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
            return new CollectionPage<>(List.of(), null, null);
        }

        try {
            JsonNode envelope = COLLECTION_MAPPER.readTree(response.body());
            if (envelope == null || !envelope.isObject()) {
                throw new ODataException("Invalid collection response: expected a JSON object");
            }
            JsonNode valueNode = envelope.get("value");
            if (valueNode == null || !valueNode.isArray()) {
                throw new ODataException("Invalid collection response: 'value' must be an array");
            }
            valueNode = sanitizeCollectionPayload(valueNode, elementType);

            String nextLink = null;
            JsonNode nextLinkNode = envelope.get("@odata.nextLink");
            if (nextLinkNode != null && nextLinkNode.isTextual() && !nextLinkNode.asText().isEmpty()) {
                nextLink = nextLinkNode.asText();
            }

            Long count = null;
            JsonNode countNode = envelope.get("@odata.count");
            if (countNode != null && countNode.isNumber()) {
                count = countNode.asLong();
            }

            List<T> items;
            if (schemaInfo != null && containsTypeAnnotation(valueNode)) {
                items = new ArrayList<>(valueNode.size());
                for (JsonNode element : valueNode) {
                    items.add(deserializeNode(element, elementType, context, schemaInfo));
                }
            } else if (context.serializer().getClass() == JacksonSerializer.class) {
                JavaType listType = LIST_TYPE_CACHE.computeIfAbsent(
                        elementType, t -> COLLECTION_MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, t));
                try {
                    items = COLLECTION_MAPPER.convertValue(valueNode, listType);
                } catch (RuntimeException e) {
                    throw conversionFailure(e);
                }
            } else {
                items = new ArrayList<>(valueNode.size());
                for (JsonNode element : valueNode) {
                    items.add(deserializeNode(element, elementType, context, schemaInfo));
                }
            }

            return new CollectionPage<>(items, nextLink, count);
        } catch (ODataException e) {
            throw e;
        } catch (IOException e) {
            throw new ODataException("Failed to parse collection response: " + e.getMessage(), e);
        }
    }

    private static ODataException conversionFailure(RuntimeException cause) {
        if (cause instanceof ODataException odataException) {
            return odataException;
        }
        return new ODataException("Failed to convert collection response: " + cause.getMessage(), cause);
    }

    private static boolean containsTypeAnnotation(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("@odata.type")) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsTypeAnnotation(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsTypeAnnotation(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializeNode(JsonNode node, Class<T> declaredType,
                                         Context context, SchemaInfo schemaInfo) {
        if (node == null || node.isNull()) {
            return null;
        }
        Class<?> target = declaredType;
        if (schemaInfo != null && node.isObject()) {
            JsonNode typeNode = node.get("@odata.type");
            if (typeNode != null && typeNode.isTextual()) {
                Class<?> actual = schemaInfo.getClassFromTypeWithNamespace(
                        stripTypeAnnotationPrefix(typeNode.asText()));
                if (actual != null && declaredType.isAssignableFrom(actual)) {
                    target = actual;
                }
            }
        }
        JsonNode safeNode = sanitizeNodeForTarget(node, target);
        Object result = deserializeJsonValue(safeNode, target, context);
        if (schemaInfo != null && result != null && safeNode.isObject() && containsTypeAnnotation(safeNode)) {
            repairExpandedValues(result, safeNode, target, context, schemaInfo);
        }
        return (T) result;
    }

    private static Object deserializeValue(JsonNode node, Type declaredType,
                                           Context context, SchemaInfo schemaInfo) {
        if (node == null || node.isNull()) {
            if (declaredType instanceof ParameterizedType parameterized
                    && rawClass(parameterized.getRawType()) == Optional.class) {
                return Optional.empty();
            }
            return null;
        }
        if (declaredType instanceof ParameterizedType parameterized) {
            Class<?> raw = rawClass(parameterized.getRawType());
            if (Collection.class.isAssignableFrom(raw)) {
                Type elementType = parameterized.getActualTypeArguments()[0];
                Collection<Object> values = Set.class.isAssignableFrom(raw)
                        ? new LinkedHashSet<>() : new ArrayList<>();
                for (JsonNode element : node) {
                    values.add(deserializeValue(element, elementType, context, schemaInfo));
                }
                return values;
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return Optional.ofNullable(deserializeValue(node,
                        parameterized.getActualTypeArguments()[0], context, schemaInfo));
            }
        }
        Class<?> raw = rawClass(declaredType);
        if (node.isArray() && Collection.class.isAssignableFrom(raw)) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                values.add(deserializeValue(element, Object.class, context, schemaInfo));
            }
            return values;
        }
        return deserializeNode(node, raw, context, schemaInfo);
    }

    private static Object deserializeJsonValue(JsonNode node, Class<?> target, Context context) {
        try {
            if (context.serializer().getClass() == JacksonSerializer.class) {
                return COLLECTION_MAPPER.convertValue(node, target);
            }
            return context.serializer().deserialize(COLLECTION_MAPPER.writeValueAsBytes(node), target);
        } catch (ODataException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ODataException("Failed to convert response to " + target.getName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }

    private static void repairExpandedValues(Object bean, JsonNode node, Class<?> type,
                                             Context context, SchemaInfo schemaInfo) {
        Map<String, PropertyAccess> properties = propertyAccess(type);
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().startsWith("@")) {
                continue;
            }
            PropertyAccess property = properties.get(entry.getKey());
            if (isLifecycleJsonName(entry.getKey())
                    && (property == null || !property.explicitlyMapped())) {
                continue;
            }
            if (property == null || (!entry.getValue().isObject()
                    && !entry.getValue().isArray() && !entry.getValue().isNull())) {
                continue;
            }
            Object replacement = deserializeValue(entry.getValue(), property.type(), context, schemaInfo);
            setProperty(bean, property, replacement);
        }
    }

    private static JsonNode sanitizeCollectionPayload(JsonNode valueNode, Class<?> elementType) {
        if (valueNode == null || !valueNode.isArray()) {
            return valueNode;
        }
        ArrayNode copy = null;
        for (int i = 0; i < valueNode.size(); i++) {
            JsonNode original = valueNode.get(i);
            JsonNode safe = sanitizeNodeForType(original, elementType);
            if (safe != original) {
                if (copy == null) {
                    copy = (ArrayNode) valueNode.deepCopy();
                }
                copy.set(i, safe);
            }
        }
        return copy == null ? valueNode : copy;
    }

    private static JsonNode sanitizeNodeForType(JsonNode node, Type type) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = rawClass(parameterized.getRawType());
            if (Collection.class.isAssignableFrom(raw) && node.isArray()) {
                Type elementType = parameterized.getActualTypeArguments()[0];
                ArrayNode copy = null;
                for (int i = 0; i < node.size(); i++) {
                    JsonNode original = node.get(i);
                    JsonNode safe = sanitizeNodeForType(original, elementType);
                    if (safe != original) {
                        if (copy == null) {
                            copy = (ArrayNode) node.deepCopy();
                        }
                        copy.set(i, safe);
                    }
                }
                return copy == null ? node : copy;
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return sanitizeNodeForTarget(node, rawClass(parameterized.getActualTypeArguments()[0]));
            }
        }
        return sanitizeNodeForTarget(node, rawClass(type));
    }

    private static JsonNode sanitizeNodeForTarget(JsonNode node, Class<?> target) {
        if (node == null || !node.isObject() || !io.github.akbarhusain.odata.runtime.entity.ODataType.class
                .isAssignableFrom(target)) {
            return node;
        }
        Map<String, PropertyAccess> properties = propertyAccess(target);
        ObjectNode copy = null;
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            PropertyAccess property = properties.get(entry.getKey());
            boolean lifecycle = isLifecycleJsonName(entry.getKey())
                    && (property == null || !property.explicitlyMapped());
            if (lifecycle) {
                if (copy == null) {
                    copy = (ObjectNode) node.deepCopy();
                }
                copy.remove(entry.getKey());
                continue;
            }
            if (property != null) {
                JsonNode safe = sanitizeNodeForType(entry.getValue(), property.type());
                if (safe != entry.getValue()) {
                    if (copy == null) {
                        copy = (ObjectNode) node.deepCopy();
                    }
                    copy.set(entry.getKey(), safe);
                }
            }
        }
        return copy == null ? node : copy;
    }

    private static boolean isLifecycleJsonName(String name) {
        return name != null && LIFECYCLE_JSON_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * JSON-name -&gt; reflective accessor for a generated type, derived once per class.
     *
     * <p>Walking the hierarchy with {@code getDeclaredFields}/{@code getDeclaredMethods}
     * (plus {@link #ignoredPropertyNames} per class) is by far the most expensive step in
     * polymorphic deserialization: it runs about three times per JSON node -- once in
     * {@link #sanitizeCollectionPayload} for the DECLARED element type (so even the
     * non-polymorphic fast path paid it), once in {@code deserializeNode} for the
     * resolved subtype, and once more in {@link #repairExpandedValues}. On a 500-element
     * collection that is ~1500 full reflective walks.
     *
     * <p>{@link ClassValue} rather than a static {@code ConcurrentHashMap<Class<?>, ...>}:
     * the latter strongly references the {@code Class} and therefore its
     * {@code ClassLoader}, so in a container or a plugin host it pins every loader the
     * JVM has ever seen for the process lifetime. {@code ClassValue} is associated with
     * the class itself and its value is dropped when the class is unloaded.
     */
    private static final ClassValue<Map<String, PropertyAccess>> PROPERTY_ACCESS =
            new ClassValue<>() {
                @Override
                protected Map<String, PropertyAccess> computeValue(Class<?> type) {
                    return buildPropertyAccess(type);
                }
            };

    /**
     * Test hook: how many times the reflective index has been derived in this JVM. The
     * increment lives in {@link #buildPropertyAccess} (not in {@code computeValue}) so the
     * count also grows if the cache is bypassed and the walk runs per node — which is what
     * makes the caching test able to fail.
     */
    private static final java.util.concurrent.atomic.AtomicLong PROPERTY_ACCESS_BUILDS =
            new java.util.concurrent.atomic.AtomicLong();

    static long propertyAccessBuildCount() {
        return PROPERTY_ACCESS_BUILDS.get();
    }

    private static Map<String, PropertyAccess> propertyAccess(Class<?> type) {
        return PROPERTY_ACCESS.get(type);
    }

    private static Map<String, PropertyAccess> buildPropertyAccess(Class<?> type) {
        PROPERTY_ACCESS_BUILDS.incrementAndGet();
        Map<String, PropertyAccess> result = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Set<String> ignoredNames = ignoredPropertyNames(current);
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getAnnotation(JsonIgnore.class) != null) {
                    continue;
                }
                JsonProperty annotation = field.getAnnotation(JsonProperty.class);
                String name = annotation != null && !annotation.value().isEmpty()
                        ? annotation.value() : field.getName();
                if (ignoredNames.contains(name.toLowerCase(Locale.ROOT))
                        || (isLifecycleJsonName(name) && annotation == null)) {
                    continue;
                }
                result.putIfAbsent(name, new PropertyAccess(field, null, field.getGenericType(),
                        annotation != null, false));
            }
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                        || !method.getName().startsWith("set")
                        || method.getName().length() <= 3
                        || method.getAnnotation(JsonIgnore.class) != null) {
                    continue;
                }
                String suffix = method.getName().substring(3);
                String javaName = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
                JsonProperty annotation = method.getAnnotation(JsonProperty.class);
                String name = annotation != null && !annotation.value().isEmpty()
                        ? annotation.value() : javaName;
                boolean explicit = annotation != null;
                if (ignoredNames.contains(name.toLowerCase(Locale.ROOT))
                        || (isLifecycleJsonName(name) && !explicit)) {
                    continue;
                }
                PropertyAccess existing = result.get(name);
                if (existing == null) {
                    result.put(name, new PropertyAccess(null, method, method.getGenericParameterTypes()[0],
                            explicit, false));
                } else if (existing.setter() == null) {
                    result.put(name, new PropertyAccess(existing.field(), method,
                            method.getGenericParameterTypes()[0], existing.explicitlyMapped() || explicit,
                            existing.lifecycle()));
                }
            }
        }
        return result;
    }

    private static Set<String> ignoredPropertyNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || method.getAnnotation(JsonIgnore.class) == null) {
                continue;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                name = name.substring(3);
            } else if (name.startsWith("is") && name.length() > 2) {
                name = name.substring(2);
            } else {
                continue;
            }
            names.add(Character.toLowerCase(name.charAt(0)) + name.substring(1)
                    .toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static void setProperty(Object bean, PropertyAccess property, Object value) {
        try {
            if (property.setter() != null) {
                if (!property.setter().canAccess(bean)) {
                    property.setter().setAccessible(true);
                }
                property.setter().invoke(bean, value);
            } else if (property.field() != null) {
                if (!property.field().canAccess(bean)) {
                    property.field().setAccessible(true);
                }
                property.field().set(bean, value);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ODataException("Failed to materialize expanded value: " + e.getMessage(), e);
        }
    }

    private record PropertyAccess(Field field, Method setter, Type type,
                                  boolean explicitlyMapped, boolean lifecycle) {}


    public static long executeCount(Context context, ContextPath path) {
        // OData v4.01 Part 2: "Resource paths ending in /$count allow $filter and
        // $search" (SS5.1); "The count MUST NOT be affected by $top, $skip, $orderby,
        // or $expand" (SS4.8). The caller may hand us a path that came from an
        // @odata.nextLink, which carries exactly the paging options the server chose
        // ($top/$skip/$skiptoken) - none of which belong on /$count. Drop them rather
        // than letting the generated countValue() (or a direct caller) emit a URL that
        // either 400s or silently returns the wrong number. Custom options and
        // parameter aliases survive: they are the caller's own instruction, and a
        // retained $filter may reference an alias, so dropping it would dangle the filter.
        ContextPath countPath = path.retainSystemQueryOptions("$filter", "$search").addCountSegment();
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

    // Operation (function/action import) invocations. Functions are GET with parameters
    // embedded in the URL fragment; actions are POST with a JSON parameter body. Generated
    // request classes build the path/parameter encoding and pick the right variant based on
    // the operation's return-type kind: object → invoke*, primitive → invokePrimitive*,
    // collection-of-primitive → invokePrimitiveCollection*, void → invokeVoidSync.

    public static <T> T invokeSync(Context context, ContextPath path, HttpMethod method,
                                   byte[] body, Class<T> responseType) {
        HttpResponse response = executeSync(context, method, path, body, contentTypeHeader(body));
        checkResponse(response);
        return deserializeOrNull(response, context, responseType);
    }

    public static <T> CompletableFuture<T> invokeAsync(Context context, ContextPath path, HttpMethod method,
                                                       byte[] body, Class<T> responseType) {
        return executeAsync(context, method, path, body, contentTypeHeader(body))
                .thenApply(response -> {
                    checkResponse(response);
                    return deserializeOrNull(response, context, responseType);
                });
    }

    public static <T> T invokeSync(Context context, ContextPath path, HttpMethod method,
                                   byte[] body, Class<T> responseType, SchemaInfo schemaInfo) {
        HttpResponse response = executeSync(context, method, path, body, contentTypeHeader(body));
        checkResponse(response);
        T entity = readEntity(response, context, responseType, schemaInfo);
        applyEtagHeader(entity, response);
        return entity;
    }

    public static <T> CompletableFuture<T> invokeAsync(Context context, ContextPath path, HttpMethod method,
                                                       byte[] body, Class<T> responseType, SchemaInfo schemaInfo) {
        return executeAsync(context, method, path, body, contentTypeHeader(body))
                .thenApply(response -> {
                    checkResponse(response);
                    T entity = readEntity(response, context, responseType, schemaInfo);
                    applyEtagHeader(entity, response);
                    return entity;
                });
    }

    /** Entity-shaped read with optional polymorphic {@code @odata.type} resolution. */
    private static <T> T readEntity(HttpResponse response, Context context, Class<T> type,
                                    SchemaInfo schemaInfo) {
        if (schemaInfo != null && response.body() != null && response.body().length > 0) {
            T polymorphic = deserializePolymorphic(response.body(), context, type, schemaInfo);
            if (polymorphic != null) return polymorphic;
        }
        return deserializeOrNull(response, context, type);
    }

    /**
     * Invokes an operation whose result is a single complex-type or enum value. Per the
     * OData v4 JSON format these arrive value-wrapped — {@code {"@odata.context":...,
     * "value":{...}}} — unlike entities, which arrive at the JSON root. The envelope is
     * unwrapped (a {@code "value"} that is the sole non-control property), tolerating
     * services that inline the value at the root; {@code schemaInfo} additionally
     * resolves {@code @odata.type} to subtypes, mirroring entity reads.
     */
    public static <T> T invokeComplexSync(Context context, ContextPath path, HttpMethod method,
                                          byte[] body, Class<T> complexType) {
        return invokeComplexSync(context, path, method, body, complexType, null);
    }

    public static <T> T invokeComplexSync(Context context, ContextPath path, HttpMethod method,
                                          byte[] body, Class<T> complexType, SchemaInfo schemaInfo) {
        try {
            return invokeComplexAsync(context, path, method, body, complexType, schemaInfo).join();
        } catch (CompletionException e) {
            rethrowCause(e, "Operation failed");
            throw new ODataException("Operation failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    public static <T> CompletableFuture<T> invokeComplexAsync(Context context, ContextPath path, HttpMethod method,
                                                              byte[] body, Class<T> complexType) {
        return invokeComplexAsync(context, path, method, body, complexType, null);
    }

    public static <T> CompletableFuture<T> invokeComplexAsync(Context context, ContextPath path, HttpMethod method,
                                                              byte[] body, Class<T> complexType,
                                                              SchemaInfo schemaInfo) {
        return executeAsync(context, method, path, body, contentTypeHeader(body))
                .thenApply(response -> {
                    checkResponse(response);
                    return deserializeWrapped(response.body(), context, complexType, schemaInfo);
                });
    }

    /**
     * Invokes an operation whose result is a single primitive value. Spec-conformant
     * services wrap it — {@code {"@odata.context":"...","value":<literal>}} — so the
     * envelope is unwrapped whenever {@code "value"} is the only non-control property;
     * bare JSON literals at the root are taken as-is. Element bytes always route through
     * the configured {@link io.github.akbarhusain.odata.runtime.serialization.Serializer} so
     * custom serializers apply (parity with collection reads, lesson 50).
     */
    public static <T> T invokePrimitiveSync(Context context, ContextPath path, HttpMethod method,
                                            byte[] body, Class<T> primitiveType) {
        try {
            return invokePrimitiveAsync(context, path, method, body, primitiveType).join();
        } catch (CompletionException e) {
            rethrowCause(e, "Operation failed");
            throw new ODataException("Operation failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    public static <T> CompletableFuture<T> invokePrimitiveAsync(Context context, ContextPath path,
                                                                HttpMethod method, byte[] body,
                                                                Class<T> primitiveType) {
        return executeAsync(context, method, path, body, contentTypeHeader(body))
                .thenApply(response -> {
                    checkResponse(response);
                    return deserializePrimitive(response.body(), context, primitiveType);
                });
    }

    public static <T> List<T> invokePrimitiveCollectionSync(Context context, ContextPath path,
                                                            HttpMethod method, byte[] body,
                                                            Class<T> elementClass) {
        try {
            return executeAsync(context, method, path, body, contentTypeHeader(body))
                    .thenApply(response -> {
                        checkResponse(response);
                        return deserializePrimitiveList(response.body(), context, elementClass);
                    })
                    .join();
        } catch (CompletionException e) {
            rethrowCause(e, "Operation failed");
            throw new ODataException("Operation failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /** Void operations: execute and surface typed errors only; no result parsing. */
    public static void invokeVoidSync(Context context, ContextPath path, HttpMethod method, byte[] body) {
        HttpResponse response = executeSync(context, method, path, body, contentTypeHeader(body));
        checkResponse(response);
    }

    /**
     * Serializes an action-import parameter map into the JSON request body. Uses the shared
     * mapper directly — parameter maps are plain JSON objects with no entity-specific
     * serialization policy to honor.
     */
    public static byte[] buildActionBody(Map<String, Object> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        try {
            return COLLECTION_MAPPER.writeValueAsBytes(new LinkedHashMap<>(parameters));
        } catch (IOException e) {
            throw new ODataException("Failed to serialize action parameters: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes a structured (complex/entity) function parameter into the JSON literal
     * for its parameter alias — complex values cannot be embedded inline in the
     * invocation path, so URL Conventions §5.1.1 requires them to travel as alias query
     * options. Uses the shared mapper directly, exactly like {@link #buildActionBody}:
     * parameter values are plain JSON with no entity-specific serialization policy.
     */
    public static String jsonParameter(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "structured parameter value must not be null; nullable parameters must be "
                            + "omitted from the invocation, not rendered as 'null'");
        }
        try {
            return new String(COLLECTION_MAPPER.writeValueAsBytes(value),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ODataException("Failed to serialize structured parameter: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> contentTypeHeader(byte[] body) {
        return body == null ? null : Map.of("Content-Type", "application/json");
    }

    @SuppressWarnings("unchecked")
    static <T> T deserializePrimitive(byte[] bodyBytes, Context context, Class<T> type) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        try {
            var root = unwrapValueEnvelope(COLLECTION_MAPPER.readTree(bodyBytes));
            return (T) context.serializer().deserialize(COLLECTION_MAPPER.writeValueAsBytes(root), type);
        } catch (IOException e) {
            throw new ODataException("Failed to parse operation result: " + e.getMessage(), e);
        }
    }

    /**
     * Spec-conformant operation responses carry control annotations alongside the
     * result — {@code {"@odata.context":"...","value":42}} — so the envelope is
     * recognized whenever {@code "value"} is the only non-{@code @}-prefixed property,
     * not only when it is the only property at all.
     */
    static com.fasterxml.jackson.databind.JsonNode unwrapValueEnvelope(
            com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null || root.isNull() || !root.isObject() || !root.has("value")) {
            return root;
        }
        for (var it = root.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!field.startsWith("@") && !field.equals("value")) {
                return root;
            }
        }
        return root.get("value");
    }

    /**
     * Deserializes a value-wrapped (complex/enum) operation result: unwrap the envelope,
     * optionally resolve {@code @odata.type} to a subtype, then deserialize through the
     * configured {@link io.github.akbarhusain.odata.runtime.serialization.Serializer}.
     */
    @SuppressWarnings("unchecked")
    static <T> T deserializeWrapped(byte[] bodyBytes, Context context, Class<T> type, SchemaInfo schemaInfo) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        try {
            JsonNode root = unwrapValueEnvelope(COLLECTION_MAPPER.readTree(bodyBytes));
            return (T) deserializeNode(root, type, context, schemaInfo);
        } catch (ODataException e) {
            throw e;
        } catch (IOException e) {
            throw new ODataException("Failed to parse operation result: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> deserializePrimitiveList(byte[] bodyBytes, Context context, Class<T> elementClass) {

        if (bodyBytes == null || bodyBytes.length == 0) {
            return List.of();
        }
        try {
            var root = COLLECTION_MAPPER.readTree(bodyBytes);
            List<com.fasterxml.jackson.databind.JsonNode> elements = root.isObject() && root.has("value")
                    && root.get("value").isArray()
                    ? asNodes(root.get("value"))
                    : root.isArray() ? asNodes(root)
                    : null;
            if (elements == null) {
                throw new ODataException("Expected a JSON array in the operation response");
            }
            List<T> result = new ArrayList<>(elements.size());
            for (var element : elements) {
                result.add((T) context.serializer().deserialize(
                        COLLECTION_MAPPER.writeValueAsBytes(element), elementClass));
            }
            return Collections.unmodifiableList(result);
        } catch (IOException e) {
            throw new ODataException("Failed to parse operation result: " + e.getMessage(), e);
        }
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> asNodes(com.fasterxml.jackson.databind.JsonNode arrayNode) {
        List<com.fasterxml.jackson.databind.JsonNode> nodes = new ArrayList<>(arrayNode.size());
        arrayNode.forEach(nodes::add);
        return nodes;
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
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                .url(url)
                .headers(requestHeaders(context, url, Map.of("Accept", "*/*"), "application/json"))
                .connectTimeout(context.connectTimeout())
                .readTimeout(context.readTimeout())
                .build();

        return submitStream(buildTransportChain(context, context.transport()), request);
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

    private static final Map<Context, Map<HttpTransport, HttpTransport>> CHAIN_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static HttpTransport buildTransportChain(Context context, HttpTransport real) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(real, "real transport must not be null");
        if (context.interceptors().isEmpty()) {
            return real;
        }
        synchronized (CHAIN_CACHE) {
            Map<HttpTransport, HttpTransport> byTransport = CHAIN_CACHE.computeIfAbsent(
                    context, ignored -> new IdentityHashMap<>());
            HttpTransport cached = byTransport.get(real);
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
                        try {
                            return CompletableFuture.completedFuture(next.intercept(request, delegate));
                        } catch (RuntimeException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    }

                    @Override
                    public CompletableFuture<InputStream> stream(HttpRequest request) {
                        return submitInterceptorStream(next, request, delegate);
                    }
                };
            }
            byTransport.put(real, transport);
            return transport;
        }
    }

    private static CompletableFuture<InputStream> submitStream(HttpTransport transport,
                                                                HttpRequest request) {
        try {
            CompletableFuture<InputStream> result = transport.stream(request);
            return result == null ? CompletableFuture.failedFuture(
                    new NullPointerException("HTTP stream transport returned null future")) : result;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<InputStream> submitInterceptorStream(HttpInterceptor interceptor,
                                                                            HttpRequest request,
                                                                            HttpTransport delegate) {
        try {
            CompletableFuture<InputStream> result = interceptor.stream(request, delegate);
            return result == null ? CompletableFuture.failedFuture(
                    new NullPointerException("HTTP stream interceptor returned null future")) : result;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static final Set<String> REPLACED_PROTOCOL_HEADERS = Set.of(
            "accept", "content-type", "if-match", "odata-version", "odata-maxversion");

    public static Map<String, List<String>> requestHeaders(Context context, String url,
                                                            Map<String, String> extraHeaders,
                                                            String defaultAccept) {
        Objects.requireNonNull(context, "context must not be null");
        Map<String, String> authHeaders = Objects.requireNonNull(
                context.authProvider().getHeaders(), "authentication headers must not be null");
        validateAuthenticationOrigin(context, url, authHeaders, extraHeaders);

        int headerCount = authHeaders.size() + (extraHeaders != null ? extraHeaders.size() : 0);
        Map<String, List<String>> headers = new LinkedHashMap<>(Math.max(headerCount + 4, 8));
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            String name = HttpHeaders.requireRequestName(entry.getKey());
            HttpHeaders.requireRequestValue(name, entry.getValue());
            if (!REPLACED_PROTOCOL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                putHeaderCaseInsensitive(headers, name, entry.getValue());
            }
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                String name = HttpHeaders.requireRequestName(entry.getKey());
                HttpHeaders.requireRequestValue(name, entry.getValue());
                if (REPLACED_PROTOCOL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    setHeaderCaseInsensitive(headers, name, entry.getValue());
                } else {
                    putHeaderCaseInsensitive(headers, name, entry.getValue());
                }
            }
        }
        if (defaultAccept != null) {
            ensureHeader(headers, "Accept", defaultAccept);
        }
        ensureHeader(headers, "OData-Version", "4.0");
        ensureHeader(headers, "OData-MaxVersion", "4.01");
        return headers;
    }

    private static void validateAuthenticationOrigin(Context context, String url,
                                                       Map<String, String> authHeaders,
                                                       Map<String, String> extraHeaders) {
        boolean hasAuthentication = !authHeaders.isEmpty();
        if (!hasAuthentication && extraHeaders != null) {
            hasAuthentication = extraHeaders.keySet().stream().anyMatch(EntityOperations::isAuthenticationHeader);
        }
        if (!hasAuthentication) {
            return;
        }
        final URI base;
        final URI target;
        try {
            base = URI.create(context.baseUrl());
            URI parsed = URI.create(url);
            target = parsed.isAbsolute() ? parsed : base.resolve(parsed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Request URL is not a valid absolute URI", e);
        }
        if (!isPermittedAuthTarget(base, target)) {
            throw new IllegalArgumentException(
                    "Refusing to forward configured authentication to a cross-origin or HTTPS-downgrade URL");
        }
    }

    private static boolean isAuthenticationHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("authorization")
                || lower.equals("proxy-authorization")
                || lower.equals("cookie")
                || lower.contains("token")
                || lower.contains("api-key")
                || lower.contains("apikey")
                || lower.contains("auth");
    }

    /**
     * Authentication configured for {@code base} may be forwarded when the target is same-origin,
     * or when the scheme is upgraded from {@code http} to {@code https} on the same host — an
     * upgrade is strictly safer than the configured plaintext origin. Cross-origin targets and
     * HTTPS-to-HTTP downgrades are always rejected.
     */
    private static boolean isPermittedAuthTarget(URI base, URI target) {
        if (sameOrigin(base, target)) {
            return true;
        }
        return sameHost(base, target)
                && "http".equalsIgnoreCase(base.getScheme())
                && "https".equalsIgnoreCase(target.getScheme());
    }

    private static boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost());
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left.getScheme() == null || right.getScheme() == null
                || left.getHost() == null || right.getHost() == null) {
            return false;
        }
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }

    private static void putHeaderCaseInsensitive(Map<String, List<String>> headers,
                                                 String name, String value) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                headers.get(key).add(value);
                return;
            }
        }
        headers.put(name, new ArrayList<>(List.of(value)));
    }

    private static void setHeaderCaseInsensitive(Map<String, List<String>> headers,
                                                 String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, new ArrayList<>(List.of(value)));
    }

    private static boolean hasHeader(Map<String, List<String>> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private static void ensureHeader(Map<String, List<String>> headers, String name, String value) {
        if (!hasHeader(headers, name)) {
            setHeaderCaseInsensitive(headers, name, value);
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
        if (cause instanceof ODataException odataException
                && odataException.getCause() instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw odataException;
        }
        if (cause instanceof RuntimeException re) throw re;
    }

    public static CompletableFuture<HttpResponse> executeAsync(Context context, HttpMethod method,
                                                                ContextPath path, byte[] body,
                                                                Map<String, String> extraHeaders) {
        String url = path.toUrl();
        HttpRequest request = HttpRequest.builder()
                .method(method)
                .url(url)
                .headers(requestHeaders(context, url, extraHeaders, "application/json"))
                .body(body)
                .connectTimeout(context.connectTimeout())
                .readTimeout(context.readTimeout())
                .build();

        HttpTransport transport = buildTransportChain(context, context.transport());
        try {
            CompletableFuture<HttpResponse> result = transport.submit(request);
            return result == null ? CompletableFuture.failedFuture(
                    new NullPointerException("HTTP transport returned null future")) : result;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
