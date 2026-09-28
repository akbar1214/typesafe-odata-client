package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnumNumericValidationTest {

    @Test
    void fractionalAndOverflowNumericJsonValuesAreRejected(@TempDir Path tempDir) throws Exception {
        CsdlModel.EnumTypeModel type = new CsdlModel.EnumTypeModel("E", "Edm.Int64", false,
                List.of(new CsdlModel.EnumMemberModel("Zero", 0),
                        new CsdlModel.EnumMemberModel("Max", Long.MAX_VALUE)));
        Path root = tempDir.resolve("out");
        Path source = root.resolve("com/example/enums/E.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, new EnumGenerator("com.example").generate(type));
        assertNull(CompilationHarness.compileAll(root));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{root.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> enumClass = Class.forName("com.example.enums.E", true, loader);
            var fromJson = enumClass.getMethod("fromJson", Object.class);
            InvocationTargetException fractional = assertThrows(InvocationTargetException.class,
                    () -> fromJson.invoke(null, 1.5d));
            assertTrue(fractional.getCause() instanceof IllegalArgumentException);
            InvocationTargetException overflow = assertThrows(InvocationTargetException.class,
                    () -> fromJson.invoke(null, new java.math.BigInteger("9223372036854775808")));
            assertTrue(overflow.getCause() instanceof IllegalArgumentException);
        }
    }
}
