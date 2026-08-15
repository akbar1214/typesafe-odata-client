package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M9 (extended): typed key literals per the OData ABNF — no value-shape heuristics.
 */
class TypedKeyFormatTest {

    private static final String BASE = "https://services.odata.org/V4";

    enum Color { Red }

    @Test
    void stringKeysAreAlwaysQuotedEvenWhenUuidShaped() {
        String url = new ContextPath(BASE).addSegment("Things")
                .addKey("Id", "0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001", "Edm.String").toUrl();
        assertEquals(BASE + "/Things('0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001')", url,
                "the heuristic previously sent Edm.String keys unquoted when UUID-shaped");
    }

    @Test
    void guidKeysAreUnquoted() {
        String url = new ContextPath(BASE).addSegment("Things")
                .addKey("Id", "0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001", "Edm.Guid").toUrl();
        assertEquals(BASE + "/Things(0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001)", url);
    }

    @Test
    void dateTimeKeysRenderBareIsoLiterals() {
        assertEquals(BASE + "/Things(2024-01-01)",
                new ContextPath(BASE).addSegment("Things")
                        .addKey("Day", LocalDate.of(2024, 1, 1), "Edm.Date").toUrl());
        assertEquals(BASE + "/Things(2024-01-01T10:15:30Z)",
                new ContextPath(BASE).addSegment("Things")
                        .addKey("At", OffsetDateTime.parse("2024-01-01T10:15:30Z"), "Edm.DateTimeOffset").toUrl());
    }

    @Test
    void timeOfDayRendersWithSecondsAndDurationPrefixed() {
        assertEquals(BASE + "/Things(10:15:00)",
                new ContextPath(BASE).addSegment("Things")
                        .addKey("T", LocalTime.of(10, 15), "Edm.TimeOfDay").toUrl(),
                "LocalTime.toString omits zero seconds — invalid OData");
        assertEquals(BASE + "/Things(duration'PT2H')",
                new ContextPath(BASE).addSegment("Things")
                        .addKey("D", Duration.ofHours(2), "Edm.Duration").toUrl());
    }

    @Test
    void enumKeysRenderQualifiedQuoted() {
        String url = new ContextPath(BASE).addSegment("Things")
                .addKey("Color", Color.Red, "Ns.Color").toUrl();
        assertEquals(BASE + "/Things(Ns.Color'Red')", url);
    }
}
