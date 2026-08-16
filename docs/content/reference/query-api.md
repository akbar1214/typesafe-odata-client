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
| `divide(value)` | `div` / `divby` | Division — `div` for integer operands (truncating), `divby` for Double/Decimal/Single |
| `mod(value)` | `mod` | Modulus |
| `negate()` | `-` | Negate |

### BooleanProperty

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | Exact match |
| `notEqualTo(value)` | `ne` | Not equal |

### DateTimeProperty

Comparison operators accept pre-formatted strings (validated against the OData ABNF) or
typed `LocalDate` / `OffsetDateTime` / `LocalTime` / `Duration` values, which are
formatted per the ABNF automatically.

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

Produces: `Trips/any(x: x/Budget gt 500.0f)`

### all

```java
Person.TRIPS.all(trip ->
    trip.BUDGET.greaterThan(100.0f)
)
```

Produces: `Trips/all(x: x/Budget gt 100.0f)`

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
    .and(Person.CONCURRENCY.greaterThan(25))
```

Produces: `(FirstName eq 'Scott' or FirstName eq 'Keith') and Concurrency gt 25`

### Lambda Expression

```java
Person.TRIPS.any(trip ->
    trip.BUDGET.greaterThan(500.0f)
    .and(trip.STARTS_AT.year().equalTo(2024))
)
```

Produces: `Trips/any(x: x/Budget gt 500.0f and x/Duration gt duration'P7D')`

## What's Next

- [HTTP Transport](http-transport.md) — API details
- [Serialization](serialization.md) — JSON library options

### GuidProperty

`Edm.Guid` properties. Literals are the bare 8-4-4-4-12 value (quoted strings are an
OData type error); anything else throws `IllegalArgumentException`.

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(guid)` | `eq` | GUID equality |
| `notEqualTo(guid)` | `ne` | GUID inequality |
| `isNull()` / `isNotNull()` | `eq/ne null` | Null predicates |
| `asc()` / `desc()` | `$orderby` | Ordering |

### EnumProperty\<E, V\>

| Method | OData | Description |
|--------|-------|-------------|
| `equalTo(value)` | `eq` | `NS.Enum'Member'` literal (fully qualified) |
| `notEqualTo(value)` | `ne` | Not equal |
| `has(value)` | `has` | Flags membership (IsFlags enums) |
| `isNull()` / `isNotNull()` | `eq/ne null` | Null predicates |

### CollectionProperty\<E, T, F\>

| Method | OData | Description |
|--------|-------|-------------|
| `any(predicate)` | `/any(x: ...)` | At least one element matches (typed `Filterable` lambda) |
| `all(predicate)` | `/all(x: ...)` | Every element matches |
| `contains(value)` | `contains()` | Collection contains a value |
| `length()` | `length()` | Element count |

!!! note
    `$select` accepts structural property paths only — transformation results such as
    `Person.FIRST_NAME.toUpper()` are rejected with a clear error (function calls
    belong in `$filter` or `$compute`; `$orderby` accepts them, which is legal OData).
