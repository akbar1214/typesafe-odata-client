package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryApiConformanceTest {

    private enum Color { Red }

    @Test
    void dateTimePropertyAcceptsOptionalSecondsAndValidatesRanges() {
        DateTimeProperty<Object> value = new DateTimeProperty<>("At", Object.class);
        assertEquals("At eq 2024-01-01T10:15Z", value.equalTo("2024-01-01T10:15Z").toODataExpression());
        assertThrows(IllegalArgumentException.class, () -> value.equalTo("2023-02-29"));
        assertThrows(IllegalArgumentException.class, () -> value.equalTo("2024-01-01T25:15Z"));
    }

    @Test
    void temporalFunctionsRejectIncompatibleEdmTypes() {
        DateTimeProperty<Object> date = new DateTimeProperty<>("Day", Object.class, "Edm.Date");
        DateTimeProperty<Object> dateTime = new DateTimeProperty<>("At", Object.class, "Edm.DateTimeOffset");
        assertThrows(IllegalStateException.class, date::date);
        assertThrows(IllegalStateException.class, date::hour);
        assertEquals("date(At)", dateTime.date().toODataExpression());
        assertEquals("time(At)", dateTime.time().toODataExpression());
        assertThrows(IllegalArgumentException.class, () -> dateTime.equalTo(java.time.LocalTime.NOON));
    }

    @Test
    void temporalFunctionsFollowTypedEdmSignatures() {
        DateTimeProperty<Object> date = new DateTimeProperty<>("Day", Object.class, "Edm.Date");
        DateTimeProperty<Object> time = new DateTimeProperty<>("Clock", Object.class, "Edm.TimeOfDay");
        DateTimeProperty<Object> dateTime =
                new DateTimeProperty<>("At", Object.class, "Edm.DateTimeOffset");
        DateTimeProperty<Object> duration =
                new DateTimeProperty<>("Elapsed", Object.class, "Edm.Duration");

        assertEquals("year(Day)", date.year().toODataExpression());
        assertEquals("month(Day)", date.month().toODataExpression());
        assertEquals("day(Day)", date.day().toODataExpression());
        assertEquals("hour(Clock)", time.hour().toODataExpression());
        assertEquals("minute(Clock)", time.minute().toODataExpression());
        assertEquals("second(Clock)", time.second().toODataExpression());
        assertEquals("fractionalseconds(Clock)", time.fractionalSeconds().toODataExpression());
        assertEquals("totaloffsetminutes(At)", dateTime.totalOffsetMinutes().toODataExpression());
        assertEquals("totalseconds(Elapsed)", duration.totalSeconds().toODataExpression());
        assertThrows(IllegalStateException.class, date::hour);
        assertThrows(IllegalStateException.class, time::year);
        assertThrows(IllegalStateException.class, date::fractionalSeconds);
        assertThrows(IllegalStateException.class, duration::totalOffsetMinutes);
    }

    @Test
    void integerPropertyRejectsRoundingFunctions() {
        NumberProperty<Object, Integer> count = new NumberProperty<>("Count", Object.class, "Edm.Int32");
        NumberProperty<Object, Float> single = new NumberProperty<>("Ratio", Object.class, "Edm.Single");
        assertThrows(IllegalArgumentException.class, count::ceiling);
        assertThrows(IllegalArgumentException.class, count::floor);
        assertThrows(IllegalArgumentException.class, count::round);
        assertThrows(IllegalArgumentException.class, single::ceiling);
        assertThrows(IllegalArgumentException.class, single::floor);
        assertThrows(IllegalArgumentException.class, single::round);
    }

    @Test
    void substringRejectsNegativeLength() {
        StringProperty<Object> name = new StringProperty<>("Name", Object.class);
        assertThrows(IllegalArgumentException.class, () -> name.substring(0, -1));
        assertThrows(IllegalArgumentException.class, () -> name.substring(-1));
    }

    @Test
    void collectionContainsFormatsDurationAndRejectsBinaryMembers() {
        CollectionProperty<Object, Duration, CollectionProperty.FilterableElement<Duration>, ?> durations =
                new CollectionProperty<>("Durations", Object.class, Duration.class,
                        CollectionProperty.FilterableElement::new);
        assertEquals("Durations/any(x: x eq duration'PT1H')", durations.contains(Duration.ofHours(1)).toODataExpression());
        CollectionProperty<Object, byte[], CollectionProperty.FilterableElement<byte[]>, ?> binaries =
                new CollectionProperty<>("Blobs", Object.class, byte[].class,
                        CollectionProperty.FilterableElement::new);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> binaries.contains(new byte[]{1, 2}));
        assertTrue(error.getMessage().contains("Edm.Binary"));
    }

    @Test
    void collectionContainsRejectsDeclaredBinaryElementTypeEvenForStringValues() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>, Object> binaries =
                new CollectionProperty<>("Blobs", Object.class, String.class,
                        CollectionProperty.FilterableElement::new, null, "Edm.Binary");
        assertThrows(IllegalArgumentException.class, () -> binaries.contains("AQI="));
    }

    @Test
    void collectionContainsEscapesStringMembers() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>, ?> values =
                new CollectionProperty<>("Values", Object.class, String.class,
                        CollectionProperty.FilterableElement::new);
        assertEquals("Values/any(x: x eq 'O''Brien')", values.contains("O'Brien").toODataExpression());
        assertEquals("Values/any(x: x eq 'a&b')", values.contains("a&b").toODataExpression());
    }

    @Test
    void collectionLambdaRejectsNonODataAlias() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>, ?> values =
                new CollectionProperty<>("Values", Object.class, String.class,
                        () -> new CollectionProperty.FilterableElement<>("x$y"));
        assertThrows(IllegalArgumentException.class, () -> values.any(x -> x.stringField("Name").equalTo("a")));
    }

    @Test
    void collectionLambdaRejectsPredicateWithoutBoundVariable() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>, ?> values =
                new CollectionProperty<>("Values", Object.class, String.class,
                        CollectionProperty.FilterableElement::new);
        FilterExpression<String> constant = FilterExpression.of("Name eq 'a'");
        assertThrows(IllegalArgumentException.class, () -> values.any(x -> constant));
    }

    @Test
    void navQueryAsPreservesOptions() {
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavQuery<Object, Object, ?> query = NavQuery.of("Versions")
                .select(name).top(5).as("Version", Object.class);
        assertEquals("Versions/Version($select=Name;$top=5)", query.toODataExpand());
        assertThrows(IllegalArgumentException.class, () -> query.as("../Admin", Object.class));
        assertThrows(IllegalArgumentException.class, () -> query.as("1Type", Object.class));
    }

    @Test
    void rawOptionMergingIgnoresParenthesesInsideLiterals() {
        NavQuery<Object, Object, Object> query = NavQuery.<Object, Object, Object>raw(
                "A($filter=Name eq 'x(y)' and Duration eq duration'PT1H')").top(1);
        assertEquals("A($filter=Name eq 'x(y)' and Duration eq duration'PT1H';$top=1)",
                query.toODataExpand());
    }

    @Test
    void duplicateNestedExpandsAreDeduplicated() {
        NavQuery<Object, Object, Object> nested = NavQuery.of("Flights");
        NavQuery<Object, Object, Object> query = NavQuery.<Object, Object, Object>of("Trips")
                .expand(nested).expand(NavQuery.of("Flights"));
        assertEquals("Trips($expand=Flights)", query.toODataExpand());
    }

    @Test
    void collectionBuilderDeduplicatesNestedExpands() {
        NavCollectionProperty<Object, Object, Object, Object> nested =
                new NavCollectionProperty<>("Flights", Object.class, Object.class,
                        () -> new Object(), () -> new Object());
        CollectionProperty<Object, Object, Object, Object> structural =
                new CollectionProperty<>("Values", Object.class, Object.class);
        assertEquals("Values($expand=Flights)",
                structural.expand(nested).expand(nested).toODataExpand());
    }

    @Test
    void applyBuilderRejectsEmptyTransformations() {
        assertThrows(IllegalArgumentException.class, () -> ApplyExpression.builder().groupBy(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ApplyExpression.builder().filter(" "));
        assertThrows(IllegalArgumentException.class, () -> ApplyExpression.builder().aggregate(new String[0]));
        assertEquals("", ApplyExpression.builder().toODataApply());
    }

    @Test
    void applyBuilderHasExplicitNoArgGroupByMethod() {
        assertDoesNotThrow(() -> ApplyBuilder.class.getMethod("groupBy"));
        assertThrows(IllegalArgumentException.class, () -> ApplyExpression.builder().groupBy());
    }

    @Test
    void applyBuilderRenderIsAtomicUnderConcurrentMutation() throws Exception {
        ApplyBuilder builder = ApplyExpression.builder();
        builder.top(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < 7; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        String rendered = builder.toODataApply();
                        if (!rendered.equals("top(1)") && !rendered.equals("top(1)/skip(1)")) {
                            throw new AssertionError(rendered);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
        // One thread mutates DURING the render race so torn snapshots would be observed.
        pool.submit(() -> {
            try {
                start.await();
                builder.skip(1);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(failure.get(), () -> "concurrent render failure: " + failure.get());
    }

    @Test
    void publicQueryFactoriesRejectNullOrBlankRequiredValues() {
        assertThrows(IllegalArgumentException.class, () -> NavQuery.of(" "));
        assertThrows(IllegalArgumentException.class, () -> new CollectionProperty<>(" ", Object.class));
        assertThrows(IllegalArgumentException.class, () -> FilterExpression.of(" "));
        assertThrows(IllegalArgumentException.class, () -> new StringProperty<>(" ", Object.class));
        assertThrows(IllegalArgumentException.class, () -> new DateTimeProperty<>(" ", Object.class));
        assertThrows(IllegalArgumentException.class, () -> new NumberExpression<>(" ", Object.class));
        assertThrows(IllegalArgumentException.class, () -> new NumberExpression<>("Value", Object.class, " "));
        assertThrows(IllegalArgumentException.class, () -> new DateTimeProperty<>("Value", Object.class, " "));
        assertThrows(IllegalArgumentException.class, () -> new CollectionProperty<>("Value", Object.class,
                Object.class, null, null, " "));
    }

    @Test
    void nullOrderingMethodsAreNotPartOfTheODataOrderByApi() {
        assertFalse(Arrays.stream(OrderExpression.class.getMethods())
                .anyMatch(method -> method.getName().equals("nullsFirst") || method.getName().equals("nullsLast")));
    }
}
