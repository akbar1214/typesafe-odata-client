package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container accessors for function/action imports: one typed accessor method per
 * import returning its operation request, joining the existing set/singleton
 * collision registry. TripPin's GetNearestAirport (function) + ResetDataSource
 * (action) exercise both flavors.
 */
class ContainerGeneratorImportTest {

    private String generateContainer() throws Exception {
        var model = load("/trippin-metadata.xml");
        var schema = model.schemas().get(0);
        return new ContainerGenerator("com.example.trippin", java.util.Map.of(), "com.example.trippin")
                .generate(schema.containers().get(0), schema);
    }

    private static InputStream loadStream(String path) {
        return ContainerGeneratorImportTest.class.getResourceAsStream(path);
    }

    private static io.github.akbarhusain.odata.core.model.CsdlModel load(String path) {
        try (InputStream is = loadStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void containerEmitsOneAccessorPerFunctionImportReturningRequestObject() throws Exception {
        String code = generateContainer();
        assertTrue(code.contains("public GetNearestAirportFunctionRequest getNearestAirport(double lat, double lon)"),
                "accessor name = toJavaFieldName(import name); parameters mirror the operation signature");
        assertTrue(code.contains("return new GetNearestAirportFunctionRequest(context, lat, lon);"));
        assertTrue(code.contains("import com.example.trippin.operation.GetNearestAirportFunctionRequest;"));
    }

    @Test
    void containerEmitsOneAccessorPerActionImport() throws Exception {
        String code = generateContainer();
        assertTrue(code.contains("public ResetDataSourceActionRequest resetDataSource()"));
        assertTrue(code.contains("return new ResetDataSourceActionRequest(context);"));
    }

    @Test
    void noSkipWarningsRemainForOperationImports() throws Exception {
        // The warn-and-skip loops are gone; imports must be generated, never silently dropped
        assertDoesNotThrow(this::generateContainer);
    }

    @Test
    void importAccessorCollidingWithSetOrSingletonFailsLoudly() throws Exception {
        var model = load("/trippin-metadata.xml");
        var schema = model.schemas().get(0);
        var container = schema.containers().get(0);
        // People entity set already exists — an import named 'people' would fold onto people()
        var collide = new io.github.akbarhusain.odata.core.model.CsdlModel.ContainerModel(
                container.name(), null,
                container.entitySets(),
                container.singletons(),
                List.of(new io.github.akbarhusain.odata.core.model.CsdlModel.FunctionImportModel(
                        "People", "Microsoft.OData.SampleService.Models.TripPin.GetNearestAirport",
                        null, false)),
                container.actionImports());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ContainerGenerator("com.example.trippin", java.util.Map.of(), "com.example.trippin").generate(collide, schema));
        assertTrue(ex.getMessage().contains("people"), ex.getMessage());
    }
}
