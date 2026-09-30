# OData for Newcomers

This page introduces the OData concepts needed to use OData Codegen. It assumes no prior OData experience.

## What is OData?

OData is a standard for querying and updating REST-style services. It defines keys, URLs, query options, metadata, and JSON conventions so clients can work against different services using the same model.

An OData service publishes its schema as CSDL XML, usually at `/$metadata`. OData Codegen uses that schema as the source for Java model and request classes.

Most request examples below use the public [TripPin service](https://services.odata.org/V4/TripPinService). The aggregation and batch examples are illustrative and require a service that supports those OData features.

## Entities, Entity Sets, and Keys

An **entity type** describes a record. An **entity set** is a collection of those records. A key identifies one entity.

```text
People                       entity set
People('scottketchum')       one Person entity
```

Generated accessors put the entity set name and key at the request layer:

```java
client.people();
client.people("scottketchum");
```

The first returns a collection request. The second returns an entity request.

## Properties and Nullability

CSDL properties become Java fields, getters, setters, and static query constants. A property declared `Nullable="true"` gets an `Optional<T>` getter; a non-nullable scalar or key getter returns its boxed Java type directly.

```java
Person.FIRST_NAME.equalTo("Scott");
String userName = person.getUserName();
```

Do not assume every generated getter returns `Optional`; inspect the CSDL nullability or the generated method signature.

Constants use `UPPER_CASE` so they do not collide with generated instance fields.

## Navigation Properties

Navigation properties describe relationships between entities:

```text
Person
  Trips : Collection(Trip)
```

Navigation requests are generated on request classes, not on model classes:

```java
CollectionPage<Trip> trips = client.people("scottketchum")
    .trips()
    .filter(trip -> trip.BUDGET.greaterThan(500.0f))
    .get();
```

A keyed navigation overload returns the target entity request directly:

```java
TripEntityRequest trip = client.people("scottketchum").trips(1);
```

## Complex Types and Enums

A complex type is a structured value without its own key. An enum type is a named set of CSDL enum members. Both are generated as ordinary Java types. Complex types can participate in inheritance and can contain navigation properties.

## Inheritance

A CSDL `BaseType` becomes a Java `extends` relationship. For example, TripPin models:

```text
PlanItem
  ├── Event
  └── PublicTransportation
        └── Flight
```

A predicate written against a base type can be used when querying a subtype collection:

```java
FilterExpression<PlanItem> recent =
    PlanItem.STARTS_AT.greaterThan(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
```

When the generated container exposes a `Flight` entity set, its collection request accepts that base-type predicate. The exact accessor name comes from the CSDL entity container.

The reverse is not allowed: a property declared only on `Flight` cannot be used to filter a `PlanItem` collection.

## Query Options

### `$filter`

`$filter` restricts a collection. Generated expression classes expose operators appropriate to each Edm type:

```java
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .get();
```

### `$select` and `$orderby`

```java
CollectionPage<Person> page = client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.desc())
    .get();
```

Selector lambdas are also available:

```java
client.people()
    .select(p -> p.FIRST_NAME)
    .orderBy(p -> p.LAST_NAME.asc())
    .get();
```

### `$expand`

`$expand` embeds related values in the response:

```java
client.people()
    .expand(Person.TRIPS.select(Trip.NAME).top(5))
    .get();
```

Expanded navigation values are deserialized into the model, so `person.getTrips()` returns a `List<Trip>`.

### `$top`, `$skip`, and `$count`

```java
CollectionPage<Person> page = client.people()
    .top(10)
    .skip(20)
    .count()
    .get();

long inlineCount = page.count().orElse(0L);
long countOnly = client.people().countValue();
```

`count()` requests `@odata.count` with the collection. `countValue()` performs a `GET` to the `/$count` endpoint and returns a `long`.

### `$apply`

`$apply` performs server-side transformations such as grouping and aggregation. `$compute` is a transformation inside `$apply`, not a standalone query option.

```java
client.people()
    .apply(ApplyExpression.builder()
        .groupBy(Person.LAST_NAME)
        .aggregate("$count as Total"))
    .get();
```

## CRUD and Batch

Collection requests create entities with `create(...)`; entity requests read, patch, put, delete, and expose navigation-link operations:

```java
Person created = client.people().create(newPerson);
Person fetched = client.people("scottketchum").get();
client.people("scottketchum").deleteWithETag(fetched.getETag().orElseThrow());
```

Batch requests send `multipart/mixed` content. Standalone operations and changesets are correlated back to submitted operation order, and explicit Content-IDs can be used for lookup:

```java
BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .addChangeset(new Changeset(List.of(
        BatchOperation.post("Customers", customerBody),
        BatchOperation.post("Orders", orderBody)
    )))
    .execute();
```

## Java Mapping Summary

| OData concept | Generated Java |
|---------------|----------------|
| Entity type | Model class in `entity` |
| Entity set | Collection request in `collection.request` |
| Key | Keyed entity-request overload |
| Navigation property | Typed model constant plus request navigation method |
| `$filter` | `FilterExpression<E>` |
| `$select` / `$orderby` | `SelectableExpression` / `OrderExpression` |
| `$expand` | `Expandable` values such as `NavCollectionProperty` and `NavQuery` |
| Function/action import | Request class in `operation` |
| Schema registry | `schema.SchemaInfo` |

## Where to Go Next

- [Getting Started](../getting-started.md) — Install the plugin and generate a client
- [Your First Query](../tutorial/first-query.md) — Complete TripPin walkthrough
- [Filter with Type-Safe Expressions](../how-to/filter.md) — Expression API
- [Expand Navigation Properties](../how-to/expand.md) — Nested expansions
- [OData URL Patterns](../reference/odata-urls.md) — URL construction rules
