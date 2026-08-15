package io.github.akbarhusain.odata.core.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M24: numeric enum payloads must map by CSDL VALUE, not by ordinal (Jackson's default),
 * and member-name strings must keep working. Values 1 and 4 on a 2-member enum make the
 * ordinal mapping observably wrong.
 */
class EnumJsonDeserializationTest {

    private static final String METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="TestNS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EnumType Name="Level">
                <Member Name="Low" Value="1"/>
                <Member Name="High" Value="4"/>
              </EnumType>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    @SuppressWarnings("unchecked")
    @Test
    void numericPayloadsMapByValueNotOrdinal(@TempDir Path tempDir) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel model = parser.parse(new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)));
        var enumType = model.schemas().get(0).enumTypes().get(0);

        String code = new EnumGenerator("com.example.test").generate(enumType);
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonCreator"),
                "generated enums must carry a @JsonCreator mapping numbers by value");

        Path srcDir = tempDir.resolve("src/com/example/test/enums");
        Files.createDirectories(srcDir);
        Path src = srcDir.resolve("Level.java");
        Files.writeString(src, code);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available - run with a JDK");
        List<File> classpath = new ArrayList<>();
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        try (Stream<Path> jars = Files.walk(m2)) {
            jars.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources") && !p.toString().contains("-javadoc"))
                    .map(Path::toFile)
                    .forEach(classpath::add);
        }
        StringWriter out = new StringWriter();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        fm.setLocation(StandardLocation.CLASS_PATH, classpath);
        Path classesOut = tempDir.resolve("classes");
        Files.createDirectories(classesOut);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesOut.toFile()));
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(src.toFile());
        assertTrue(compiler.getTask(new PrintWriter(out), fm, null, null, null, units).call(),
                "generated enum must compile. Errors:\n" + out);

        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classesOut.toUri().toURL()},
                EnumJsonDeserializationTest.class.getClassLoader())) {
            Class<Enum<?>> level = (Class<Enum<?>>) Class.forName("com.example.test.enums.Level", true, loader);
            ObjectMapper mapper = new ObjectMapper();

            // Numeric 4 is ordinal 1 (High is index 1) AND value 4 — ambiguous for that one,
            // so use 1: ordinal 1 would be High, value 1 is Low. Value mapping => Low.
            Enum<?> fromNumber = mapper.readValue("1", level);
            assertEquals("Low", fromNumber.name(),
                    "numeric payloads must map by CSDL value, not ordinal");

            Enum<?> fromNumber4 = mapper.readValue("4", level);
            assertEquals("High", fromNumber4.name());

            // The OData v4 JSON string form keeps working
            Enum<?> fromName = mapper.readValue("\"High\"", level);
            assertEquals("High", fromName.name());
        }
    }
}
