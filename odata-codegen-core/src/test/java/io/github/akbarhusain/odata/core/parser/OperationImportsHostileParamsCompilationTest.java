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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression guard for the parameter shapes TripPin lacks (review round 6,
 * H1/M1): a client whose imports take enum/structured/collection parameters — and
 * return complexes and entity collections — must generate AND compile. The original
 * bugs produced uncompilable output (missing imports, a nonexistent {@code String_}
 * type) that content tests alone missed because TripPin only exercises
 * {@code GetNearestAirport(double, double)}.
 */
class OperationImportsHostileParamsCompilationTest {

    private static final String METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EnumType Name="Color">
                <Member Name="Red" Value="0"/>
                <Member Name="A-B" Value="1"/>
              </EnumType>
              <ComplexType Name="Address">
                <Property Name="Street" Type="Edm.String"/>
              </ComplexType>
              <EntityType Name="Person">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                <Property Name="Name" Type="Edm.String"/>
              </EntityType>
              <Function Name="PickColor">
                <Parameter Name="c" Type="NS.Color" Nullable="false"/>
                <ReturnType Type="Edm.String" Nullable="false"/>
              </Function>
              <Function Name="HomeAddress">
                <Parameter Name="who" Type="Edm.String" Nullable="false"/>
                <ReturnType Type="NS.Address" Nullable="false"/>
              </Function>
              <Function Name="AllPeople">
                <ReturnType Type="Collection(NS.Person)" Nullable="false"/>
              </Function>
              <Function Name="ByTags">
                <Parameter Name="tags" Type="Collection(Edm.String)" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Function Name="ByScores">
                <Parameter Name="min" Type="Edm.Double" Nullable="false"/>
                <Parameter Name="scores" Type="Collection(Edm.Int32)" Nullable="true"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Function Name="NearAddresses">
                <Parameter Name="addr" Type="NS.Address" Nullable="false"/>
                <Parameter Name="alt" Type="NS.Address" Nullable="true"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Function Name="VisitAll">
                <Parameter Name="addrs" Type="Collection(NS.Address)" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Function>
              <Action Name="ShipTo">
                <Parameter Name="addr" Type="NS.Address" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Action>
              <Action Name="AddTags">
                <Parameter Name="tags" Type="Collection(Edm.String)" Nullable="false"/>
                <ReturnType Type="Edm.Int32" Nullable="false"/>
              </Action>
              <Action Name="Rename">
                <Parameter Name="new-name" Type="Edm.String" Nullable="false"/>
              </Action>
              <EntityContainer Name="DefaultContainer">
                <EntitySet Name="People" EntityType="NS.Person"/>
                <FunctionImport Name="pickColor" Function="NS.PickColor"/>
                <FunctionImport Name="homeAddress" Function="NS.HomeAddress"/>
                <FunctionImport Name="allPeople" Function="NS.AllPeople"/>
                <FunctionImport Name="byTags" Function="NS.ByTags"/>
                <FunctionImport Name="byScores" Function="NS.ByScores"/>
                <FunctionImport Name="nearAddresses" Function="NS.NearAddresses"/>
                <FunctionImport Name="visitAll" Function="NS.VisitAll"/>
                <ActionImport Name="shipTo" Action="NS.ShipTo"/>
                <ActionImport Name="addTags" Action="NS.AddTags"/>
                <ActionImport Name="rename" Action="NS.Rename"/>
              </EntityContainer>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    @Test
    void hostileParameterShapesGenerateAndCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel model = new StaxCsdlParser().parse(
                new java.io.ByteArrayInputStream(METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        new Generator(tempDir, Map.of("NS", "com.example.ops"), "com.example.ops").generate(model);

        // content pins for the fixed shapes ...
        String pickColor = Files.readString(tempDir.resolve(
                "com/example/ops/operation/PickColorFunctionRequest.java"));
        assertTrue(pickColor.contains("import com.example.ops.enums.Color;"),
                "enum parameter needs its import: " + pickColor);
        String shipTo = Files.readString(tempDir.resolve(
                "com/example/ops/operation/ShipToActionRequest.java"));
        assertTrue(shipTo.contains("import com.example.ops.complex.Address;"),
                "structured action parameter needs its import: " + shipTo);
        String addTags = Files.readString(tempDir.resolve(
                "com/example/ops/operation/AddTagsActionRequest.java"));
        assertTrue(addTags.contains("Context context, List<String> tags)"),
                "collection action parameter maps to List<String>: " + addTags);
        assertTrue(addTags.contains("__params.put(\"tags\", tags)"));
        String rename = Files.readString(tempDir.resolve(
                "com/example/ops/operation/RenameActionRequest.java"));
        assertTrue(rename.contains("__params.put(\"new-name\", new_name)"),
                "JSON body keys use the CSDL parameter name: " + rename);
        // collection function parameters ride parameter aliases: pair in the segment,
        // value as a query option — never an inline collection literal
        String byTags = Files.readString(tempDir.resolve(
                "com/example/ops/operation/ByTagsFunctionRequest.java"));
        assertTrue(byTags.contains("public ByTagsFunctionRequest(Context context, List<String> tags)"),
                "collection parameter maps to List<element>: " + byTags);
        assertTrue(byTags.contains("__pairs.add(\"tags=@p0\");"));
        assertTrue(byTags.contains(
                "addQuery(\"@p0\", OperationPath.collectionParameter(tags, \"Edm.String\"))"));
        String byScores = Files.readString(tempDir.resolve(
                "com/example/ops/operation/ByScoresFunctionRequest.java"));
        assertTrue(byScores.contains(
                "public ByScoresFunctionRequest(Context context, double min, List<Integer> scores)"));
        assertTrue(byScores.contains("\"min=\" + OperationPath.parameter(min, \"Edm.Double\")"),
                "scalar parameters stay inline next to the alias");
        assertTrue(byScores.contains(
                "if (scores != null) {\n            __path = __path.addQuery(\"@p0\", "
                        + "OperationPath.collectionParameter(scores, \"Edm.Int32\"))"));
        // structured function parameters ride JSON parameter aliases (single + collection)
        String nearAddresses = Files.readString(tempDir.resolve(
                "com/example/ops/operation/NearAddressesFunctionRequest.java"));
        assertTrue(nearAddresses.contains(
                "public NearAddressesFunctionRequest(Context context, Address addr, Address alt)"),
                "structured function parameters map to the complex type: " + nearAddresses);
        assertTrue(nearAddresses.contains("__pairs.add(\"addr=@p0\");"));
        assertTrue(nearAddresses.contains("EntityOperations.jsonParameter(addr)"),
                "the alias value is the serialized JSON of the complex instance");
        assertTrue(nearAddresses.contains(
                "if (alt != null) {\n            __path = __path.addQuery(\"@p1\", EntityOperations.jsonParameter(alt))"),
                "nullable structured parameters omit the pair and alias when null");
        String visitAll = Files.readString(tempDir.resolve(
                "com/example/ops/operation/VisitAllFunctionRequest.java"));
        assertTrue(visitAll.contains(
                "public VisitAllFunctionRequest(Context context, List<Address> addrs)"));
        assertTrue(visitAll.contains("addQuery(\"@p0\", EntityOperations.jsonParameter(addrs))"),
                "a structured collection serializes as one JSON array literal");
        String container = Files.readString(tempDir.resolve(
                "com/example/ops/container/DefaultContainer.java"));
        assertTrue(container.contains("import com.example.ops.enums.Color;"),
                "container accessors need parameter-type imports: " + container);
        assertTrue(container.contains("import com.example.ops.complex.Address;"));
        assertTrue(container.contains("public ByTagsFunctionRequest byTags(List<String> tags)"),
                "container accessors carry collection parameters: " + container);
        assertTrue(container.contains(
                "public NearAddressesFunctionRequest nearAddresses(Address addr, Address alt)"),
                "container accessors carry structured function parameters: " + container);

        // ... and the whole client — operations included — compiles against the runtime
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
        assertTrue(task.call(), "Client with hostile import parameters must compile. Errors:\n"
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
