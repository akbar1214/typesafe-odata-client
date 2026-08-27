package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end referee for unbound function overloads (the shape TripPin lacks): OData
 * identifies an unbound overload by its parameter names, so {@code IsSiteAdmin(username)}
 * and {@code IsSiteAdmin(userId)} must each generate their own request class + container
 * accessor, the whole client must compile, and bound same-name siblings must not be
 * mistaken for ambiguity. Previously any same-name pair aborted generation with
 * "Ambiguous operation reference".
 */
class OperationImportsOverloadCompilationTest {

    private static final String METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Person">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                <Property Name="Name" Type="Edm.String"/>
              </EntityType>
              <Function Name="IsSiteAdmin">
                <Parameter Name="username" Type="Edm.String" Nullable="false"/>
                <ReturnType Type="Edm.Boolean" Nullable="false"/>
              </Function>
              <Function Name="IsSiteAdmin">
                <Parameter Name="userId" Type="Edm.String" Nullable="false"/>
                <ReturnType Type="Edm.Boolean" Nullable="false"/>
              </Function>
              <Function Name="GetStuff">
                <Parameter Name="a" Type="Edm.Int32" Nullable="false"/>
                <Parameter Name="b" Type="Edm.String" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Function Name="GetStuff">
                <Parameter Name="c" Type="Edm.Double" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Function Name="Foo" IsBound="true">
                <Parameter Name="p" Type="NS.Person" Nullable="false"/>
                <ReturnType Type="Edm.Boolean" Nullable="false"/>
              </Function>
              <Function Name="Foo">
                <Parameter Name="s" Type="Edm.String" Nullable="false"/>
                <ReturnType Type="Edm.Boolean" Nullable="false"/>
              </Function>
              <Action Name="Zap" IsBound="true">
                <Parameter Name="p" Type="NS.Person" Nullable="false"/>
              </Action>
              <Action Name="Zap"/>
              <EntityContainer Name="DefaultContainer">
                <EntitySet Name="People" EntityType="NS.Person"/>
                <FunctionImport Name="IsSiteAdmin" Function="NS.IsSiteAdmin"/>
                <FunctionImport Name="getStuff" Function="NS.GetStuff"/>
                <FunctionImport Name="foo" Function="NS.Foo"/>
                <ActionImport Name="zap" Action="NS.Zap"/>
              </EntityContainer>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    @Test
    void overloadedFunctionImportsGenerateAndCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel model = new StaxCsdlParser().parse(
                new java.io.ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)));
        new Generator(tempDir, Map.of("NS", "com.example.ops"), "com.example.ops").generate(model);

        // one request class per overload, named by the overload's parameter names
        String byUsername = Files.readString(tempDir.resolve(
                "com/example/ops/operation/IsSiteAdminByUsernameFunctionRequest.java"));
        assertTrue(byUsername.contains(
                "public IsSiteAdminByUsernameFunctionRequest(Context context, String username)"));
        assertTrue(byUsername.contains("\"username=\" + OperationPath.parameter(username, \"Edm.String\")"));
        assertFalse(byUsername.contains("userId"));
        String byUserId = Files.readString(tempDir.resolve(
                "com/example/ops/operation/IsSiteAdminByUserIdFunctionRequest.java"));
        assertTrue(byUserId.contains("\"userId=\" + OperationPath.parameter(userId, \"Edm.String\")"));

        // multi-parameter overloads join all parameter names in the suffix
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/ops/operation/GetStuffByAAndBFunctionRequest.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/ops/operation/GetStuffByCFunctionRequest.java")));

        // bound same-name siblings resolve to the unbound overload, unsuffixed
        String foo = Files.readString(tempDir.resolve(
                "com/example/ops/operation/FooFunctionRequest.java"));
        assertTrue(foo.contains("public FooFunctionRequest(Context context, String s)"));
        String zap = Files.readString(tempDir.resolve(
                "com/example/ops/operation/ZapActionRequest.java"));
        assertTrue(zap.contains("public ZapActionRequest(Context context)"));

        // the container exposes one accessor per overload, delegating to the right class
        String container = Files.readString(tempDir.resolve(
                "com/example/ops/container/DefaultContainer.java"));
        assertTrue(container.contains(
                "public IsSiteAdminByUsernameFunctionRequest isSiteAdminByUsername(String username)"));
        assertTrue(container.contains(
                "public IsSiteAdminByUserIdFunctionRequest isSiteAdminByUserId(String userId)"));
        assertTrue(container.contains("getStuffByAAndB(int a, String b)"));
        assertTrue(container.contains("getStuffByC(double c)"));

        // ... and the whole client — overloads included — compiles against the runtime
        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        }
        assertFalse(javaFiles.isEmpty());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter compilerOutput = new StringWriter();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));
        List<String> options = List.of(
                "-d", tempDir.resolve("classes").toString(),
                "-classpath", classpath.stream().map(File::getAbsolutePath)
                        .reduce((a, b) -> a + File.pathSeparator + b).orElse("")
        );
        JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(compilerOutput), fileManager, null, options, null, units);
        assertTrue(task.call(), "Client with overloaded function imports must compile. Errors:\n"
                + compilerOutput);
    }

    private List<File> findClasspathJars() {
        Path mavenRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        List<String> artifactIds = List.of(
                "odata-codegen-runtime",
                "jackson-databind",
                "jackson-core",
                "jackson-annotations",
                "jackson-datatype-jdk8",
                "jackson-datatype-jsr310",
                "jackson-module-parameter-names",
                "slf4j-api"
        );
        List<File> classpath = new ArrayList<>();
        for (String id : artifactIds) {
            Path jar = findJar(mavenRepo, id);
            if (jar != null) {
                classpath.add(jar.toFile());
            }
        }
        // current reactor runtime FIRST — the ~/.m2 snapshot may predate new runtime types
        Path siblingClasses = Path.of("..", "odata-codegen-runtime", "target", "classes");
        if (Files.isReadable(siblingClasses)) {
            classpath.add(0, siblingClasses.toFile());
        }
        return classpath;
    }

    private Path findJar(Path mavenRepo, String artifactId) {
        try (Stream<Path> paths = Files.walk(mavenRepo)) {
            return paths
                    .filter(p -> p.getFileName().toString().contains(artifactId))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources"))
                    .filter(p -> !p.toString().contains("-javadoc"))
                    .filter(p -> p.toString().contains("0.1.0-SNAPSHOT")
                            || !artifactId.equals("odata-codegen-runtime"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
