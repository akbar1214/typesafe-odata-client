# Filter with Type-Safe Expressions

Build `$filter` expressions that are validated at compile time.

## Type Safety Guarantees

A filter is a `FilterExpression<E>` parameterized by the entity type it applies
to. This gives two compile-time guarantees:

- **Cross-entity filters are rejected.** You cannot filter `People` with a
  `Trip` predicate — `client.people().filter(Trip.BUDGET.greaterThan(500))`
  fails to compile.
- **Base-type predicates work on subtypes.** Because `filter()` accepts
  `FilterExpression<? super E>`, a predicate written against a base type (e.g.
  `PlanItem`) is accepted when filtering a subtype (`Flight`). This is what makes
  entity inheritance useful in queries.

Use `FilterExpression.of("raw odata")` only when you need an expression the
builder doesn't cover.

## Basic Comparisons

### String Operations

```java
import com.example.trippin.entity.Person;

// Exact match
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .get();

// Contains
client.people()
    .filter(Person.FIRST_NAME.contains("ott"))
    .get();

// Starts with
client.people()
    .filter(Person.FIRST_NAME.startsWith("S"))
    .get();

// Ends with
client.people()
    .filter(Person.FIRST_NAME.endsWith("ott"))
    .get();

// Case-insensitive (uses the tolower() OData function)
client.people()
    .filter(Person.FIRST_NAME.toLower().equalTo("scott"))
    .get();
```

### Numeric Operations

```java
// Comparison operators (Person.CONCURRENCY is Edm.Int64)
client.people()
    .filter(Person.CONCURRENCY.greaterThan(25))
    .get();

client.people()
    .filter(Person.CONCURRENCY.greaterThanOrEqualTo(18))
    .get();

// Arithmetic on the related collection via a typed lambda
client.people()
    .filter(Person.TRIPS.any(trip -> trip.BUDGET.multiply(2).greaterThan(1000.0f)))
    .get();
```

### Enum Operations

```java
client.people()
    .filter(Person.GENDER.equalTo(PersonGender.Male))
    .get();

// Flags membership (IsFlags enums): Gender has NS.PersonGender'Male'
client.people()
    .filter(Person.GENDER.has(PersonGender.Male))
    .get();
```

## Logical Operators

### AND

```java
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.equalTo("Ketchum")))
    .get();
```

### OR

```java
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .or(Person.FIRST_NAME.equalTo("Keith")))
    .get();
```

### NOT

```java
client.people()
    .filter(Person.FIRST_NAME.notEqualTo("Scott"))
    .get();
```

### Chaining filter() Calls

Multiple `filter()` calls are ANDed together. Each predicate is parenthesized
before the join, so an `or` inside one predicate cannot bind across the
implicit `and`:

```java
// Renders: $filter=(FirstName eq 'Scott' or FirstName eq 'Keith') and Concurrency gt 25
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .or(Person.FIRST_NAME.equalTo("Keith")))
    .filter(Person.CONCURRENCY.greaterThan(25))
    .get();
```

Prefer explicit `.and()`/`.or()` composition when the boolean structure matters;
chaining is a convenience for independent conditions.

### Complex Expressions

```java
// (FirstName = 'Scott' OR FirstName = 'Keith') AND Concurrency > 25
client.people()
    .filter(
        Person.FIRST_NAME.equalTo("Scott")
            .or(Person.FIRST_NAME.equalTo("Keith"))
    )
    .and(Person.CONCURRENCY.greaterThan(25))
    .get();
```

## Null Checks

Passing `null` to `equalTo`/`notEqualTo` (or calling `isNull()`/`isNotNull()`) renders
the null predicates — including on nullable enums and GUIDs:

```java
client.people()
    .filter(Person.GENDER.equalTo(null))       // Gender eq null
    .get();

client.people()
    .filter(Person.GENDER.isNotNull())         // Gender ne null
    .get();
```

## Collection Functions

```java
// Length
client.people()
    .filter(Person.FIRST_NAME.length().greaterThan(3))
    .get();

// Index of
client.people()
    .filter(Person.FIRST_NAME.indexOf("ott").greaterThan(0))
    .get();

// Substring
client.people()
    .filter(Person.FIRST_NAME.substring(0, 1).equalTo("S"))
    .get();

// Trim
client.people()
    .filter(Person.FIRST_NAME.trim().equalTo("Scott"))
    .get();

// Concat
client.people()
    .filter(Person.FIRST_NAME.concat(Person.LAST_NAME).equalTo("ScottKetchum"))
    .get();
```

## Date/Time Operations

`DateTimeProperty` accepts pre-formatted strings (validated against the OData ABNF) or
typed values — `LocalDate`, `OffsetDateTime`, `LocalTime`, and `Duration` are formatted
for you. Note that `Trip.STARTS_AT` belongs to `Trip`, so the predicates appear inside a
typed lambda or a nested `$expand` filter, keeping the compile-time entity checks:

```java
// Typed literals: OffsetDateTime renders as a bare ISO datetime literal
client.people()
    .filter(Person.TRIPS.any(trip ->
        trip.STARTS_AT.greaterThan(OffsetDateTime.parse("2024-06-01T00:00:00Z"))))
    .get();

// Date/time extraction: year(), month(), day(), hour(), minute(), second()
client.people()
    .filter(Person.TRIPS.any(trip -> trip.STARTS_AT.year().equalTo(2024)))
    .get();

// Durations render as duration'...' literals
client.people()
    .expand(Person.TRIPS.filter(Trip.STARTS_AT.year().equalTo(2024)))
    .get();
```

## GUID Filters

`Edm.Guid` properties use `GuidProperty`, whose literals are the bare 8-4-4-4-12 form
(quoted strings are a type error services reject):

```java
client.people()
    .filter(Person.TRIPS.any(trip ->
        trip.SHARE_ID.equalTo("0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001")))
    .get();
```

## Lambda Operators

### any

```java
// People who have at least one trip with budget > 500
client.people()
    .filter(Person.TRIPS.any(trip -> trip.BUDGET.greaterThan(500.0f)))
    .get();
```

### all

```java
// People where all trips have budget > 100
client.people()
    .filter(Person.TRIPS.all(trip -> trip.BUDGET.greaterThan(100.0f)))
    .get();
```

## What's Next

- [Select and Order Results](select-order.md) — Project fields and sort
- [Query API Reference](../reference/query-api.md) — Complete list of operations
