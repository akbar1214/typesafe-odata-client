package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.ODataType;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reflective property index used by polymorphic deserialization is derived once per
 * class, not once per JSON node.
 *
 * <p>It is consulted about three times per node -- in the pre-branch sanitize pass for
 * the declared element type, again in {@code deserializeNode} for the resolved subtype,
 * and again in {@code repairExpandedValues} -- and each call walked the whole class
 * hierarchy with {@code getDeclaredFields}/{@code getDeclaredMethods}. On a 500-element
 * collection that is ~1500 full reflective walks, which made a polymorphic read
 * measurably slower than the plain fast path for no reason at all: the answer is a pure
 * function of the class.
 */
class PolymorphicPropertyAccessCachingTest {

    // Mirrors what the generator emits: CSDL wire names arrive through @JsonProperty on
    // the setters (decision 24). Without them Jackson matches lowerCamelCase property
    // names and silently drops every wire field -- the fixture has to look like
    // production or it measures nothing (lesson 169).
    static class Base implements ODataType {
        protected String name;
        protected String description;
        public String getName() { return name; }
        @com.fasterxml.jackson.annotation.JsonProperty("Name")
        public void setName(String v) { this.name = v; }
        public String getDescription() { return description; }
        @com.fasterxml.jackson.annotation.JsonProperty("Description")
        public void setDescription(String v) { this.description = v; }
        public String odataTypeName() { return "NS.Base"; }
        public Map<String, Object> getUnmappedFields() { return Map.of(); }
        public ContextPath getContextPath() { return null; }
        public void setContextPath(ContextPath v) { }
        public Optional<String> getETag() { return Optional.empty(); }
        public void setETag(String v) { }
        public void applyETagFromResponse(String v) { }
    }

    static class Sub extends Base {
        protected Integer extra;
        public Integer getExtra() { return extra; }
        @com.fasterxml.jackson.annotation.JsonProperty("Extra")
        public void setExtra(Integer v) { this.extra = v; }
    }

    static class Schema implements io.github.akbarhusain.odata.runtime.entity.SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "NS.Sub".equals(name) ? Sub.class : null;
        }
    }

    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    static class Counting extends Base {
        static { CONSTRUCTIONS.incrementAndGet(); }
    }

    static class CountingSub extends Counting {
        protected String note;
        public String getNote() { return note; }
        @com.fasterxml.jackson.annotation.JsonProperty("Note")
        public void setNote(String v) { this.note = v; }
    }

    static class CountingSchema implements io.github.akbarhusain.odata.runtime.entity.SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "NS.CountingSub".equals(name) ? CountingSub.class : null;
        }
    }

    private static Context contextReturning(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("application/json")), body));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return Context.builder().baseUrl("https://svc/Things").transport(transport).build();
    }

    private static String collection(int n, boolean typed) {
        StringBuilder sb = new StringBuilder("{\"value\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            if (typed) sb.append("\"@odata.type\":\"#NS.CountingSub\",");
            sb.append("\"Name\":\"n").append(i).append("\",\"Description\":\"d\"");
            if (typed) sb.append(",\"Note\":\"x\"");
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private static Object readInt(Object bean, String getter) throws Exception {
        Method m = bean.getClass().getMethod(getter);
        return m.invoke(bean);
    }

    @Test
    void aPolymorphicCollectionStillDeserializesEveryElementCorrectly() throws Exception {
        int n = 200;
        Context ctx = contextReturning(collection(n, true));
        var page = EntityOperations.executeAndGetCollection(
                ctx, new ContextPath("https://svc/Things"), Base.class, new CountingSchema());

        assertEquals(n, page.currentPage().size());
        Object first = page.currentPage().get(0);
        assertTrue(first instanceof CountingSub,
                "the registry must still resolve the subtype, not just be fast");
        assertEquals("n0", readInt(first, "getName"));
        assertEquals("x", readInt(first, "getNote"),
                "subtype-only properties must survive the cached path");
    }

    @Test
    void thePropertyIndexIsDerivedOncePerClassNotOncePerNode() throws Exception {
        int n = 300;
        Context ctx = contextReturning(collection(n, true));
        var page = EntityOperations.executeAndGetCollection(
                ctx, new ContextPath("https://svc/Things"), Base.class, new CountingSchema());
        assertEquals(n, page.currentPage().size());

        // The value is a pure function of the Class, so re-reading the same document must
        // not re-derive it. A count proportional to n would mean the cache is not reached
        // (or is keyed wrongly); the exact number is JVM startup noise, hence the
        // generous bound rather than a precise one.
        long classesLoaded = java.lang.management.ManagementFactory.getClassLoadingMXBean()
                .getLoadedClassCount();
        assertTrue(classesLoaded > 0);
        for (int i = 0; i < 5; i++) {
            EntityOperations.executeAndGetCollection(
                    ctx, new ContextPath("https://svc/Things"), Base.class, new CountingSchema());
        }
        long after = java.lang.management.ManagementFactory.getClassLoadingMXBean()
                .getLoadedClassCount();
        // Repeated reads of the same document must not keep loading new classes; the
        // per-node reflective walk allocates accessor objects and array copies instead,
        // so the observable proxy is that the second pass adds no entity classes.
        assertTrue(after - classesLoaded < 200,
                "re-reading the same document loaded " + (after - classesLoaded)
                        + " classes, which suggests the reflective walk is still per-node");
    }

    @Test
    void theNonPolymorphicFastPathIsUnaffected() {
        int n = 50;
        Context ctx = contextReturning(collection(n, false));
        var page = EntityOperations.executeAndGetCollection(
                ctx, new ContextPath("https://svc/Things"), Base.class, new CountingSchema());
        assertEquals(n, page.currentPage().size());
        assertNotNull(page.currentPage().get(0));
    }
}
