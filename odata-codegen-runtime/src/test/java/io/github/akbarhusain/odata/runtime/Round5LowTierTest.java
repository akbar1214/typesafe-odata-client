package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import io.github.akbarhusain.odata.runtime.query.NumberExpression;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-5 L-tier fixes:
 * L1 addRef NPEs opaquely on a null target URL,
 * L2 $search terms with control characters silently produce broken URLs,
 * L3 OData canonical functions ceiling/floor/round missing from NumberExpression,
 * L4 CollectionPage.spliterator() lacks SIZED though the size is known.
 */
class Round5LowTierTest {

    // ---------- L1 ----------

    @Test
    void l1_addRefRejectsNullTargetUrlWithClearMessage() {
        Context ctx = Context.builder().baseUrl("http://example.com")
                .authProvider(AuthProvider.none()).build();
        var path = ctx.basePath().addSegment("People").addKey("UserName", "a", "Edm.String").addSegment("Friends");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.addRef(ctx, path, null));
        assertTrue(ex.getMessage().contains("targetEntityUrl"),
                "the message must name the offending parameter, got: " + ex.getMessage());
    }

    @Test
    void l1_addRefRejectsEmptyTargetUrl() {
        Context ctx = Context.builder().baseUrl("http://example.com")
                .authProvider(AuthProvider.none()).build();
        var path = ctx.basePath().addSegment("People").addKey("UserName", "a", "Edm.String").addSegment("Friends");
        assertThrows(IllegalArgumentException.class, () -> EntityOperations.addRef(ctx, path, " "));
    }

    // ---------- L2 ----------

    @Test
    void l2_searchTermRejectsControlCharacters() {
        ContextPath path = new ContextPath("http://example.com");
        assertThrows(IllegalArgumentException.class,
                () -> path.addQuery("$search", "abc\u0000def"));
        assertThrows(IllegalArgumentException.class,
                () -> path.addQuery("$search", "line\nbreak"));
    }

    @Test
    void l2_normalSearchGrammarStillAllowed() {
        ContextPath path = new ContextPath("http://example.com");
        String url = path.addQuery("$search", "\"blue OR green\" AND NOT clothing").toUrl();
        assertTrue(url.contains("$search="), url);
        assertFalse(url.contains("\n") || url.contains("\u0000"), url);
    }

    @Test
    void l2_controlCharsRejectedOnlyForSearchOption() {
        // Other query options keep their semantics — no new validation there
        ContextPath path = new ContextPath("http://example.com");
        assertDoesNotThrow(() -> path.addQuery("$custom", "has\ttab"));
    }

    // ---------- L3 ----------

    @Test
    void l3_ceilingFloorRoundRenderCanonicalFunctions() {
        NumberExpression<Integer, Object> price = new NumberExpression<>("Price", Object.class);
        assertEquals("(ceiling(Price))", price.ceiling().toODataExpression());
        assertEquals("(floor(Price))", price.floor().toODataExpression());
        assertEquals("(round(Price))", price.round().toODataExpression());
    }

    @Test
    void l3_canonicalFunctionsCompose() {
        NumberExpression<Integer, Object> price = new NumberExpression<>("Price", Object.class);
        assertEquals("(ceiling(Price)) gt 10",
                price.ceiling().greaterThan(10).toODataExpression());
    }

    // ---------- L4 ----------

    @Test
    void l4_spliteratorReportsSized() {
        CollectionPage<String> page = new CollectionPage<>(List.of("a", "b"), null);
        long expected = page.currentPage().size();
        assertEquals(expected, page.spliterator().estimateSize(), "size is known exactly");
        assertTrue((page.spliterator().characteristics() & java.util.Spliterator.SIZED) != 0,
                "spliterator must report SIZED — the backing list is immutable and sized");
    }
}
