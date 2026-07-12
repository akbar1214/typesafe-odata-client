# Type-Safe Query Building

Build OData queries with compile-time validation.

## Expression Hierarchy

```
Expression<T>
├── PropertyExpression<E, T> (abstract)
│   ├── StringProperty<E>
│   ├── NumberProperty<E, N>
│   ├── BooleanProperty<E>
│   ├── DateTimeProperty<E>
│   ├── EnumProperty<E, V>
│   └── CollectionProperty<E, T, F>
├── FilterExpression<E>
└── ApplyExpression
```

## Property Types

Each property type has its own set of valid operations:

### StringProperty

```java
Person.FIRST_NAME.equalTo("Scott")       // ✓
Person.FIRST_NAME.contains("ott")        // ✓
Person.FIRST_NAME.startsWith("S")        // ✓
Person.FIRST_NAME.endsWith("ott")        // ✓
Person.FIRST_NAME.length()               // ✓
Person.FIRST_NAME.greaterThan(3)         // ✗ Compile error!
```

### NumberProperty

```java
Person.AGE.greaterThan(25)               // ✓
Person.AGE.lessThanOrEqualTo(65)         // ✓
Person.AGE.multiply(2)                   // ✓
Person.AGE.equalTo(30)                   // ✓
Person.AGE.contains("2")                 // ✗ Compile error!
```

### BooleanProperty

```java
Person.IS_ACTIVE.equalTo(true)           // ✓
Person.IS_ACTIVE.and(otherExpression)    // ✓
Person.IS_ACTIVE.greaterThan(5)          // ✗ Compile error!
```

### CollectionProperty

```java
Person.TRIPS.any(trip -> trip.BUDGET.greaterThan(500))  // ✓
Person.TRIPS.all(trip -> trip.NAME.startsWith("A"))     // ✓
```

## Composing Expressions

### AND

```java
Person.FIRST_NAME.equalTo("Scott")
    .and(Person.LAST_NAME.equalTo("Ketchum"))
```

Produces: `FirstName eq 'Scott' and LastName eq 'Ketchum'`

### OR

```java
Person.FIRST_NAME.equalTo("Scott")
    .or(Person.FIRST_NAME.equalTo("Keith"))
```

Produces: `FirstName eq 'Scott' or FirstName eq 'Keith'`

### NOT

```java
Person.FIRST_NAME.notEqualTo("Scott")
```

Produces: `FirstName ne 'Scott'`

### Complex Expressions

```java
(Person.FIRST_NAME.equalTo("Scott").or(Person.FIRST_NAME.equalTo("Keith")))
    .and(Person.AGE.greaterThan(25))
```

Produces: `(FirstName eq 'Scott' or FirstName eq 'Keith') and Age gt 25`

## Compile-Time Safety

The type system prevents invalid operations at compile time:

```java
// This won't compile
Person.FIRST_NAME.greaterThan(3);

// This won't compile
Person.AGE.contains("Scott");

// This won't compile
Person.IS_ACTIVE.equalTo("true");
```

The error message tells you exactly what's wrong:

```
Error: java: cannot find symbol
  symbol: method greaterThan(int)
  location: class StringProperty
```

## Why This Matters

**String-based queries (old way):**

```java
// No compile-time safety
String filter = "FirstName eq 'Scott'";  // Typo: "FirstNme"
// Runtime HTTP 400 error
```

**Type-safe queries (new way):**

```java
// Compile error: cannot find symbol
Person.FIRST_NMAE.equalTo("Scott");
// Caught at compile time!
```

## What's Next

- [Entity Immutability](immutability.md) — Why records matter
- [CSDL Metadata Parsing](csdl-parsing.md) — How metadata is processed
