# Query Expression API

Complete reference for type-safe query operations.

## Property Types

### StringProperty

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | Exact match |
| `notEqualTo(value)` | `ne` | Not equal |
| `greaterThan(value)` | `gt` | Greater than |
| `greaterThanOrEqualTo(value)` | `ge` | Greater than or equal |
| `lessThan(value)` | `lt` | Less than |
| `lessThanOrEqualTo(value)` | `le` | Less than or equal |
| `contains(value)` | `contains()` | Contains substring |
| `startsWith(value)` | `startswith()` | Starts with |
| `endsWith(value)` | `endswith()` | Ends with |
| `equalToIgnoreCase(value)` | `tolower() eq` | Case-insensitive |
| `notEqualToIgnoreCase(value)` | `tolower() ne` | Case-insensitive |
| `length()` | `length()` | String length |
| `indexOf(value)` | `indexof()` | Find position |
| `substring(start, end)` | `substring()` | Substring |
| `trim()` | `trim()` | Remove whitespace |
| `concat(value)` | `concat()` | Concatenate |

### NumberProperty\<T\>

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | Exact match |
| `notEqualTo(value)` | `ne` | Not equal |
| `greaterThan(value)` | `gt` | Greater than |
| `greaterThanOrEqualTo(value)` | `ge` | Greater than or equal |
| `lessThan(value)` | `lt` | Less than |
| `lessThanOrEqualTo(value)` | `le` | Less than or equal |
| `add(value)` | `add` | Addition |
| `subtract(value)` | `sub` | Subtraction |
| `multiply(value)` | `mul` | Multiplication |
| `divide(value)` | `div` | Division |
| `mod(value)` | `mod` | Modulus |
| `negate()` | `-` | Negate |

### BooleanProperty

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | Exact match |
| `notEqualTo(value)` | `ne` | Not equal |

### DateProperty

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | Exact match |
| `notEqualTo(value)` | `ne` | Not equal |
| `greaterThan(value)` | `gt` | Greater than |
| `greaterThanOrEqualTo(value)` | `ge` | Greater than or equal |
| `lessThan(value)` | `lt` | Less than |
| `lessThanOrEqualTo(value)` | `le` | Less than or equal |
| `year()` | `year()` | Extract year |
| `month()` | `month()` | Extract month |
| `day()` | `day()` | Extract day |
| `hour()` | `hour()` | Extract hour |
| `minute()` | `minute()` | Extract minute |
| `second()` | `second()` | Extract second |
| `date()` | `date()` | Date part |
| `time()` | `time()` | Time part |

### CollectionProperty\<T\>

| Method | OData | Description |
|--------|-------|-------------|
| `any(lambda)` | `any()` | Check if any element matches |
| `all(lambda)` | `all()` | Check if all elements match |
| `contains(value)` | `contains()` | Check if contains element |
| `length()` | `length()` | Collection length |

## Logical Operators

### AND

```java
expression1.and(expression2)
```

Produces: `expr1 and expr2`

### OR

```java
expression1.or(expression2)
```

Produces: `expr1 or expr2`

### NOT

```java
expression.not()
```

Produces: `not expr`

## Lambda Operators

### any

```java
Person.TRIPS.any(trip ->
    trip.BUDGET.greaterThan(500.0f)
)
```

Produces: `Trips/any(trip: trip/Budget gt 500.0f)`

### all

```java
Person.TRIPS.all(trip ->
    trip.BUDGET.greaterThan(100.0f)
)
```

Produces: `Trips/all(trip: trip/Budget gt 100.0f)`

## Sort Expressions

| Method | Description |
|--------|-------------|
| `property.asc()` | Ascending |
| `property.desc()` | Descending |

## Examples

### Basic Filter

```java
Person.FIRST_NAME.equalTo("Scott")
```

Produces: `FirstName eq 'Scott'`

### Complex Filter

```java
(Person.FIRST_NAME.equalTo("Scott").or(Person.FIRST_NAME.equalTo("Keith")))
    .and(Person.AGE.greaterThan(25))
```

Produces: `(FirstName eq 'Scott' or FirstName eq 'Keith') and Age gt 25`

### Lambda Expression

```java
Person.TRIPS.any(trip ->
    trip.BUDGET.greaterThan(500.0f)
    .and(trip.DURATION.days().greaterThan(7))
)
```

Produces: `Trips/any(trip: trip/Budget gt 500.0f and trip/Duration gt duration'P7D')`

## What's Next

- [HTTP Transport](http-transport.md) — API details
- [Serialization](serialization.md) — JSON library options
