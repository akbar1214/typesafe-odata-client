# Aggregate with `$apply` (and `$search`)

OData v4 exposes server-side aggregation and transformations through the `$apply`
system query option. `$compute` is **not** a standalone option — it is a
transformation *inside* `$apply`. Both are surfaced on the generated collection
request via `apply(...)`, and free-text `$search` via `search(...)`.

## Free-Text Search (`$search`)

```java
CollectionPage<Product> hits = client.products()
    .search("bread")
    .get();
```

## Build an `$apply` Pipeline

Use the fluent `ApplyExpression.builder()`. Each call appends a transformation;
they are rendered slash-separated in the order added.

```java
import io.github.akbarhusain.odata.runtime.query.ApplyExpression;

// groupby((Category))/aggregate(Price with sum as Total)
ApplyExpression agg = ApplyExpression.builder()
    .groupBy("Category")
    .aggregate("Price with sum as Total");

CollectionPage<Product> totals = client.products()
    .apply(agg)
    .get();
```

`ApplyBuilder` implements `ApplyExpression`, so you can pass the builder directly:

```java
client.products()
    .apply(ApplyExpression.builder()
        .filter(Product.PRICE.greaterThan(10.0))   // typed FilterExpression
        .groupBy(Product.CATEGORY)                  // typed PropertyExpression
        .aggregate("Price with average as AvgPrice"))
    .get();
```

## Compute Derived Properties (`$compute`)

`compute(...)` adds calculated properties you can then select, filter, or order by:

```java
// compute(Price mul 2 as DoublePrice)
client.products()
    .apply(ApplyExpression.builder()
        .compute("Price mul 2 as DoublePrice"))
    .get();
```

## Builder Transformations

| Method | Emits | Notes |
|--------|-------|-------|
| `filter(String)` / `filter(FilterExpression)` | `filter(...)` | Raw or type-safe predicate |
| `groupBy(String...)` / `groupBy(PropertyExpression...)` | `groupby((a, b))` | Type-safe overload uses `getEdmName()` |
| `aggregate(String...)` | `aggregate(...)` | e.g. `Price with sum as Total` |
| `compute(String...)` | `compute(...)` | `$compute` inside `$apply` |
| `orderBy(String...)` | `orderby(...)` | |
| `top(int)` / `skip(int)` | `top(n)` / `skip(n)` | |

## Raw Escape Hatch

For transformations the builder doesn't model, pass the raw OData string:

```java
client.products()
    .apply("groupby((Category),aggregate(Price with sum as Total))")
    .get();

// or via the interface:
client.products()
    .apply(ApplyExpression.of("aggregate($count as Count)"))
    .get();
```

## What's Next

- [Filter with Type-Safe Expressions](filter.md) — reused inside `$apply` filters
- [Select and Order Results](select-order.md) — `$select`/`$orderby`
