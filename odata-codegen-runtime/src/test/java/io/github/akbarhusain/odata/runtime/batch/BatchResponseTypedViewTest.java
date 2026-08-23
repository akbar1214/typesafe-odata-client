package io.github.akbarhusain.odata.runtime.batch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2: typed views of batch results must preserve the part-level Content-ID.
 * get(index, type)/getAll(type) rebuilt BatchResult via the 4-arg constructor,
 * silently nulling contentId — after getByContentId("1") you could no longer take a
 * typed view without losing the correlation (lesson 97).
 */
class BatchResponseTypedViewTest {

    private static BatchResult<Object> result(int status, String contentId) {
        return new BatchResult<>(status, Map.of(), "{\"x\":1}".getBytes(), Object.class, contentId);
    }

    @Test
    void getWithTypePreservesContentId() {
        BatchResponse response = new BatchResponse(List.of(
                result(201, "1"),
                result(200, "2")));
        BatchResult<String> typed = response.get(0, String.class);
        assertEquals("1", typed.contentId(),
                "typed view must carry the part-level Content-ID for correlation");
        assertEquals(201, typed.statusCode());
    }

    @Test
    void getTypeOverloadPreservesContentId() {
        BatchResponse response = new BatchResponse(List.of(result(200, "7")));
        BatchResult<String> typed = response.get(0, (java.lang.reflect.Type) String.class);
        assertEquals("7", typed.contentId());
    }

    @Test
    void getAllPreservesContentIds() {
        BatchResponse response = new BatchResponse(List.of(
                result(201, "a"),
                result(404, "b")));
        List<BatchResult<String>> all = response.getAll(String.class);
        assertEquals("a", all.get(0).contentId());
        assertEquals("b", all.get(1).contentId());
    }

    @Test
    void correlatedLookupThenTypedViewRoundTrip() {
        BatchResponse response = new BatchResponse(List.of(result(204, "5")));
        BatchResult<?> raw = response.getByContentId("5");
        assertNotNull(raw);
        BatchResult<String> typed = response.get(response.size() - 1, String.class);
        assertEquals(raw.contentId(), typed.contentId(),
                "correlation info must survive the raw → typed view transition");
    }
}
