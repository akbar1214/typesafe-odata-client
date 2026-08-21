package io.github.akbarhusain.odata.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M12: Temp metadata deleteOnExit leak
 * M13 is in GeneratorMediumTest
 */
class GenerateMojoMediumTest {

    private String extractDownloadMetadataMethod() throws Exception {
        Path projectMojo = Path.of("/Users/akbarhusain/projects/modern-odata-client/odata-codegen-maven-plugin/src/main/java/io/github/akbarhusain/odata/maven/GenerateMojo.java");
        String code = Files.readString(projectMojo);
        int start = code.indexOf("private Path downloadMetadata");
        if (start < 0) start = code.indexOf("downloadMetadata");
        int end = code.indexOf("\n    static URI resolveRedirectUri", start);
        if (end < 0) end = code.indexOf("resolveRedirectUri", start);
        if (end < 0) end = Math.min(code.length(), start + 3000);
        return code.substring(start, end);
    }

    @Test
    void m12_tempFileShouldBeDeletedNotJustDeleteOnExit(@TempDir Path tmp) throws Exception {
        String methodCode = extractDownloadMetadataMethod();
        // Should not rely solely on deleteOnExit; should have explicit delete handling for tempFile within same method
        boolean hasDeleteOnExit = methodCode.contains("deleteOnExit");
        boolean hasExplicitDelete = methodCode.contains("deleteIfExists") || methodCode.contains("Files.delete(") || methodCode.contains("try") && methodCode.contains("delete");
        // Before fix: hasDeleteOnExit true, hasExplicitDelete false -> fail
        assertTrue(hasExplicitDelete || !hasDeleteOnExit,
                "M12: downloadMetadata should explicitly delete temp file, not just deleteOnExit. Method snippet:\n" + methodCode.substring(0, Math.min(methodCode.length(), 800)));
    }

    @Test
    void m12_deleteOnExitShouldNotBeOnlyCleanup() throws Exception {
        String methodCode = extractDownloadMetadataMethod();
        int deleteOnExitCount = methodCode.split("deleteOnExit", -1).length - 1;
        boolean hasExplicitInMethod = methodCode.contains("deleteIfExists") || methodCode.contains("Files.delete");
        // Before fix: deleteOnExit=1, explicit=0 -> fail
        assertTrue(hasExplicitInMethod || deleteOnExitCount == 0,
                "M12: downloadMetadata should have explicit delete handling within method, deleteOnExit=" + deleteOnExitCount + " hasExplicit=" + hasExplicitInMethod + "\n" + methodCode.substring(0, Math.min(methodCode.length(), 500)));
    }
}
