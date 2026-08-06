# Improvement Backlog

## High-impact, lower-effort

### 1. Narrow `select`, `orderBy`, and `expand` to entity-scoped types ✅
`filter()` already uses `FilterExpression<? super E>` (so base-type predicates work on subtypes), but the other query methods were too permissive:

```java
// Now fails to compile (as it should):
client.people().select(Trip.NAME);
client.people().orderBy(Trip.BUDGET.desc());
client.people().expand(Trip.FLIGHTS);
```

**Change made:**
- Added an entity type parameter to `OrderExpression<E, T>` and `PropertyExpression<E, T>`.
- Generated collection requests now accept:
  ```java
  select(PropertyExpression<? super E, ?>... properties)
  orderBy(OrderExpression<? super E, ?>... expressions)
  expand(NavProperty<? super E, ?>... properties)
  expand(NavProperty.NavQuery<? super E, ?>... queries)
  ```
- `NavProperty<E, T>` and `NavQuery<S, T>` carry source/target entity types so nested `$expand` options are also type-checked.

This is a compile-time-only change; correct code keeps working. It directly extends the type-safety philosophy already in `filter()`.

**Tests:** `RequestGeneratorNarrowQueryTest` (5) + `QueryTypeSafetyCompilationTest` (1).

### 2. Sync query API with documentation ✅
Several methods were documented in `query-api.md` / `filter.md` but missing in code:

