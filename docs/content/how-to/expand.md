# Expand Navigation Properties

Include related entities in your query results with `$expand`.

## Basic Expand

### Expand a Navigation Property

```java
client.people()
    .expand(Person.TRIPS)
    .get();
```

Each `Person` will have its `trips` field populated with the expanded `Trip`
entities.

### Expand Multiple Properties

```java
client.people()
    .expand(Person.TRIPS, Person.PHOTOS)
    .get();
```

## Nested Expand Options (NavQuery)

A navigation property exposes `select`, `filter`, `orderBy`, and `top` methods
that return a `NavQuery<T>`. Pass the `NavQuery` to `expand(...)` to nest those
options inside the `$expand` clause — equivalent to OData's
`$expand=Trips($select=...;$filter=...;$top=...;$orderby=...)`.

```java
client.people()
    .expand(Person.TRIPS
        .select(Trip.TRIP_ID, Trip.BUDGET)
        .filter(Trip.BUDGET.greaterThan(500.0f))
        .orderBy(Trip.STARTS_AT.desc())
        .top(5))
    .get();
```

This produces (roughly):

```text
$expand=Trips($select=TripId,Budget;$filter=Budget gt 500.0;$orderby=StartsAt desc;$top=5)
```

### Select within Expand

```java
client.people()
    .expand(Person.TRIPS.select(Trip.TRIP_ID, Trip.BUDGET))
    .get();
```

### Filter within Expand

```java
client.people()
    .expand(Person.TRIPS.filter(Trip.BUDGET.greaterThan(500.0f)))
    .get();
```

Only trips with budget > 500 are included in the expansion.

### Order within Expand

```java
client.people()
    .expand(Person.TRIPS.orderBy(Trip.STARTS_AT.desc()))
    .get();
```

### Combine Options

`NavQuery` methods chain, so you can combine `select`, `filter`, `orderBy`, and
`top` freely:

```java
client.people()
    .expand(Person.TRIPS
        .filter(Trip.BUDGET.greaterThan(500.0f))
        .select(Trip.TRIP_ID, Trip.BUDGET)
        .orderBy(Trip.STARTS_AT.desc())
        .top(5))
    .get();
```

## Expand on Entity Requests

```java
// Expand when getting a single entity
Person scott = client.peopleByUserName("scottketchum")
    .expand(Person.TRIPS)
    .get();
```

## What's Next

- [Use Pagination](pagination.md) — Handle large result sets
- [Perform CRUD Operations](crud.md) — Create, update, delete
- [Filter with Type-Safe Expressions](../how-to/filter.md) — The `$filter` expression API
