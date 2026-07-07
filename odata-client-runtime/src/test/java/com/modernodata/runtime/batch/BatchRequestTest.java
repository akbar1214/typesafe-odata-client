package com.modernodata.runtime.batch;

import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchRequestTest {

    @Test
    void createEmptyBatch() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch();
        assertTrue(batch.isEmpty());
        assertEquals(0, batch.size());
    }

    @Test
    void addOperationIncreasesSize() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch()
                .add(BatchOperation.get("People('scott')"));

        assertEquals(1, batch.size());
        assertFalse(batch.isEmpty());
    }

    @Test
    void addMultipleOperations() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch()
                .add(BatchOperation.get("People('scott')"))
                .add(BatchOperation.get("People('keith')"))
                .add(BatchOperation.delete("People('louis')"));

        assertEquals(3, batch.size());
    }

    @Test
    void fluentApiReturnsSameInstance() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch();
        BatchRequest returned = batch.add(BatchOperation.get("People('scott')"));
        assertSame(batch, returned);
    }

    @Test
    void contextPathRelativeUrl() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addKey("UserName", "scott")
                .addSegment("Trips");

        String relative = path.toRelativeUrl();
        assertEquals("People('scott')/Trips", relative);
    }

    @Test
    void contextPathRelativeUrlWithQuery() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addQuery("$top", "5");

        String relative = path.toRelativeUrl();
        assertEquals("People?$top=5", relative);
    }
}
