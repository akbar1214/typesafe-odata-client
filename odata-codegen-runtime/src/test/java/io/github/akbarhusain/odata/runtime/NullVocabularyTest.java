package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch B5): every public entry must fail fast with a named parameter
 * instead of rendering a "null" literal, returning "null" bytes, or throwing a
 * bare message-less NPE pages later.
 */
class NullVocabularyTest {

    private ContextPath people() {
        return new ContextPath("https://example.com").addSegment("People");
    }

    @Test
    void addKeyNullValueThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> people().addKey("UserName", null),
                "null key values must not render People(null)/'null'");
        assertTrue(ex.getMessage().contains("UserName"), ex::getMessage);
    }

    @Test
    void addKeyNullNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> people().addKey(null, "x"));
    }

    @Test
    void typedAddKeyNullValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> people().addKey("UserName", null, "Edm.String"));
    }

    @Test
    void addQueryNullNameAndValueThrow() {
        assertThrows(IllegalArgumentException.class, () -> people().addQuery(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> people().addQuery("$top", null));
    }

    @Test
    void serializeNullThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JacksonSerializer().serialize(null, Map.class),
                "null entity must fail fast instead of POSTing a 'null' body");
        assertNotNull(ex.getMessage());
    }

    @Test
    void serializeNullWithIncludeFieldsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new JacksonSerializer().serialize(null, Map.class, java.util.Set.of("A")));
    }

    @Test
    void collectionPageNullThrowsWithMessage() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new CollectionPage<>(null, null));
        assertEquals("currentPage must not be null", ex.getMessage());
    }

    @Test
    void batchOperationFactoriesValidate() {
        assertThrows(NullPointerException.class, () -> BatchOperation.delete(null));
        assertThrows(NullPointerException.class,
                () -> BatchOperation.get("https://example.com/People", null));
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> BatchOperation.get("https://example.com/People", null));
        assertEquals("headers must not be null", ex.getMessage());
    }

    @Test
    void contextBuilderNullsFailFast() {
        String base = "https://example.com";
        assertThrows(NullPointerException.class,
                () -> Context.builder().baseUrl(base).serializer(null).build());
        assertThrows(NullPointerException.class,
                () -> Context.builder().baseUrl(base).transport(null).build());
        assertThrows(NullPointerException.class,
                () -> Context.builder().baseUrl(base).authProvider(null).build());
        assertThrows(NullPointerException.class,
                () -> Context.builder().baseUrl(base).interceptors(null));
    }
}