| Documented | Status |
|---|---|
| `StringProperty.concat` | ✅ Implemented |
| `DateTimeProperty.year/month/day/hour/minute/second/date/time` | ✅ Implemented |
| `NumberExpression.negate` | ✅ Implemented |
| `BooleanProperty.notEqualTo` | ✅ Implemented (via #3) |
| `CollectionProperty.contains(value)`, `length()` | ✅ Implemented |

**Change made:**
- `StringProperty`: added `concat(String)` and `concat(StringProperty)`. (`equalToIgnoreCase`/`notEqualToIgnoreCase` were subsequently removed — they are non-standard OData wrappers; use `StringProperty.toLower().equalTo(...)` instead.)
- `DateTimeProperty`: added `year()`, `month()`, `day()`, `hour()`, `minute()`, `second()` returning `NumberExpression<Integer, E>`, and `date()` / `time()` returning `DateTimeProperty<E>`.
- `NumberExpression`: added `negate()` returning `NumberExpression<N, E>`.
- `BooleanProperty`: `notEqualTo(boolean)` / `notEqualTo(Boolean)` were already added in #3.
- `CollectionProperty`: added `contains(T value)` (with type-aware string quoting) and `length()` returning `NumberExpression<Integer, E>`.

**Tests:** `StringPropertyTest` (5), `DateTimePropertyTest` (8), `NumberExpressionTest` (1), `CollectionPropertyTest` (3).

### 3. Fix null handling in property comparisons ✅
Behavior was inconsistent:
- `StringProperty.equalTo(null)` -> NPE inside `escape(value)`
- `NumberProperty.equalTo(null)` -> silently rendered `Field eq null`
- `DateTimeProperty.equalTo(null)` -> NPE
- `BooleanProperty`/`EnumProperty` -> NPE

**Change made:**
- Added null-safe overloads that route to `isNull()` / `isNotNull()`:
  - `StringProperty.equalTo(null)` / `notEqualTo(null)`
  - `NumberExpression.equalTo(null)` / `notEqualTo(null)` (covers `NumberProperty`)
  - `DateTimeProperty.equalTo(null)` / `notEqualTo(null)`
  - `BooleanProperty.equalTo(Boolean)` / `notEqualTo(Boolean)` (new overloads; primitive `equalTo(boolean)` unchanged)
  - `EnumProperty.equalTo(null)` / `notEqualTo(null)`

**Tests:** `StringPropertyTest` (2), `NumberExpressionTest` (2), `DateTimePropertyTest` (2), `BooleanPropertyTest` (2), `EnumPropertyTest` (2).

### 4. Add missing exception types ✅
`error-handling.md` lists `PreconditionFailedException (412)` and `ServerException (5xx)`, but the runtime only had:
`BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`, `RateLimitException`.

**Change made:**
- Added `PreconditionFailedException` for HTTP 412 (ETag/precondition failures).
- Added `ServerException` for HTTP 5xx, preserving the actual status code via `getStatusCode()`.
- Updated `ODataException.fromResponse()` to map 412 → `PreconditionFailedException` and 500-599 → `ServerException`.
- Updated `release-notes.md` to list the new exception types.

**Tests:** `ODataExceptionTest` (4: 412, 500, 503, 418).

## Medium effort

### 5. Typed collection lambdas (`any` / `all`) ✅
Today `CollectionProperty.FilterableElement` only offers stringly-typed field accessors:

```java
Person.TRIPS.any(trip -> trip.stringField("Budgt").equalTo("x")) // compiles, wrong OData
```

A generated per-entity filterable class now allows:

```java
Person.TRIPS.any(trip -> trip.BUDGET.greaterThan(500f))
```

**Change made:**
- Added a third type parameter to `CollectionProperty<E, T, F>` where `F` is the filterable type.
- `any`/`all` now accept `Function<F, FilterExpression<T>>`.
- Generated entities and complex types expose a `public static class Filterable` with typed property fields prefixed by the lambda variable (`x/`).
- Collection navigation properties and collection structural properties use the target type's `Filterable` factory.
- Primitive collections still fall back to `CollectionProperty.FilterableElement<T>`.

**Tests:** `EntityGeneratorFilterableTest` (5) + `CollectionPropertyTypedLambdaTest` (2).

### 6. Pagination helpers ✅
`docs/content/how-to/pagination.md` showed `client.people().nextPage(nextLink.get()).get()`, but no `nextPage(String)` method existed. Also, `.count()` was documented as `GET /People/$count`, but the generator emitted `$count=true`.

**Change made:**
- Generated collection requests now expose `nextPage(String nextLink)`, which resolves absolute or relative OData `@odata.nextLink` values via `ContextPath.fromNextLink(String)`.
- Added `countValue()` on generated collection requests for the count-only endpoint (`GET /EntitySet/$count`), returning `long`.
- Kept `count()` as the inline count option (`$count=true`) and updated the docs to explain the difference.
- Added `ContextPath.addCountSegment()` so that `/$count` is appended to the resource path before query parameters (e.g. `/People/$count?$filter=Age%20gt%2025`).

**Tests:** `RequestGeneratorPaginationTest` (4) + `ContextPathTest` additions (7) + `EntityOperationsCountTest` (4).

### 7. Surface OData error JSON in exceptions ✅
`ODataError.fromResponse()` existed but was stored separately in each typed exception. The base `ODataException` did not carry it, so unmapped status codes lost the structured error and callers had to parse `getMessage()`.

**Change made:**
- Moved `ODataError` into the base `ODataException` with a public `getError()` getter.
- Added `ODataException` constructors that accept an `ODataError`.
- Updated `ODataException.fromResponse()` to parse `ODataError` once and pass it into every typed exception constructor (including the generic fallback for 5xx and other unmapped codes).
- Removed the duplicated `error` field from `BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`, and `RateLimitException`; they now inherit `getError()` from `ODataException`.

**Tests:** `ODataExceptionTest` additions (4).

## Larger effort

### 8. Wide-entity `@JsonAnySetter` unsafe casts ✅
For entities with >252 parameters, the generator emitted direct casts like `(SomeComplexType) value` in the `@JsonAnySetter` switch. Jackson passes `LinkedHashMap` for nested objects, so complex-type or enum properties threw `ClassCastException`.

**Change made:** Removed the normal/wide hybrid entirely. All entities and complex types now use a public no-args constructor + `@JsonProperty` setters for Jackson deserialization. This eliminates the 255-parameter concern, removes the unsafe casts, and lets Jackson handle nested object/collection conversion automatically. Collection getters were made null-safe (`field == null ? List.of() : Collections.unmodifiableList(field)`) so unset collections serialize cleanly.

**Tests:** `EntityGeneratorSimplifiedDeserializationTest` (5) + `ComplexTypeGeneratorSimplifiedDeserializationTest` (4). `WideEntityGeneratorTest` was removed.

### 9. Generator duplication ✅
`EntityGenerator` and `ComplexTypeGenerator` duplicate base-type walking, inherited property/nav resolution, import collection, `with*` generation, and reserved-word handling. A shared `AbstractTypeGenerator` base would reduce maintenance and keep behavior consistent.

**Change made:**
- Introduced `AbstractTypeGenerator` as the shared base for both generators.
- Moved duplicated helpers into the base: type resolution (`resolvePropertyJavaType`, `resolveSingleJavaType`, `resolveTypeDefinition`), cross-schema package resolution (`basePackageForType`), import collection (`addPropertyImports`, `addNavImports`), navigation-property helpers (`navJavaType`, `navGetterName`, `navWithMethod`, `generateNavGetter`), and typed `Filterable` inner-class generation for collection lambdas.
- `EntityGenerator` and `ComplexTypeGenerator` now extend the base and call `super(...)` in their constructors.
- Inheritance walking, lifecycle-field handling, `with*`/`Builder` generation, and open-type specifics remain subclass-specific because they differ meaningfully between entities and complex types.
- Generated output remains byte-identical.

**Tests:** Full `odata-codegen-core` test suite (122 tests) plus cross-module reactor (450 tests).

### 10. Maven plugin incremental build ✅
`GenerateMojo` always parsed metadata and regenerated every file, even when the source metadata had not changed.

**Change made:**
- Added `skip` parameter (`-Dodata.skip=true`) to bypass generation entirely.
- Added `forceRegenerate` parameter (`-Dodata.forceRegenerate=true`) to override incremental behavior.
- Implemented MD5-based incremental build: the plugin writes a `.odata-generation-marker` file in the output directory containing the metadata hash. On subsequent runs, if the hash matches and generated `.java` files exist, generation is skipped.
- For `metadataUrl`, metadata is still downloaded but now cached to a temp file so it can be hashed before parsing.

**Tests:** `GenerateMojoIncrementalTest` (5).

## Recommended order
1. ~~#1 (narrow select/orderBy/expand)~~ ✅ Done
2. ~~#2 (missing query methods)~~ ✅ Done
3. ~~#3 (null handling)~~ ✅ Done
4. ~~#4 (missing exception types)~~ ✅ Done
5. ~~#5 (typed collection lambdas)~~ ✅ Done
6. ~~#6 (pagination helpers)~~ ✅ Done
7. ~~#7 (surface OData error JSON in exceptions)~~ ✅ Done
8. #8 (simplified deserialization) — done, wide-entity path removed
9. ~~#9 (generator duplication)~~ ✅ Done
10. ~~#10 (Maven plugin incremental build)~~ ✅ Done
