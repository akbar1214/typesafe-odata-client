# OData for Newcomers

This page is for Java developers who have never used OData. It explains the
core ideas you need to use **OData Codegen** productively. No prior OData
experience required.

If you already know OData, skip to [Getting Started](../getting-started.md).

---

## What is OData?

**OData (Open Data Protocol)** is an OASIS standard for building and consuming
RESTful APIs over HTTP. Instead of inventing your own URL conventions and JSON
shapes, an OData service exposes data through a *standard* set of rules:

- All data is organized into **entities** with **keys** — like database rows with primary keys.
- Every entity has a predictable URL: `/People('scott')`, `/Products(42)`.
- Querying (filter, sort, page, expand relationships) uses well-known **query options**
  like `$filter`, `$select`, and `$expand` — the same across all OData services.
- The service publishes its own schema at `/$metadata` in **CSDL** XML.
  This is the single source of truth — like a database schema, but exposed over HTTP.

> **Analogy:** If REST without OData is like calling a restaurant and saying
> "give me the usual" (you have to know the exact protocol), OData is like
> reading the menu (the `$metadata`) and ordering by item number — any kitchen
> that follows the menu system can serve you.

The big win: a client can be **generated** from the metadata, so you write
compile-time-safe Java instead of hand-building URL strings. That is exactly
what OData Codegen does.

