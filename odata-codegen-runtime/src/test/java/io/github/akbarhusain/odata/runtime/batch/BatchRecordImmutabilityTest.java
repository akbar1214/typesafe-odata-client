package io.github.akbarhusain.odata.runtime.batch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L9: BatchOperation/BatchResult are records over byte[]/map components — without
 * explicit equals/hashCode they get identity equality, and without defensive copies
 * callers can mutate their internals.
 */
class BatchRecordImmutabilityTest {

    @Test
    void logicallyIdenticalOperationsAreEqual() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        BatchOperation a = BatchOperation.post("People", body);
        BatchOperation b = BatchOperation.post("People", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertEquals(a, b, "records over byte[] need value-based equals");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void sourceArraysCannotMutateTheOperation() {
        byte[] body = "original".getBytes(StandardCharsets.UTF_8);
        BatchOperation op = BatchOperation.put("X", body);
        body[0] = 'X';

        assertEquals("original", new String(op.body(), StandardCharsets.UTF_8),
                "the record must hold a defensive copy");
    }

    @Test
    void headerMapViewCannotMutateTheOperation() {
        Map<String, List<String>> headers = new HashMap<>();
        List<String> values = new ArrayList<>(List.of("v1"));
        headers.put("X-Custom", values);
        BatchOperation op = BatchOperation.get("People", headers);

        values.add("v2"); // mutate the caller's list after construction
        assertEquals(List.of("v1"), op.headers().get("X-Custom"),
                "header values must be defensively copied");
        assertThrows(UnsupportedOperationException.class,
                () -> op.headers().put("X-New", List.of("v")));
    }

    @Test
    void batchResultBodyAccessorReturnsDefensiveCopy() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        BatchResult<?> result = new BatchResult<>(200, Map.of(), body, Object.class);

        result.body()[0] = 'X';
        assertEquals('a', result.body()[0],
                "mutating the returned array must not affect the record");
    }

    @Test
    void logicallyIdenticalResultsAreEqual() {
        BatchResult<?> a = new BatchResult<>(200, Map.of(), "abc".getBytes(), Object.class, "1");
        BatchResult<?> b = new BatchResult<>(200, Map.of(), "abc".getBytes(), Object.class, "1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("contentId=1"));
    }
}
