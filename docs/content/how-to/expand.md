# Expand Navigation Properties

Use `$expand` to request related entity values in the same response. Generated entity-navigation constants are typed: collection navigations are `NavCollectionProperty` values and single navigations are `NavQuery` values. Both implement `Expandable`. Complex-target navigation constants are not emitted.

## Basic Expand

```java
CollectionPage<Person> people = client.people()
    .expand(Person.TRIPS)
    .get();
```

A collection navigation can be expanded without options. The returned `Person` instances expose the expanded values through typed model getters such as `getTrips()`.

Multiple navigations can be expanded together:

```java
CollectionPage<Person> people = client.people()
    .expand(Person.TRIPS, Person.PHOTO)
    .get();
```

## Selector Lambdas

Every request-level read-shaping option has a constant form and a selector-lambda form. A selector is a generated view of the entity's properties, so a property from another entity is a compile error.

```java
CollectionPage<Person> people = client.people()
    .expand(p -> p.TRIPS)
    .expand(p -> p.TRIPS.select(t -> t.NAME).top(2))
    .get();
```

The selector factory is carried by generated navigation constants. The lambda form composes at each navigation depth:

```java
CollectionPage<Person> people = client.people()
    .expand(p -> p.TRIPS
        .select(t -> t.NAME)
        .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID)))
    .get();
```

The resulting `$expand` value is structurally equivalent to:

```text
Trips($select=Name;$expand=PlanItems($select=PlanItemId))
```

## Nested Expand Options

`NavCollectionProperty` and `NavQuery` provide `select`, `filter`, `orderBy`, `top`, `skip`, `count`, and `expand` methods. A navigation constant opens a `NavQuery` when one of these methods is called.

```java
CollectionPage<Person> people = client.people()
    .expand(Person.TRIPS
        .select(Trip.TRIP_ID, Trip.BUDGET)
        .filter(Trip.BUDGET.greaterThan(500.0f))
        .orderBy(Trip.STARTS_AT.desc())
        .top(5)
        .count())
    .get();
```

This renders the equivalent of:

```text
$expand=Trips($select=TripId,Budget;$filter=Budget gt 500.0;$orderby=StartsAt desc;$top=5;$count=true)
```

Multiple calls are immutable operations: each call returns a new `NavQuery` carrying the previous options.

### Select and expand a nested navigation

```java
CollectionPage<Person> people = client.people()
    .expand(Person.TRIPS.expand(Trip.PLAN_ITEMS
        .select(PlanItem.PLAN_ITEM_ID, PlanItem.CONFIRMATION_CODE)))
    .get();
```

A bare `NavCollectionProperty` such as `Trip.PLAN_ITEMS` is an `Expandable`; a `NavQuery` returned by `.select(...)` is also an `Expandable`.

### Raw expand expressions

`NavQuery.raw(...)` is the escape hatch for an expression that the typed builder does not cover:

```java
CollectionPage<Person> people = client.people()
    .expand(NavQuery.raw("Versions/ABC.Doc($expand=abc)"))
    .get();
```

Raw expressions can still be extended with the constant option methods. Existing option groups are merged rather than emitted as a second parenthesized group.

## Polymorphic Expands

When a navigation targets a base entity type, the generator emits a cast constant for each known subtype. The qualified CSDL type name is part of the URL:

```java
CollectionPage<Trip> trips = client.people("scottketchum")
    .trips()
    .expand(Trip.PLAN_ITEMS_AS_FLIGHT
        .select(Flight.FLIGHT_NUMBER))
    .get();
```

The generated constant renders a path such as:

```text
PlanItems/Microsoft.OData.SampleService.Models.TripPin.Flight($select=FlightNumber)
```

The three-argument `as(...)` form can be used when a cast is not represented by a generated constant:

```java
NavQuery<Trip, Flight, Flight.Selector> flights =
    Trip.PLAN_ITEMS.as(
        "Microsoft.OData.SampleService.Models.TripPin.Flight",
        Flight.class,
        Flight.Selector::new);

CollectionPage<Trip> trips = client.people("scottketchum")
    .trips()
    .expand(flights.select(f -> f.FLIGHT_NUMBER))
    .get();
```

The subtype must extend the navigation target type. Nested options are checked against the subtype's selector, while inherited subtype properties remain available through the `? super` bounds.

## Expand on Entity Requests

Keyed container accessors return entity requests, and entity requests support the `select` and `expand` options valid for a single-entity GET:

```java
Person person = client.people("scottketchum")
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .expand(Person.TRIPS)
    .get();
```

Entity requests do not expose collection-only `filter`, `orderBy`, `top`, or `skip` methods.

## Expanded Values in Model Getters

Expanded JSON is materialized into the generated model:

```java
Person person = client.people("scottketchum")
    .expand(Person.TRIPS.expand(Trip.PLAN_ITEMS))
    .get();

List<Trip> trips = person.getTrips();
List<PlanItem> items = trips.get(0).getPlanItems();
```

Collection navigation getters return an unmodifiable, empty-safe `List<T>`. Singleton navigation getters return `Optional<T>`, as in `Location.getAirportRef()`.

## What's Next

- [Use Pagination](pagination.md) — Handle large result sets
- [Perform CRUD Operations](crud.md) — Create, update, and delete entities
- [Filter with Type-Safe Expressions](filter.md) — The filter expression API
