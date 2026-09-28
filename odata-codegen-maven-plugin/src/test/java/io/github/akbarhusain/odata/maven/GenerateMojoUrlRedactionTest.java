package io.github.akbarhusain.odata.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configured {@code metadataUrl} can carry userinfo and a bearer token in its query, and
 * a redirect {@code Location} is commonly a pre-signed URL whose query <em>is</em> the
 * credential ({@code sig=}, {@code se=}, {@code sp=}). {@code URI.create} embeds the
 * offending string verbatim in its exception message, so retaining that exception as the
 * cause of a MojoFailureException put the secret into the cause chain Maven prints with
 * {@code -e} / {@code -X}.
 *
 * <p>Every other log path in this class redacts (see {@code redactUri} /
 * {@code redactUrlText}), which is exactly why the existing redaction test passed while
 * this hole survived it.
 */
class GenerateMojoUrlRedactionTest {

    /** The whole rendered chain is what Maven prints, so the whole chain must be clean. */
    private static String renderedChain(Throwable failure) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Test
    void malformedRedirectLocationDoesNotLeakItsQueryString() {
        // A space makes URI.create fail; the message would otherwise carry the whole URL.
        MojoFailureException failure = assertThrows(MojoFailureException.class,
                () -> GenerateMojo.resolveRedirectUri(URI.create("https://svc.example/$metadata"),
                        "https://other.example/$metadata?sig=SUPERSECRETSIG bad"));

        String rendered = renderedChain(failure);
        assertFalse(rendered.contains("SUPERSECRETSIG"),
                "the redirect Location must not reach the exception chain:\n" + rendered);
        assertTrue(rendered.contains("Invalid redirect URL"), rendered);
    }

    @Test
    void malformedMetadataUrlDoesNotLeakUserinfoOrQueryToken() throws Exception {
        // Same shape via the configured metadataUrl, which is user-supplied config.
        // validateHttpUri is private, so invoke it reflectively and read the chain off
        // whatever comes back — the point of the assertion is the CHAIN, not its type.
        var method = GenerateMojo.class.getDeclaredMethod("validateHttpUri", String.class);
        method.setAccessible(true);
        Throwable failure;
        try {
            method.invoke(null, "https://user:S3CRET-PASSWORD@svc.example/$metadata"
                    + "?access_token=TOPSECRETTOKEN bad");
            throw new AssertionError("expected the malformed URL to be rejected");
        } catch (java.lang.reflect.InvocationTargetException e) {
            failure = e.getCause();
        }

        assertTrue(failure instanceof MojoFailureException,
                "expected a MojoFailureException, got " + failure.getClass().getName());
        String rendered = renderedChain(failure);
        assertFalse(rendered.contains("S3CRET-PASSWORD"),
                "userinfo must not reach the exception chain:\n" + rendered);
        assertFalse(rendered.contains("TOPSECRETTOKEN"),
                "the query token must not reach the exception chain:\n" + rendered);
        assertTrue(rendered.contains("Invalid metadata URL"), rendered);
    }
}
