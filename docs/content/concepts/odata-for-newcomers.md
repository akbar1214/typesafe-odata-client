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

- Entities are addressed by predictable URLs.
- Querying (filter, sort, page, expand) is done with well-known **query options**
  like `$filter`, `$select`, and `$expand`.
- The service describes its own schema in machine-readable **CSDL metadata**.

The big win: a client can be *generated* from the metadata, so you write
compile-time-safe code instead of hand-built request strings. That is exactly
what OData Codegen does.

> Example service used throughout this guide: the public
> [TripPin](https://services.odata.org/V4/TripPinService) demo service.

---

## Core concepts

### Entity Type and Entity Set

An **Entity Type** is a structured record — roughly a "table row" or "class".
A **Entity Set** is a collection of those records, exposed at a URL.

```text
People                       <- entity set (a collection of Person)
People('scottketchum')       <- one Person entity, addressed by key
```

In OData Codegen this becomes:

```java
client.people()                    // -> collection request (People)
client.peopleByUserName("scottketchum")  // -> entity request (People('scottketchum'))
```

### Properties, Keys, and Nullability

Each entity type has **Properties** (scalar or complex values) and a **Key**
that uniquely identifies an instance. Properties can be nullable.

```text
Person
  UserName   String   (key, not null)
  FirstName  String   (nullable)
  Age        Int32    (nullable)
```

Codegen turns each property into a `UPPER_CASE` constant and a typed getter:

```java
Person.FIRST_NAME.equalTo("Scott")   // type-safe filter
person.getFirstName()                // Optional<String> (nullable)
person.getUserName()                 // String (key, non-null)
```

### Navigation Properties

A **Navigation Property** links one entity to another entity or collection.
Think of them as typed relationships / foreign keys.

```text
Person
  Trips : Collection(Trip)      <- "this person's trips"
Trip
  PlanItemId ... 
  Person : Person               <- "the owner of this trip"
```

Navigations let you traverse the graph in URLs:

```text
People('scottketchum')/Trips
```

In Codegen you traverse them through *request* objects (not the entity itself —
entities are pure data and hold no HTTP context):

```java
client.peopleByUserName("scottketchum").trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .get();
```

### Complex Types and Enums

- A **Complex Type** is a structured value embedded inside an entity (like an
  `Address`), but it has no key of its own.
- An **Enum Type** is a named set of integer/string values (like `PersonGender`).

Both are generated as plain Java types you can reference just like entities.

### Inheritance

OData entity types can declare a `BaseType`. Real services use this to model
hierarchies, e.g. TripPin's:

```text
PlanItem  (base)
  ├── Event
  └── PublicTransportation
        └── Flight
```

OData Codegen honors `BaseType` by emitting a real Java `extends` clause, so a
`Flight` *is a* `PlanItem` and inherits its fields, keys, and query constants.

### CSDL Metadata (`$metadata`)

Every OData service publishes its schema at `/$metadata`. It is written in
**CSDL** (Common Schema Definition Language) as XML. This is the single source
of truth OData Codegen parses to generate your client.

```text
https://services.odata.org/V4/TripPinService/$metadata
```

---

## The OData query language (query options)

OData queries are plain URLs with special `$`-prefixed query parameters.
OData Codegen gives you a Java method for each one, so you rarely write the raw
string — but it helps to know what they map to.

### `$filter`

Restrict rows with a boolean expression. Operators: `eq`, `ne`, `gt`, `ge`,
`lt`, `le`, `and`, `or`, `not`, `contains`, `startswith`, `endswith`, etc.

```text
People?$filter=FirstName eq 'Scott' and startswith(LastName,'K')
```

```java
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .get();
```

### `$select`

Return only the listed properties (smaller payloads).

```text
People?$select=FirstName,LastName
```

```java
client.people().select(Person.FIRST_NAME, Person.LAST_NAME).get();
```

### `$orderby`

Sort by one or more properties, with `asc`/`desc`.

```text
People?$orderby=LastName desc,FirstName
```

```java
client.people().orderBy(Person.LAST_NAME.desc(), Person.FIRST_NAME.asc()).get();
```

### `$expand`

Include related entities inline. This is OData's answer to JOINs / GraphQL
nested fetches.

```text
People?$expand=Trips
People?$expand=Trips($select=Name;$top=5;$filter=Budget gt 500)
```

```java
// simple expand
client.people().expand(Person.TRIPS).get();

// nested options on the expanded navigation
client.people()
    .expand(Person.TRIPS.select(Trip.NAME).top(5)
        .filter(Trip.BUDGET.greaterThan(500.0f)))
    .get();
```

### `$top` and `$skip` (paging)

`$top` limits the number of rows; `$skip` offsets into the result. Combine them
for page-by-page navigation.

```text
People?$top=10&$skip=20
```

```java
client.people().top(10).skip(20).get();
```

### `$count`

Ask the service to return the total matching count alongside the page.

```text
People?$count=true
```

```java
CollectionPage<Person> page = client.people().count().top(10).get();
long total = page.count().orElse(0L);
```

### `$search` and `$apply` (advanced)

- `$search` performs a full-text search when the service supports it.
- `$apply` runs server-side transformations (aggregate, groupby, filter) — useful
  for analytics. These are passed through as raw options when needed.

---

## A full request, end to end

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

## How OData Codegen maps OData → Java

| OData concept            | Generated Java                                      |
|-------------------------|----------------------------------------------------|
| Entity Type             | `final class Person implements ODataEntityType`    |
| Entity Set              | `client.people()` collection request               |
| Key                     | `client.peopleByUserName(...)` entity request     |
| Property (constant)     | `Person.FIRST_NAME` (`StringProperty<Person>`)     |
| `$filter`               | `.filter(Person.FIRST_NAME.equalTo("Scott"))`      |
| Navigation Property     | `Person.TRIPS` (`NavProperty<Person, Trip>`)       |
| Nested `$expand`        | `.expand(Person.TRIPS.select(...).top(5))`         |
| `$select` / `$orderby`  | `.select(...)` / `.orderBy(...)`                   |
| Errors (4xx/5xx)        | `NotFoundException`, `RateLimitException`, ...     |

The generated client depends only on the small `odata-codegen-runtime`
library; there is no reflection or runtime proxy magic.

---

## Where to go next

- [Getting Started](../getting-started.md) — add the Maven plugin and generate your client.
- [Your First Query](../tutorial/first-query.md) — a complete walkthrough.
- [Filter with Type-Safe Expressions](../how-to/filter.md) — the expression API in depth.
- [Expand Navigation Properties](../how-to/expand.md) — nested `$expand` options.
- [OData URL Patterns](../reference/odata-urls.md) — the exact URLs Codegen produces.
- [ODATA.md](https://github.com/odata-codegen/odata-codegen/blob/main/ODATA.md) — the raw OData spec notes used while building this project.
