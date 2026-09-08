package io.github.akbarhusain.odata.runtime.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Action bodies and structured function-parameter aliases go through the shared
 * mapper, not the entity Serializer — they must render temporal values as the ISO 8601
 * strings the OData JSON format requires (not Jackson's numeric timestamps).
 */
class EntityOperationsTemporalParameterTest {

    @Test
    void actionBodyRendersTemporalParametersAsIsoStrings() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startDate", LocalDate.of(2014, 1, 1));
        params.put("at", OffsetDateTime.of(2014, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30)));

        String body = new String(EntityOperations.buildActionBody(params), StandardCharsets.UTF_8);

        assertEquals("{\"startDate\":\"2014-01-01\",\"at\":\"2014-01-05T10:15:30+05:30\"}", body);
    }

    @Test
    void structuredParameterAliasRendersTemporalValuesAsIsoStrings() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("Day", LocalDate.of(2014, 1, 1));

        assertEquals("{\"Day\":\"2014-01-01\"}", EntityOperations.jsonParameter(value));
    }
}
