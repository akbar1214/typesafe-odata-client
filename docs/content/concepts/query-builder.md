# Type-Safe Query Building

Generated property constants carry the owning entity type in their generic signature. That lets request methods reject properties from another entity while still accepting properties declared by a base entity.

## Expression Hierarchy

```text
Expression<T>
├── OrderExpression<E, T>
│   └── PropertyExpression<E, T>
│       ├── StringProperty<E>
│       ├── NumberProperty<E, N>
│       ├── BooleanProperty<E>
│       ├── DateTimeProperty<E>
│       ├── GuidProperty<E>
│       └── EnumProperty<E, V>
└── FilterExpression<E>

ApplyExpression

Collection and navigation builders
├── CollectionProperty<E, T, F, Sel>
├── NavCollectionProperty<E, T, F, Sel>
└── NavQuery<S, T, Sel>
```

`CollectionProperty` has four type parameters: owner entity `E`, element type `T`, filterable type `F`, and selector type `Sel`. Generated collection navigations use `NavCollectionProperty`, a subtype that also implements `Expandable`; structural collection properties use `CollectionProperty`. Primitive collection elements use `CollectionProperty.FilterableElement<T>`.

## Property Operations

`StringProperty` supports equality, null checks, lexicographic comparisons, `contains`, `startsWith`, `endsWith`, `matchesPattern`, `length`, `indexOf`, `substring`, `trim`, `toLower`, `toUpper`, and `concat`.

`NumberProperty` and `NumberExpression` support comparisons, `add`, `subtract`, `multiply`, `divide`/`divby`, `modulo`, `negate`, `ceiling`, `floor`, and `round`. Floating Edm types use `divby`; integer operands use `div`.

`DateTimeProperty` accepts validated OData temporal strings and typed `LocalDate`, `OffsetDateTime`, `LocalTime`, and `Duration` values. It also provides `year`, `month`, `day`, `hour`, `minute`, `second`, `fractionalSeconds`, `totalOffsetMinutes`, `totalSeconds`, `date`, and `time` where the Edm type permits them.

`BooleanProperty` supports `isTrue`, `isFalse`, equality, and null checks. `EnumProperty` renders qualified enum literals and supports `has(...)` for enums declared with `IsFlags="true"`. `GuidProperty` validates an 8-4-4-4-12 value and emits an unquoted GUID literal.

## Logical Composition

```java
FilterExpression<Person> expression =
    Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K"));
```

`and`, `or`, and `not` preserve the entity type parameter. Multiple request-level `filter(...)` calls are ANDed and each predicate is parenthesized before the join.

## Collection Predicates

Generated entity navigation constants provide typed `any` and `all` lambdas:

```java
client.people()
    .filter(Person.TRIPS.any(trip -> trip.BUDGET.greaterThan(500.0f)))
    .filter(Person.TRIPS.all(trip -> trip.BUDGET.greaterThan(0.0f)))
    .get();
```

`any` and `all` receive the target type's generated `Filterable` class. Primitive collection elements use the string-addressable `FilterableElement<T>` helper; entity and complex collections use their generated filterable views.

## Request Selector Lambdas

`select`, `orderBy`, `expand`, and collection `filter` also have selector-lambda overloads:

```java
client.people()
    .select(p -> p.FIRST_NAME, p -> p.LAST_NAME)
    .orderBy(p -> p.LAST_NAME.asc())
    .expand(p -> p.TRIPS.select(t -> t.NAME).top(2))
    .get();
```

Selector lambdas use generated `Selector` views whose fields share the entity constants. The `Sel` type parameter is carried by `NavQuery` and `NavCollectionProperty`, allowing nested lambda composition at arbitrary depth. Hand-built navigation values without a selector factory support constant builders and fail fast if a lambda overload is used.

## Apply Expressions

`ApplyExpression.builder()` returns an `ApplyBuilder`. This example uses the generated
`Product` type from the OData Demo fixture; substitute the entity and property names from
your own metadata:

```java
ApplyExpression expression = ApplyExpression.builder()
    .filter(Product.PRICE.greaterThan(10.0))
    .groupBy(Product.NAME)
    .aggregate("Price with sum as Total");
```

`ApplyExpression.of(raw)` is the raw escape hatch. `compute(...)` is a transformation inside `$apply`, not a standalone request option.