> Example service used throughout this guide: the public
> [TripPin](https://services.odata.org/V4/TripPinService) demo service.

---

## Core Concepts

### Entity Types and Entity Sets

An **Entity Type** is a structured record — think of it as a database table or
a Java class with fields. An **Entity Set** is the collection of those records
exposed at a URL — the table's contents.

```
People                       ← entity set (the "table")
People('scottketchum')       ← one Person entity, addressed by key
```

> **Analogy:** An entity set is a filing cabinet drawer. Each entity is a
> folder in that drawer. The key is the label on the tab — `'scottketchum'`
> lets you pull the right folder without opening every one.

In OData Codegen this becomes:

```java
client.people()                         // → collection request
client.peopleByUserName("scottketchum") // → single entity request
```

### Properties, Keys, and Nullability

Each entity type has **Properties** (scalar values like strings, numbers, dates)
and a **Key** that uniquely identifies an instance (like a primary key).
Properties can be nullable.

```
Person
  UserName   String   (key, NOT null)
  FirstName  String   (nullable)
  Age        Int32    (nullable)
```

Codegen turns each property into a `UPPER_CASE` constant for queries and a
typed getter for reading data:

```java
Person.FIRST_NAME.equalTo("Scott")   // type-safe filter expression
person.getFirstName()                // returns Optional<String> (nullable)
person.getUserName()                 // returns String (key, never null)
```

**Why `UPPER_CASE` constants?** The constant name `FIRST_NAME` would collide
with an instance field `firstName` if both used camelCase. `UPPER_CASE`
follows Java conventions (like `Integer.MAX_VALUE`) and avoids the collision.

### Navigation Properties

A **Navigation Property** links one entity to another — think of it as a
foreign key relationship, but deeply typed.

```
Person
  Trips : Collection(Trip)      ← "this person's trips"
Trip
  PlanItemId : Int32
  PersonRef : Person            ← "the owner of this trip"
```

> **Analogy:** Navigation properties are the hyperlinks of OData. Just as a
> Wikipedia page links to related pages, an entity links to related entities.
> You can follow those links in URLs, and (with `$expand`) fetch them all in
> one request — like clicking "Open all links in new tabs."

Navigations let you traverse the graph in URLs:

```
People('scottketchum')/Trips
```

In Codegen, you traverse through **request objects** (not the entity itself —
entities are pure data and hold no HTTP context):

```java
client.peopleByUserName("scottketchum")
    .trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .get();
```

### Complex Types and Enums

- A **Complex Type** is a structured value embedded inside an entity (like an
  `Address`), but it has no key of its own. Think of it as a `@Embeddable`
  in JPA, or a struct in C.
- An **Enum Type** is a named set of integer/string values (like `PersonGender`).

Both are generated as plain Java types you can reference just like entities.
Complex types support inheritance too: TripPin's `EventLocation` and
`AirportLocation` both extend `Location`, so a `Location` field can hold
either subtype.

### Inheritance

OData entity types can declare a `BaseType`. Real services use this to model
hierarchies. TripPin's hierarchy:

```
PlanItem  (base)
  ├── Event
  └── PublicTransportation
        └── Flight
```

> **Analogy:** Entity inheritance is exactly like Java class inheritance.
> A `Flight` **is a** `PublicTransportation` **is a** `PlanItem`.
> Everything a `PlanItem` has (properties, keys, navigation constants),
> a `Flight` inherits automatically — no repetition.

OData Codegen honors `BaseType` by emitting a real Java `extends`:

```java
public class Flight extends PublicTransportation { ... }
public class PublicTransportation extends PlanItem { ... }
```

So a query written against `PlanItem` works on `Flight` too:

```java
// Filter expression written against the base type
FilterExpression<PlanItem> hasBudget = PlanItem.BUDGET.greaterThan(100);

// Accepted when filtering a subtype collection — compile-time safe
client.peopleByUserName("scott").trips()
    .filter(hasBudget)  // FilterExpression<? super PlanItem>
    .get();
```

### CSDL Metadata (`$metadata`)

Every OData service publishes its schema at `/$metadata`. It is written in
**CSDL** (Common Schema Definition Language) as XML. This is the single source
of truth OData Codegen parses to generate your client.

```
https://services.odata.org/V4/TripPinService/$metadata
```

---

## The OData Query Language

OData queries are plain URLs with special `$`-prefixed query parameters.
OData Codegen gives you a type-safe Java method for each one, so you rarely
write the raw string — but it helps to know what they map to.

### `$filter` — The WHERE clause

Restrict results with a boolean expression. Supports the operators you'd expect:
`eq`, `ne`, `gt`, `ge`, `lt`, `le`, `and`, `or`, `not`, plus string functions
like `contains`, `startswith`, `endswith`.

```
People?$filter=FirstName eq 'Scott' and startswith(LastName,'K')
```

```java
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .get();
```

> **Analogy:** `$filter` is SQL `WHERE` — same concept, different syntax.
> The property-type-aware operators (`StringProperty.contains()`,
> `NumberProperty.greaterThan()`) prevent you from writing a filter the
> server would reject.

### `$select` — Column pruning

Return only the listed properties. Smaller payloads, faster responses.

```
People?$select=FirstName,LastName
```

```java
client.people().select(Person.FIRST_NAME, Person.LAST_NAME).get();
```

> **Analogy:** `$select` is SQL `SELECT FirstName, LastName` — you ask the
> server to send only the columns you need, skipping the rest.

### `$orderby` — Sorting

Sort by one or more properties, with `asc`/`desc`.

```
People?$orderby=LastName desc,FirstName
```

```java
client.people().orderBy(Person.LAST_NAME.desc(), Person.FIRST_NAME.asc()).get();
```

### `$expand` — The JOIN / eager-fetch

Include related entities inline. This is OData's answer to SQL JOINs and
GraphQL nested fetches — all in a single HTTP round-trip.

```
People?$expand=Trips                                              ← simple
People?$expand=Trips($select=Name;$top=5;$filter=Budget gt 500)  ← nested
```

```java
// Simple expand — Trips data is embedded in the Person JSON
client.people().expand(Person.TRIPS).get();

// Nested options on the expanded navigation
client.people()
    .expand(Person.TRIPS.select(Trip.NAME).top(5)
        .filter(Trip.BUDGET.greaterThan(500.0f)))
    .get();
```

> **Analogy:** Without `$expand`, fetching a person plus their trips takes
> two requests: first the person, then their trips. With `$expand`, it's one
> request — the server embeds the trips inside the person response. Like
> asking for a customer's profile with their recent orders already stapled
> to it.

Navigation properties deserialized from expanded JSON are accessible via
typed getters on the entity itself:

```java
Person person = client.peopleByUserName("scott").expand(Person.TRIPS).get().orElseThrow();
List<Trip> trips = person.getTrips();  // already populated by Jackson
```

### `$top` and `$skip` — Paging

`$top` limits the number of rows; `$skip` offsets into the result. Combine them
for page-by-page navigation.

```
People?$top=10&$skip=20
```

```java
client.people().top(10).skip(20).get();
```

### `$count` — Total matching count

Ask the service to return the total count alongside the page. Useful for
"Showing 1-10 of 342" UI text.

```
People?$count=true
```

```java
CollectionPage<Person> page = client.people().count().top(10).get();
long total = page.count().orElse(0L);
```

### `$apply` — Server-side aggregation

`$apply` runs server-side transformations — grouping, aggregating, computing
derived values. It's OData's answer to SQL `GROUP BY` and window functions.

```
Products?$apply=groupby((Category))/aggregate(Price with sum as Total)
```

```java
client.products().apply(Apply.builder()
    .groupBy(Product.CATEGORY)
    .aggregate(Product.PRICE.sum().as("Total"))
    .build())
    .get();
```

### Batch (`$batch`)

Send multiple operations in a single HTTP request. Reduces round-trips
dramatically. Supports both standalone operations and atomic **changesets**
(groups of mutations that succeed or fail together).

```java
Changeset cs = new Changeset(List.of(
    BatchOperation.post("Customers", customerJson),
    BatchOperation.post("Orders", orderJson)
));

BatchResponse response = ctx.batch()
    .addChangeset(cs)
    .add(BatchOperation.get("Customers"))
    .execute();
```

---

## A Full Request, End to End

```text
GET https://services.odata.org/V4/TripPinService/People
    ?$filter=startswith(LastName,'K')
    &$select=FirstName,LastName
    &$orderby=LastName desc
    &$top=10
    &$count=true
```

The equivalent, fully type-checked Java:

```java
CollectionPage<Person> page = client.people()
    .filter(Person.LAST_NAME.startsWith("K"))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.desc())
    .top(10)
    .count()
    .get();

long total = page.count().orElse(0L);
page.forEach(p -> System.out.println(p.getFirstName()));
```

If you mistype a property name or use the wrong operator for a type, the code
**fails to compile** instead of failing at runtime against the service. That is
the core value proposition of OData Codegen.

---

## How OData Codegen Maps OData → Java

| OData concept          | Generated Java                                  |
|------------------------|-------------------------------------------------|
| Entity Type            | `final class Person implements ODataEntityType` |
| Entity Set             | `client.people()` — collection request          |
| Key                    | `client.peopleByUserName(...)` — entity request |
| Property (getter)      | `person.getFirstName()` → `Optional<String>`    |
| Property (constant)    | `Person.FIRST_NAME` — `StringProperty<Person>`  |
| `$filter`              | `.filter(Person.FIRST_NAME.equalTo("Scott"))`   |
| `$select` / `$orderby` | `.select(...)` / `.orderBy(...)`                |
| Navigation Property    | `Person.TRIPS` — `NavProperty<Person, Trip>`    |
| Nested `$expand`       | `.expand(Person.TRIPS.select(...).top(5))`      |
| CRUD operations        | `.get()`, `.patch(entity)`, `.post(entity)`, `.delete()` |
| Batch                  | `context.batch().add(...).execute()`            |
| Atomic batch (changeset)| `context.batch().addChangeset(cs).execute()`   |
| Errors (4xx/5xx)       | `NotFoundException`, `RateLimitException`, ...  |
| Media streams          | `.streamMedia()` / `.setMedia(input)`           |

The generated client depends only on the small `odata-codegen-runtime`
library; there is no reflection or runtime proxy magic.

---

## Where to Go Next

- [Getting Started](../getting-started.md) — add the Maven plugin and generate your client.
- [Your First Query](../tutorial/first-query.md) — a complete walkthrough.
- [Filter with Type-Safe Expressions](../how-to/filter.md) — the expression API in depth.
- [Expand Navigation Properties](../how-to/expand.md) — nested `$expand` options.
- [OData URL Patterns](../reference/odata-urls.md) — the exact URLs Codegen produces.
