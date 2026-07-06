# Filter with Type-Safe Expressions

Build `$filter` expressions that are validated at compile time.

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

// Case-insensitive (converts to tolower() in OData)
client.people()
    .filter(Person.FIRST_NAME.equalToIgnoreCase("scott"))
    .get();
```

### Numeric Operations

```java
// Comparison operators
client.people()
    .filter(Person.AGE.greaterThan(25))
    .get();

client.people()
    .filter(Person.AGE.greaterThanOrEqualTo(18))
    .get();

client.people()
    .filter(Person.AGE.lessThan(65))
    .get();

client.people()
    .filter(Person.AGE.lessThanOrEqualTo(30))
    .get();

// Range
client.people()
    .filter(Person.AGE.greaterThan(18).and(Person.AGE.lessThan(65)))
    .get();

// Arithmetic
client.people()
    .filter(Person.AGE.multiply(2).equalTo(50))
    .get();
```

### Boolean Operations

```java
client.people()
    .filter(Person.IS_ACTIVE.equalTo(true))
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

### Complex Expressions

```java
// (FirstName = 'Scott' OR FirstName = 'Keith') AND Age > 25
client.people()
    .filter(
        Person.FIRST_NAME.equalTo("Scott")
            .or(Person.FIRST_NAME.equalTo("Keith"))
    )
    .and(Person.AGE.greaterThan(25))
    .get();
```

## Null Checks

```java
// Is null
client.people()
    .filter(Person.EMAILS.equalTo(null))
    .get();

// Is not null
client.people()
    .filter(Person.EMAILS.notEqualTo(null))
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

```java
// Year, Month, Day
client.people()
    .filter(Trip.STARTS_AT.year().equalTo(2024))
    .get();

client.people()
    .filter(Trip.STARTS_AT.month().equalTo(6))
    .get();

// Duration
client.people()
    .filter(Trip.DURATION.days().greaterThan(7))
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
