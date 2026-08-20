package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H10: toPackageName illegal package "3d_model".
 * Names.toPackageName("3D.Model") returns "3d_model" which starts with digit -> invalid Java package.
 * Expected after fix: sanitized to valid package like "_3d_model" or throws.
 * Currently: returns illegal package, test fails.
 */
class NamesToPackageNameTest {

    private static boolean isValidPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.endsWith(".") || pkg.startsWith(".")) return false;
        for (String part : pkg.split("\\.")) {
            if (part.isEmpty()) return false;
            if (!Character.isJavaIdentifierStart(part.charAt(0))) return false;
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) return false;
            }
        }
        return true;
    }

    @Test
    void packageWithLeadingDigitIsSanitized() {
        String pkg = Names.toPackageName("3D.Model");
        assertTrue(isValidPackage(pkg),
                "H10: toPackageName('3D.Model')='" + pkg + "' must be valid Java package (currently '3d_model' illegal)");
        assertFalse(pkg.split("\\.")[0].matches("^[0-9].*"),
                "first segment must not start with digit: " + pkg);
    }

    @Test
    void packageWithHyphenIsSanitized() {
        String pkg = Names.toPackageName("My-Ns.Model");
        assertTrue(isValidPackage(pkg), "hyphen must be sanitized to valid package: " + pkg);
    }

    @Test
    void normalNamespaceStillWorks() {
        assertEquals("com_example_model", Names.toPackageName("Com.Example.Model"));
        assertTrue(isValidPackage(Names.toPackageName("Com.Example.Model")));
    }
}
