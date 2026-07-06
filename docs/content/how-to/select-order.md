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

### Select with Star

```java
client.people()
    .select("*")
    .get();
```

Returns all fields (this is the default behavior).

### Select Nested Properties

```java
client.people()
    .select(Person.FIRST_NAME, Person.TRIPS)
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
    .filter(Person.AGE.greaterThan(25))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .get();
```

## What's Next

- [Expand Navigation Properties](expand.md) — Include related entities
- [Use Pagination](pagination.md) — Handle large result sets
