package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1: chained top-level {@code filter()} calls are ANDed by joining rendered predicates.
 * A predicate containing {@code or} must be parenthesized before the join, or
 * {@code filter(A.or(B)).filter(C)} renders {@code A or B and C} — which parses as
 * {@code A or (B and C)} and silently changes query semantics. NavQuery already
 * parenthesizes (lesson 101); the collection request must match.
 */
class RequestGeneratorFilterParenTest {

    private String generatePeopleCollectionRequest() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person"))
                .findFirst()
                .orElseThrow();
        return new RequestGenerator("com.example.trippin").generateCollectionRequest(person, schema);
    }

    @Test
    void chainedFiltersAreParenthesizedBeforeAndJoin() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains(".map(f -> \"(\" + f + \")\")"),
                "buildContext must wrap each chained filter predicate in parentheses "
                        + "so 'or' inside one predicate cannot bind across the implicit 'and'");
    }

    @Test
    void singleFilterIsNotNeedlesslyParenthesized() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertTrue(code.contains("filters.size() == 1"),
                "a single filter should render verbatim (matches NavQuery.toODataExpand)");
    }

    @Test
    void rawJoinWithoutParensIsGone() throws Exception {
        String code = generatePeopleCollectionRequest();
        assertFalse(code.contains("String.join(\" and \", filters)"),
                "the unparenthesized join must not survive — it produced wrong OData for "
                        + "filter(A.or(B)).filter(C)");
    }
}
