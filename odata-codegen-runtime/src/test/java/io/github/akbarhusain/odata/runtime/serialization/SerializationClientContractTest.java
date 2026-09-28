package io.github.akbarhusain.odata.runtime.serialization;

import io.github.akbarhusain.odata.runtime.exception.ODataError;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SerializationClientContractTest {

    @Test
    void dynamicPropertyConverterDefinesNullValueAndNullTypeBehavior() {
        assertNull(DynamicPropertyConverter.convert(null, String.class));
        assertThrows(NullPointerException.class,
                () -> DynamicPropertyConverter.convert("value", null));
    }

    @Test
    void innerErrorRetainsNestedJsonStructure() {
        String body = "{\"error\":{\"code\":\"outer\",\"message\":\"failed\","
                + "\"innererror\":{\"type\":\"service\",\"cause\":{\"code\":17,"
                + "\"items\":[{\"name\":\"first\"},{\"name\":\"second\"}]}}}}";
        ODataError error = ODataError.fromResponse(
                new HttpResponse(500, Map.of(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertNotNull(error);
        Object rawInner = error.getDetails().get("innererror");
        assertInstanceOf(Map.class, rawInner);
        Map<?, ?> inner = (Map<?, ?>) rawInner;
        assertEquals("service", inner.get("type"));
        Object rawCause = inner.get("cause");
        assertInstanceOf(Map.class, rawCause);
        Object rawItems = ((Map<?, ?>) rawCause).get("items");
        assertInstanceOf(List.class, rawItems);
        List<?> items = (List<?>) rawItems;
        assertEquals("first", ((Map<?, ?>) items.get(0)).get("name"));
    }
}
