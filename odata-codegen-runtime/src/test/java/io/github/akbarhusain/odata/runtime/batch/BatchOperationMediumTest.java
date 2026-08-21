package io.github.akbarhusain.odata.runtime.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9: BatchOperation encoded %0D%0A not rejected (header injection)
 */
class BatchOperationMediumTest {

    @Test
    void m9_encodedCRLFRejected() {
        // BatchOperation currently checks raw \r\n\0 only; %0D%0A passes decoded as CRLF on server
        assertThrows(IllegalArgumentException.class, () ->
                        BatchOperation.get("People%0D%0AInjected: true"),
                "M9: encoded CRLF %0D%0A should be rejected");
    }

    @Test
    void m9_encodedLowerCaseRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                        BatchOperation.get("People%0d%0aInjected"),
                "M9: lower-case %0d%0a should be rejected");
    }

    @Test
    void m9_encodedPercentRejected() {
        // %0 is start of encoded control char; should reject %0 or %0D variants
        assertThrows(IllegalArgumentException.class, () ->
                        BatchOperation.get("People%0A"),
                "M9: %0A should be rejected");
    }

    @Test
    void m9_rawCRLFStillRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                        BatchOperation.get("People\r\nInjected"),
                "raw CRLF should still be rejected");
    }

    @Test
    void m9_normalUrlAllowed() {
        // Normal URL with %20 should be allowed (space)
        assertDoesNotThrow(() -> BatchOperation.get("People('a%20b')"));
        // Normal %2F etc allowed
        assertDoesNotThrow(() -> BatchOperation.get("People('a%2Fb')"));
    }
}
