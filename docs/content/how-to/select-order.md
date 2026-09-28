# Select and Order Results

Control which fields are returned and how results are sorted.

## Select Specific Fields

### Basic Select

```java
client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .get();
```

Only `FirstName` and `LastName` are included in the response.

### Selector-Lambda Spelling

Every constant-based example has a lambda form: apply the lambda to a
`Selector` view of the entity's properties. Both render the identical URL —
pick one style and stay consistent:

```java
client.people()
    .select(p -> p.FIRST_NAME, p -> p.LAST_NAME)
    .orderBy(x -> x.LAST_NAME.asc())
    .get();
```

Cross-entity mistakes are compile errors in both spellings: `Trip.NAME` is not a
member of `Person.Selector`, so `select(p -> p.NAME)` does not compile.

### Select All Fields

Omit `select(...)` when the service should return the entity's normal representation. The generated `select()` zero-argument bridge exists for overload resolution, but it does not add a `$select` option and is not needed for this case.

### Select Nested Properties

`$select` accepts generated scalar and enum property expressions (`PropertyExpression`). Collection-valued structural properties and navigation properties have different builder types and cannot be passed to `select(...)`; use their collection/filter or expand APIs instead:

```java
client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .expand(Person.TRIPS)
    .get();
```

## Order Results

### Single Property

```java
client.people()
    .orderBy(Person.LAST_NAME.asc())
    .get();

client.people()
    .orderBy(Person.LAST_NAME.desc())
    .get();
```

### Multiple Properties

```java
client.people()
    .orderBy(Person.LAST_NAME.asc(), Person.FIRST_NAME.asc())
    .get();
```

Results are sorted by `LastName` first, then `FirstName` within each last name.

### Select + Order

```java
client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .get();
```

## Combine with Filter

```java
client.people()
    .filter(Person.CONCURRENCY.greaterThan(25L))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .get();
```

## What's Next

- [Expand Navigation Properties](expand.md) — Include related entities
- [Use Pagination](pagination.md) — Handle large result sets
