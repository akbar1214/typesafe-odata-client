package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OData JSON Format §7.1: Edm.DateTimeOffset, Edm.Date, Edm.TimeOfDay and Edm.Duration
 * values are ISO 8601 STRINGS on the wire. Jackson's JavaTimeModule defaults to numeric
 * timestamps (epoch seconds / [y,m,d] arrays / decimal seconds), which every OData
 * service rejects — and adjusts read offsets to UTC, losing the service's zone.
 */
class JacksonSerializerTemporalTest {

    public static class Bean {
        @JsonProperty("StartsAt")
        public OffsetDateTime startsAt;
        @JsonProperty("Day")
        public LocalDate day;
        @JsonProperty("At")
        public LocalTime at;
        @JsonProperty("Span")
        public Duration span;
    }

    private static String json(Bean bean) {
        return new String(new JacksonSerializer().serialize(bean, Bean.class), StandardCharsets.UTF_8);
    }

    @Test
    void dateTimeOffsetSerializesAsIso8601StringWithOffset() {
        Bean bean = new Bean();
        bean.startsAt = OffsetDateTime.of(2014, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30));

        assertEquals("{\"StartsAt\":\"2014-01-05T10:15:30+05:30\"}", json(bean),
                "Edm.DateTimeOffset must be an ISO 8601 string, never epoch seconds");
    }

    @Test
    void utcDateTimeOffsetUsesZ() {
        Bean bean = new Bean();
        bean.startsAt = OffsetDateTime.of(2014, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        assertEquals("{\"StartsAt\":\"2014-01-01T00:00:00Z\"}", json(bean));
    }

    @Test
    void dateTimeOfDayAndDurationSerializeAsIsoStrings() {
        Bean bean = new Bean();
        bean.day = LocalDate.of(2014, 2, 3);
        bean.at = LocalTime.of(9, 5, 7);
        bean.span = Duration.ofHours(1).plusMinutes(30);

        assertEquals("{\"Day\":\"2014-02-03\",\"At\":\"09:05:07\",\"Span\":\"PT1H30M\"}", json(bean),
                "Edm.Date / Edm.TimeOfDay / Edm.Duration are ISO strings, not arrays or seconds");
    }

    @Test
    void readingKeepsTheServiceOffsetInsteadOfAdjustingToUtc() {
        Bean bean = new JacksonSerializer().deserialize(
                "{\"StartsAt\":\"2014-01-05T10:15:30+05:30\"}".getBytes(StandardCharsets.UTF_8), Bean.class);

        assertEquals(OffsetDateTime.of(2014, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                bean.startsAt, "the offset the service sent must survive the read");
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), bean.startsAt.getOffset());
    }

    @Test
    void partialPatchBodyAlsoUsesIsoStrings() {
        Bean bean = new Bean();
        bean.startsAt = OffsetDateTime.of(2014, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        bean.day = LocalDate.of(2014, 2, 3);

        String body = new String(new JacksonSerializer().serialize(bean, Bean.class, java.util.Set.of("StartsAt")),
                StandardCharsets.UTF_8);

        assertEquals("{\"StartsAt\":\"2014-01-01T00:00:00Z\"}", body);
    }
}
