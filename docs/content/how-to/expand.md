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
    .expand(Person.TRIPS, Person.PHOTO)
    .get();
```

## Selector Lambdas

Every `expand` example below has a lambda spelling: apply the lambda to a
`Selector` view of the entity's properties. Constants are the short form; lambdas
give one consistent mental model across `select`, `orderBy`, `expand`, and
`filter`, and compose at unlimited nesting depth:

```java
client.people()
    .expand(p -> p.TRIPS)                                   // bare nav
    .expand(p -> p.TRIPS.select(t -> t.NAME))               // nav + options
    .expand(p -> p.TRIPS
        .select(t -> t.NAME)
        .filter(t -> t.BUDGET.greaterThan(500f))
        .top(2))                                            // chained options
    .get();
```

Nested expands take lambdas at every hop — each hop's selector arrives with the
value, so the chain composes recursively:

```java
client.people()
    .expand(p -> p.TRIPS.select(t -> t.NAME)
        .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID)))
    .get();
// $expand=Trips($select=Name;$expand=PlanItems($select=PlanItemId))
```

Cross-entity mistakes stay compile errors in both spellings: `FLIGHTS` is a `Trip`
constant, not a member of `Person.Selector`, so `expand(p -> p.FLIGHTS)` does not
compile.

## Nested Expand Options (NavQuery)

A navigation property exposes `select`, `filter`, `orderBy`, `top`, `skip`, and `count`
methods that return a `NavQuery<S, T>` (source entity `S`, target entity `T`). Pass the
`NavQuery` to `expand(...)` to nest those options inside the `$expand` clause —
equivalent to OData's
`$expand=Trips($select=...;$filter=...;$orderby=...;$top=...;$skip=...;$count=true)`.

```java
client.people()
    .expand(Person.TRIPS
        .select(Trip.TRIP_ID, Trip.BUDGET)
        .filter(Trip.BUDGET.greaterThan(500.0f))
        .orderBy(Trip.STARTS_AT.desc())
        .top(5)
        .count())          // $count=true: inline count for the expansion
    .get();
```

This produces (roughly):

```text
$expand=Trips($select=TripId,Budget;$filter=Budget gt 500.0;$orderby=StartsAt desc;$top=5)
```

The same chain written with lambdas:

```java
client.people()
    .expand(p -> p.TRIPS
        .select(t -> t.TRIP_ID, t -> t.BUDGET)
        .filter(t -> t.BUDGET.greaterThan(500.0f))
        .orderBy(t -> t.STARTS_AT.desc())
        .top(5)
        .count())
    .get();
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

Keyed accessors return the entity request, which carries the same typed
`expand()`/`select()` options as collection requests:

```java
// Expand when getting a single entity
Person scott = client.people("scottketchum")
    .expand(Person.TRIPS)
    .get();
```

Nested expands compose the same way — the inner `NavQuery` renders the parenthesized
options (`Folders($expand=Files)`), no raw strings needed:

```java
var container = client.containers(id)
    .expand(MyContainer.FOLDERS.expand(MyFolder.FILES))
    .get();
// GET .../Containers(id)?$expand=Folders($expand=Files)

// combine with a select on the outer entity
var brief = client.containers(id)
    .select(MyContainer.NAME)
    .expand(MyContainer.FOLDERS.expand(MyFolder.FILES.select(MyFile.NAME)))
    .get();
// GET .../Containers(id)?$select=Name&$expand=Folders($expand=Files($select=Name))
```

## Deep / Multi-Level Nested Expand

You can expand a navigation-of-a-navigation by calling `expand(...)` on a
`NavQuery` (opened from a collection navigation constant) inside another
`expand(...)`. This renders OData's multi-level `$expand` — for example
`$expand=Trips($expand=PlanItems)`:

```java
client.people()
    .expand(Person.TRIPS.expand(Trip.PLAN_ITEMS))
    .get();
```

A bare navigation constant passed to `expand(...)` expands the nav with no
options. To nest options on the inner nav, chain them (constants or lambdas):

```java
client.people()
    .expand(Person.TRIPS.expand(
        Trip.PLAN_ITEMS.select(PlanItem.PLAN_ITEM_ID, PlanItem.CONFIRMATION_CODE)))
    .get();
```

This produces (roughly):

```text
$expand=Trips($expand=PlanItems($select=PlanItemId,ConfirmationCode))
```

Chaining works at any depth — `NavQuery.expand(...)` also accepts another
`NavQuery`, so you can keep nesting (e.g.
`People($expand=Trips($expand=PlanItems($expand=...)))`).

## Polymorphic Expands (Type Casts)

When a navigation targets a base type and you need a subtype-only navigation, use a
**type-cast segment** — OData's `Versions/ABC.Doc($expand=abc)` form. The generator
emits a typed constant per (navigation, known subtype) pair, with the qualified CSDL
type name baked in:

```java
// given Version with derived type ABC.Doc declaring the abc navigation
var containers = client.containers(id)
    .expand(MyContainer.VERSIONS_AS_DOC.expand(MyDoc.ABC))
    .get();
// GET .../Containers(id)?$expand=Versions/ABC.Doc($expand=abc)
```

- The cast narrows the expanded collection to `Doc` elements; nested options
  (`select`/`filter`/`expand`/`top`/`count`) are type-checked against `Doc` — its own
  constants and inherited base constants both work
- Casting to an unrelated type is a compile error (`<S extends T>` bound)
- Lambda spelling (the 3-arg `as()` also wires the subtype's selector):

```java
client.versions()
    .expand(v -> v.VERSIONS.as("NS.Doc", Doc.class, Doc.Selector::new).select(d -> d.TITLE))
    .get();
```

- For subtypes the generator doesn't know (or quick experiments), the escape hatch:

```java
client.containers(id)
    .expand(NavQuery.raw("Versions/ABC.Doc($expand=abc)"))
    .get();
```

## Expanded Values in Getters

When you `$expand` a navigation property, the expanded data is automatically
deserialized into the entity's typed getter. No manual parsing needed:

```java
Person scott = client.people("scottketchum")
    .expand(Person.TRIPS.expand(Trip.PLAN_ITEMS))
    .get();

// getTrips() returns List<Trip> — populated by expanded JSON
List<Trip> trips = scott.getTrips();
assertFalse(trips.isEmpty());

// getPlanItems() on each Trip returns List<PlanItem> — nested expand
PlanItem item = trips.get(0).getPlanItems().get(0);
```

Collection navs return `List<T>`, singleton navs return `Optional<T>`.

For complex types with navigation properties (e.g. `Location` with
`AirportRef`), the same pattern applies:

```java
// getAirportRef() returns Optional<Airport> — populated by expanded JSON
Optional<Airport> airport = location.getAirportRef();
```

## What's Next

- [Use Pagination](pagination.md) — Handle large result sets
- [Perform CRUD Operations](crud.md) — Create, update, delete
- [Filter with Type-Safe Expressions](../how-to/filter.md) — The `$filter` expression API
