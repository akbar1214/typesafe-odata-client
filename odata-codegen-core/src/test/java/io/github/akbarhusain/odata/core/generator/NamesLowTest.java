package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1: isReservedWord missing when/non-sealed
 * L2: toConstantName XMLHttp -> XML_HTTP
 */
class NamesLowTest {

    @Test
    void l1_whenIsReservedWord() {
        assertTrue(Names.isJavaKeyword("when"),
                "L1: 'when' should be reserved (Java 17 pattern matching), got false");
    }

    @Test
    void l1_nonSealedIsReserved() {
        assertTrue(Names.isJavaKeyword("non-sealed"),
                "L1: 'non-sealed' should be reserved (Java 17 sealed), got false");
    }

    @Test
    void l2_xmlHttpConstant() {
        String result = Names.toConstantName("XMLHttp");
        assertEquals("XML_HTTP", result,
                "L2: XMLHttp should split acronym boundary -> XML_HTTP, got " + result);
    }

    @Test
    void l2_xmlHttpRequest() {
        String result = Names.toConstantName("XMLHttpRequest");
        assertEquals("XML_HTTP_REQUEST", result,
                "L2: XMLHttpRequest -> XML_HTTP_REQUEST, got " + result);
    }

    @Test
    void l2_simpleCamel() {
        assertEquals("FIRST_NAME", Names.toConstantName("firstName"));
        assertEquals("MY_VALUE", Names.toConstantName("MyValue"));
    }

    @Test
    void l2_alreadyUpper() {
        assertEquals("XML", Names.toConstantName("XML"));
        assertEquals("FOO_BAR", Names.toConstantName("FooBar"));
    }
}
