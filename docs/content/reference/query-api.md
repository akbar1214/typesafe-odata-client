# Query Expression API

The runtime expression types are generic over the entity that owns a property. Generated constants pass the owning entity class, which gives request methods compile-time entity bounds.

## Property Types

### `StringProperty<E>`

Equality and null checks use `eq`/`ne`. String-specific operations include:

| Method | OData form |
|--------|------------|
| `contains(value)` | `contains(Property,value)` |
| `startsWith(value)` | `startswith(Property,value)` |
| `endsWith(value)` | `endswith(Property,value)` |
| `matchesPattern(value)` | `matchesPattern(Property,value)` |
| `length()` | `length(Property)` |
| `indexOf(value)` | `indexof(Property,value)` |
| `substring(start[, length])` | `substring(...)` |
| `trim()` | `trim(Property)` |
| `toLower()` / `toUpper()` | `tolower(...)` / `toupper(...)` |
| `concat(valueOrProperty)` | `concat(...)` |

`greaterThan`, `greaterThanOrEqualTo`, `lessThan`, and `lessThanOrEqualTo` are also valid lexicographic string comparisons.

### `NumberProperty<E, N>` and `NumberExpression<N, E>`

Number expressions support `equalTo`, `notEqualTo`, `greaterThan`, `greaterThanOrEqualTo`, `lessThan`, `lessThanOrEqualTo`, `isNull`, and `isNotNull`, plus `add`, `subtract`, `multiply`, `divide`, `modulo`, `negate`, `ceiling`, `floor`, and `round`.

`NumberProperty` retains the Edm type. `divide` uses `divby` for floating `Edm.Single`, `Edm.Double`, or `Edm.Decimal` properties and `div` for integer operands. `INF`, `-INF`, and `BigDecimal` literals are rendered in the OData numeric form.

### `BooleanProperty<E>`

Use `equalTo`, `notEqualTo`, `isTrue`, `isFalse`, `isNull`, and `isNotNull`. A nullable `Boolean` passed to `equalTo` or `notEqualTo` is routed to the corresponding null predicate.

### `DateTimeProperty<E>`

Comparison operators accept validated OData temporal strings and typed `LocalDate`, `OffsetDateTime`, `LocalTime`, and `Duration` values. Values are rendered as bare OData temporal literals, including `duration'...'` for durations.

Date/time extraction methods include `year`, `month`, `day`, `hour`, `minute`, `second`, `fractionalSeconds`, `totalOffsetMinutes`, `totalSeconds`, `date`, and `time`. The generator supplies the Edm type so invalid function/type combinations fail clearly.

### `GuidProperty<E>`

`equalTo` and `notEqualTo` accept an 8-4-4-4-12 string. The literal is emitted without quotes. Invalid GUID text throws `IllegalArgumentException`; `isNull` and `isNotNull` remain available.

### `EnumProperty<E, V>`

`equalTo` and `notEqualTo` render fully qualified enum literals such as `Namespace.Color'Red'`. `has(value)` is for enums declared with `IsFlags="true"`. Generated constants pass the qualified CSDL type name, so sanitized Java member names are converted back to their wire names.

## Collection Properties

`CollectionProperty<E, T, F, Sel>` has four type parameters. `F` is the generated target `Filterable` type used by `any`/`all`; `Sel` is the target selector type used by request selector lambdas. Primitive collection elements use `CollectionProperty.FilterableElement<T>`.

`NavCollectionProperty<E, T, F, Sel>` is the generated subtype used for collection navigation constants. It extends `CollectionProperty` and implements `Expandable<E>`, so a bare navigation constant can be passed to `expand(...)` and can also open nested options.

| Method | OData form |
|--------|------------|
| `any(predicate)` | `Name/any(alias: predicate)` |
| `all(predicate)` | `Name/all(alias: predicate)` |
| `contains(value)` | `Name/any(alias: alias eq value)` |
| `length()` | `Name/$count` |
| `select`, `filter`, `orderBy`, `top`, `skip`, `count`, `expand` | `Name($select=...;$filter=...)` |
| `as(cast, subtype[, selectorFactory])` | `Name/Cast(...)` |

`contains(value)` and `length()` use OData collection forms rather than the string functions with those names. Null collection elements and missing selector factories fail fast.

## Logical Expressions

```java
FilterExpression<Person> expression =
    Person.FIRST_NAME.equalTo("Scott")
        .or(Person.FIRST_NAME.equalTo("Keith"))
        .and(Person.CONCURRENCY.greaterThan(25L));
```

The expression renders:

```text
(FirstName eq 'Scott' or FirstName eq 'Keith') and Concurrency gt 25
```

`FilterExpression.of("...")` is the raw escape hatch.

## Request Selector Lambdas

Generated request classes provide both constant and selector forms for `select`, `orderBy`, `expand`, and collection `filter`:

```java
client.people()
    .filter(p -> p.FIRST_NAME.equalTo("Scott"))
    .select(p -> p.FIRST_NAME, p -> p.LAST_NAME)
    .orderBy(p -> p.LAST_NAME.asc())
    .expand(p -> p.TRIPS.select(t -> t.NAME).top(2))
    .get();
```

The selector type is part of `NavQuery` and `CollectionProperty` (`Sel`), so each nested value carries the factory needed for the next lambda hop. Cross-entity member access is rejected by the generated selector fields and the `? super` request bounds.

## Sort Expressions

Every property expression implements `OrderExpression` through `asc()` and `desc()`:

```java
client.people().orderBy(Person.LAST_NAME.asc()).get();
```

`$select` accepts generated scalar and enum property expressions only. Collection-valued structural properties and navigation properties are not `PropertyExpression` values, so they cannot be passed to `select(...)`. A transformation such as `Person.FIRST_NAME.toUpper()` is valid in filters and order expressions, but passing it to `select(...)` raises `IllegalArgumentException`.

## Apply Expressions

`ApplyExpression.builder()` returns an `ApplyBuilder` with `filter`, `groupBy`, `aggregate`, `compute`, `orderBy`, `top`, and `skip`. `ApplyExpression.of(raw)` creates a raw expression. The generated collection request accepts either form through `apply(...)`.

## What's Next

- [HTTP Transport](http-transport.md)
- [Serialization](serialization.md)
