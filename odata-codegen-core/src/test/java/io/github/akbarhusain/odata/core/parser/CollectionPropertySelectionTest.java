package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Collection-valued structural properties are legal {@code $select} items — the OData ABNF
 * lists {@code primitiveColProperty} and {@code complexColProperty} as {@code selectProperty}
 * alternatives — but they generated a {@code CollectionProperty} constant that implemented no
 * query capability, so {@code select(Person.TAGS)} did not compile.
 *
 * <p>The fix is a select-only subclass. The discriminator is STRUCTURAL vs NAVIGATION, not
 * collection-ness, and it is evidence-based: the live TripPin service answers
 * {@code ?$select=Emails} with an {@code Emails} array but answers {@code ?$select=Trips} and
 * {@code ?$select=Photo} with {@code 200} and the navigation property absent from the payload.
 * Selecting a navigation therefore succeeds and returns nothing, so it must stay a compile
 * error — that is what {@code expand(...)} is for.
 *
 * <p>Judged by javac: the fix references a runtime type the generated code must resolve, so a
 * content assertion alone would pass even if that type did not exist.
 */
class CollectionPropertySelectionTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static String metadata() {
        return EDMX_HEAD
                + EDM + " Namespace=\"Sel.T\">"
                + "<ComplexType Name=\"Address\">"
                + "<Property Name=\"Street\" Type=\"Edm.String\"/>"
                + "</ComplexType>"
                + "<EntityType Name=\"Person\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Name\" Type=\"Edm.String\"/>"
                + "<Property Name=\"Tags\" Type=\"Collection(Edm.String)\"/>"
                + "<Property Name=\"Addresses\" Type=\"Collection(Sel.T.Address)\"/>"
                + "<Property Name=\"Streams\" Type=\"Collection(Edm.Stream)\"/>"
                + "<NavigationProperty Name=\"Friends\" Type=\"Collection(Sel.T.Person)\"/>"
                + "<NavigationProperty Name=\"BestFriend\" Type=\"Sel.T.Person\"/>"
                + "</EntityType>"
                + "<EntityContainer Name=\"Container\">"
                + "<EntitySet Name=\"People\" EntityType=\"Sel.T.Person\"/>"
                + "</EntityContainer></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
    }

    private static String generate(Path out) throws Exception {
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, metadata(), StandardCharsets.UTF_8);
        CsdlModel model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.sel").generate(model);
        return Files.readString(out.resolve("com/sel/entity/Person.java"));
    }

    /**
     * EVERY declaration line for the constant — both the {@code static final} constant and the
     * {@code Selector} field. Checking only the first would pass even if the other had become
     * selectable, which is precisely the regression this guards.
     */
    private static List<String> declarationLines(String source, String constantName) {
        List<String> lines = source.lines()
                .filter(l -> l.contains(" " + constantName + " ="))
                .toList();
        assertTrue(!lines.isEmpty(), "no declaration found for " + constantName);
        return lines;
    }

    private static void assertNoSelectableDeclaration(String source, String constantName, String why) {
        for (String line : declarationLines(source, constantName)) {
            assertFalse(line.contains("SelectableCollectionProperty"),
                    constantName + " must not be selectable: " + why + " -- but found: " + line.strip());
        }
    }

    private static String compileUsage(Path out, String className, String body) throws Exception {
        Files.writeString(out.resolve(className + ".java"), """
                import com.sel.container.Container;
                import com.sel.entity.Person;
                import io.github.akbarhusain.odata.runtime.entity.Context;

                public class %s {
                    public static void main(String[] args) {
                        Context ctx = Context.builder().baseUrl("https://example.org").build();
                        Container client = new Container(ctx);
                %s
                    }
                }
                """.formatted(className, body));
        return CompilationHarness.compileAll(out);
    }

    @Test
    void primitiveCollectionPropertyIsSelectable(@TempDir Path out) throws Exception {
        String source = generate(out);
        assertTrue(source.contains(
                        "public static final SelectableCollectionProperty<Person, String, "
                                + "CollectionProperty.FilterableElement<String>, ?> TAGS = "
                                + "new SelectableCollectionProperty<>(\"Tags\", Person.class, String.class, "
                                + "CollectionProperty.FilterableElement::new, null, \"Edm.String\");"),
                "a Collection(Edm.String) property must be selectable, naming the CSDL wire "
                        + "path 'Tags' in its constructor");
        assertTrue(source.contains(
                        "public final SelectableCollectionProperty<Person, String, "
                                + "CollectionProperty.FilterableElement<String>, ?> TAGS = Person.TAGS;"),
                "the Selector field must carry the same type, or $select lambdas cannot name it");
    }

    @Test
    void complexCollectionPropertyIsSelectable(@TempDir Path out) throws Exception {
        String source = generate(out);
        assertTrue(source.contains(
                        "public static final SelectableCollectionProperty<Person, Address, "
                                + "Address.Filterable, ?> ADDRESSES = "
                                + "new SelectableCollectionProperty<>(\"Addresses\", Person.class, Address.class, "
                                + "Address.Filterable::new, null, null);"),
                "a Collection(Complex) property must be selectable too — the ABNF lists "
                        + "complexColProperty alongside primitiveColProperty");
        assertTrue(source.contains(
                        "public final SelectableCollectionProperty<Person, Address, Address.Filterable, ?> "
                                + "ADDRESSES = Person.ADDRESSES;"),
                "the Selector field must carry the same type");
    }

    @Test
    void streamCollectionPropertyStaysUnselectable(@TempDir Path out) throws Exception {
        // Edm.Stream is excluded even as a collection: selectProperty has a distinct
        // streamProperty form that it deliberately omits, and there is no streamColProperty.
        // Same rule as the single-valued exclusion, via the same shared check.
        String source = generate(out);
        assertNoSelectableDeclaration(source, "STREAMS", "the ABNF has no selectProperty form for a stream");
        assertTrue(declarationLines(source, "STREAMS").get(0).contains("CollectionProperty<Person, Object,"),
                "a stream collection keeps the non-selectable base descriptor: "
                        + declarationLines(source, "STREAMS").get(0).strip());
    }

    @Test
    void navigationPropertiesStayUnselectable(@TempDir Path out) throws Exception {
        // The deliberate exclusion (API policy: $select on a navigation is grammar-legal but
        // returns 200 with the navigation absent, so it would fail silently).
        String source = generate(out);
        assertNoSelectableDeclaration(source, "FRIENDS", "a navigation belongs in expand(...)");
        assertNoSelectableDeclaration(source, "BEST_FRIEND", "a navigation belongs in expand(...)");
        for (String line : declarationLines(source, "FRIENDS")) {
            assertTrue(line.contains("NavCollectionProperty"),
                    "collection navigations keep NavCollectionProperty: " + line.strip());
        }
        for (String line : declarationLines(source, "BEST_FRIEND")) {
            assertTrue(line.contains("NavQuery"), "single-valued navigations keep NavQuery: " + line.strip());
        }
        assertFalse(source.contains("SelectableCollectionProperty<Person, Person,"),
                "neither navigation may become selectable — selectability must not be hoisted "
                        + "onto CollectionProperty, or NavCollectionProperty would inherit it");
    }

    @Test
    void filterableCollectionFieldsKeepTheBaseType(@TempDir Path out) throws Exception {
        // Filterable exists for any()/all() lambdas, where a collection operand is compared,
        // never selected — and its names carry the `x/` lambda prefix, which must never reach
        // $select (NavQuery.selectableName rejects `(` but not `x/`).
        // Brace-balanced slice rather than a fixed window, so the assertion cannot silently
        // stop matching after a formatting change.
        String source = generate(out);
        String block = extractClassBody(source, "class Filterable {");
        assertTrue(block.contains("\"x/Tags\""),
                "sanity: Filterable still carries the lambda-prefixed collection field");
        assertFalse(block.contains("SelectableCollectionProperty"),
                "Filterable serves any()/all() and must not gain select-only fields");
    }

    @Test
    void selectingACollectionPropertyCompiles(@TempDir Path out) throws Exception {
        generate(out);
        String errors = compileUsage(out, "GoodQuery", """
                        client.people().select(Person.TAGS);
                        client.people().select(Person.ADDRESSES);
                        client.people().select(Person.NAME, Person.TAGS);
                        client.people().select(s -> s.TAGS);
                        client.people().select(s -> s.ADDRESSES);
                        // and the realistic nested form: People('x')?$expand=Friends($select=Tags)
                        client.people().expand(p -> p.FRIENDS.select(t -> t.TAGS));
                """);
        if (errors != null && !errors.isBlank()) {
            fail("select() on a collection property must compile, but javac reported:\n" + errors);
        }
    }

    @Test
    void selectingACollectionNavigationFailsToCompile(@TempDir Path out) throws Exception {
        generate(out);
        String errors = compileUsage(out, "BadQuery1", "        client.people().select(Person.FRIENDS);");
        assertNotNull(errors, "selecting a collection NAVIGATION must not compile");
        assertTrue(errors.contains("SelectableExpression"),
                "the error should show select() takes the select-only capability. Output:\n" + errors);
    }

    @Test
    void selectingASingleValuedNavigationFailsToCompile(@TempDir Path out) throws Exception {
        generate(out);
        String errors = compileUsage(out, "BadQuery2", "        client.people().select(Person.BEST_FRIEND);");
        assertNotNull(errors, "selecting a single-valued NAVIGATION must not compile");
        assertTrue(errors.contains("SelectableExpression"),
                "the error should show select() takes the select-only capability. Output:\n" + errors);
    }

    @Test
    void orderingACollectionPropertyFailsToCompile(@TempDir Path out) throws Exception {
        // The runtime type test asserts SelectableCollectionProperty is not an OrderExpression,
        // but that pins the IMPLEMENTATION. This pins the CONTRACT: if orderBy(...) were ever
        // widened to accept select-only descriptors, TAGS would start rendering
        // $orderby=Tags — invalid, since a collection has no primitive result value to sort on
        // (OData v4.01 Part 1 s11.2.6.2).
        generate(out);
        String errors = compileUsage(out, "BadQuery3", "        client.people().orderBy(Person.TAGS);");
        assertNotNull(errors, "ordering a collection property must not compile");
        assertTrue(errors.contains("OrderExpression"),
                "the error should show orderBy() takes an OrderExpression. Output:\n" + errors);
    }

    @Test
    void anEntityTypeNamedSelectableCollectionPropertyIsRenamedAway(@TempDir Path out) throws Exception {
        // Generated code reaches the descriptor through the wildcard query import, so a type
        // sharing its simple name would hijack the reference in every same-package file.
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"Clash\">"
                + "<EntityType Name=\"SelectableCollectionProperty\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "</EntityType>"
                + "<EntityType Name=\"Holder\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Values\" Type=\"Collection(Edm.String)\"/>"
                + "<NavigationProperty Name=\"T\" Type=\"Clash.SelectableCollectionProperty\"/>"
                + "</EntityType>"
                + "<EntityContainer Name=\"Container\">"
                + "<EntitySet Name=\"Holders\" EntityType=\"Clash.Holder\"/>"
                + "</EntityContainer></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
        Path file = out.resolve("m.xml");
        Files.writeString(file, doc, StandardCharsets.UTF_8);
        CsdlModel model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.clash").generate(model);

        assertTrue(Files.exists(out.resolve("com/clash/entity/SelectableCollectionProperty_.java")),
                "the generated class must be renamed away from the runtime descriptor's simple name");
        String errors = CompilationHarness.compileAll(out);
        if (errors != null && !errors.isBlank()) {
            fail("renamed-clash client must compile, but javac reported:\n" + errors);
        }
    }

    private static String extractClassBody(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start > 0, "expected to find '" + marker + "'");
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i);
            }
        }
        throw new AssertionError("unbalanced braces after '" + marker + "'");
    }
}
