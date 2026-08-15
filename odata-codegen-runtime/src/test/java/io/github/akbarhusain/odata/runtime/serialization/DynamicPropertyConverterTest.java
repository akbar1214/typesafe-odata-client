package io.github.akbarhusain.odata.runtime.serialization;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicPropertyConverterTest {

    public static class Target {
        private String known;

        public String getKnown() { return known; }
        public void setKnown(String known) { this.known = known; }
    }

    /**
     * M16: dynamic properties are converted to caller-supplied POJOs that almost never
     * declare every key the server sent — the converter must ignore unknown keys like
     * every other mapper in the library, not throw UnrecognizedPropertyException.
     */
    @Test
    void m16IgnoresUnknownPropertiesWhenConverting() {
        Map<String, Object> dynamic = Map.of("known", "x", "extraKey", 42, "another", true);

        Target target = DynamicPropertyConverter.convert(dynamic, Target.class);

        assertEquals("x", target.getKnown());
    }
}
