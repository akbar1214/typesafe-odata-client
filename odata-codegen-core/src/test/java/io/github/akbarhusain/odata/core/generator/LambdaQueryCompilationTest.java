package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile referee for the lambda query API (lesson 120: compile-or-it-didn't-happen).
 * The full TripPin client regenerated with the lambda emission must accept every
 * selector-lambda shape at FULL depth — bare navs, chained options, hop-2+ expands,
 * and 3-arg {@code as()} casts — inside one hand-written usage file compiled together
 * with the generated sources.
 */
class LambdaQueryCompilationTest {

    @Test
    void fullDepthLambdaChainsCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel model;
        try (InputStream is = LambdaQueryCompilationTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        new Generator(tempDir,
                Map.of("Microsoft.OData.SampleService.Models.TripPin", "com.example.trippin"),
                "com.example.trippin").generate(model);

        Files.writeString(tempDir.resolve("LambdaQueries.java"), """
                import com.example.trippin.container.DefaultContainer;
                import com.example.trippin.entity.Flight;
                import com.example.trippin.entity.Person;
                import com.example.trippin.entity.Trip;
                import io.github.akbarhusain.odata.runtime.entity.Context;
                import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

                public class LambdaQueries {
                    public static void run(Context ctx) {
                        DefaultContainer client = new DefaultContainer(ctx);

                        // select / orderBy / filter lambdas mirror the constant forms
                        client.people().select(p -> p.FIRST_NAME, p -> p.LAST_NAME).top(5).get();
                        client.people().orderBy(p -> p.USER_NAME.asc()).get();
                        client.people().filter(p -> p.FIRST_NAME.equalTo("Scott")).get();

                        // expand lambdas: bare navs, chained options, multi-hop (full depth)
                        client.people().expand(p -> p.PHOTO).get();
                        client.people().expand(p -> p.TRIPS).get();
                        client.people().expand(p -> p.TRIPS.select(t -> t.NAME)).get();
                        client.people().expand(p -> p.TRIPS
                                .select(t -> t.NAME)
                                .filter(t -> t.BUDGET.greaterThan(500f))
                                .orderBy(t -> t.NAME.desc())
                                .top(2)
                                .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID))).get();

                        // mixes with the constant forms freely
                        client.people()
                                .expand(Person.TRIPS.select(Trip.NAME))
                                .expand(p -> p.FRIENDS)
                                .filter(f -> f.USER_NAME.equalTo("scottketchum"))
                                .get();

                        // casts: 3-arg as() on the hop-2 nav and the generated cast
                        // constant inside a lambda (PlanItem casts to Flight)
                        client.people().expand(p -> p.TRIPS.expand(t -> t.PLAN_ITEMS
                                .as("Microsoft.OData.SampleService.Models.TripPin.Flight", Flight.class, Flight.Selector::new)
                                .select(f -> f.FLIGHT_NUMBER))).get();
                        client.people().expand(p -> p.TRIPS.expand(t -> t.PLAN_ITEMS_AS_FLIGHT)).get();

                        // keyed entity requests: select + expand lambdas
                        client.people("russellwhyte").select(p -> p.FIRST_NAME).get();
                        client.people("russellwhyte").expand(p -> p.TRIPS.select(t -> t.NAME)).get();

                        // selectors also drive collection lambdas (any/all) via Filterable
                        client.people().filter(p -> p.TRIPS.any(t -> t.BUDGET.greaterThan(100f))).get();
                    }

                    static CollectionPage<Person> page(Context ctx) {
                        return new DefaultContainer(ctx).people().expand(p -> p.TRIPS).get();
                    }
                }
                """);

        String errors = CompilationHarness.compileAll(tempDir);
        assertNull(errors, "full-depth lambda chains must compile against the regenerated "
                + "client. Errors:\n" + errors);
    }
}
