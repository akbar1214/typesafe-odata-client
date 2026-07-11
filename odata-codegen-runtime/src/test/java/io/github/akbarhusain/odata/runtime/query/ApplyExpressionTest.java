package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplyExpressionTest {

    @Test
    void rawEscapeHatchReturnsVerbatim() {
        assertEquals("groupby((Category))/aggregate(Price with sum as Total)",
                ApplyExpression.of("groupby((Category))/aggregate(Price with sum as Total)").toODataApply());
    }

    @Test
    void groupByThenAggregate() {
        String apply = ApplyExpression.builder()
                .groupBy("Category")
                .aggregate("Price with sum as Total")
                .toODataApply();
        assertEquals("groupby((Category))/aggregate(Price with sum as Total)", apply);
    }

    @Test
    void groupByWithMultipleProperties() {
        String apply = ApplyExpression.builder()
                .groupBy("Category", "Supplier")
                .toODataApply();
        assertEquals("groupby((Category, Supplier))", apply);
    }

    @Test
    void computeTransformation() {
        String apply = ApplyExpression.builder()
                .compute("Price mul 2 as DoublePrice", "Price add 1 as AdjustedPrice")
                .toODataApply();
        assertEquals("compute(Price mul 2 as DoublePrice, Price add 1 as AdjustedPrice)", apply);
    }

    @Test
    void filterThenGroupByThenCompute() {
        String apply = ApplyExpression.builder()
                .filter("Price gt 10")
                .groupBy("Category")
                .compute("Price mul 2 as DoublePrice")
                .toODataApply();
        assertEquals("filter(Price gt 10)/groupby((Category))/compute(Price mul 2 as DoublePrice)", apply);
    }

    @Test
    void typedFilterUsesODataExpression() {
        String apply = ApplyExpression.builder()
                .filter(FilterExpression.of("Price gt 10"))
                .toODataApply();
        assertEquals("filter(Price gt 10)", apply);
    }

    @Test
    void topSkipOrderBy() {
        String apply = ApplyExpression.builder()
                .top(5)
                .skip(10)
                .orderBy("Name desc")
                .toODataApply();
        assertEquals("top(5)/skip(10)/orderby(Name desc)", apply);
    }

    @Test
    void emptyBuilderRendersEmptyString() {
        assertEquals("", ApplyExpression.builder().toODataApply());
    }
}
