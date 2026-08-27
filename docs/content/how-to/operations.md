# Invoke Functions and Actions

Container function imports (GET semantics) and action imports (POST semantics) are
generated as typed request classes in the `.operation` package. The container exposes
one accessor per import; call `execute()` (or `executeAsync()`) on the returned request
to invoke the operation.

## Function Imports

TripPin models `GetNearestAirport` as a composable function import taking `lat`/`lon`
(`Edm.Double`) and returning an `Airport`. Parameters are embedded in the URL fragment
as OData literals — strings quoted with inner quotes doubled, numbers/Guids/dates bare,
enums qualified:

```java
Airport airport = client.getNearestAirport(47.61357, -122.19375).execute();
```

Async:

```java
CompletableFuture<Airport> airport = client.getNearestAirport(47.61357, -122.19375).executeAsync();
```

### Nullable Returns

A nullable return type renders as `Optional<T>` in both sync and async forms:

```java
Optional<String> maybe = client.someFunction().execute();
CompletableFuture<Optional<String>> asyncMaybe = client.someFunction().executeAsync();
```

Non-nullable returns render as the bare type.

## Action Imports

Actions POST a JSON parameter body. TripPin's parameterless void action becomes:

```java
client.resetDataSource().execute();   // POST .../ResetDataSource, no body
```

Parameterized actions take typed constructor parameters; nullable parameters may be
passed null and are omitted from the body. Non-nullable reference parameters throw at
construction when passed null. To illustrate on a service that exposes a parameterized
action import (TripPin's only action import is the parameterless `ResetDataSource`):

```java
// given an ActionImport "RateTrip" for action RateTrip(tripId: Edm.Int32, note: Edm.String)
Integer result = client.rateTrip(7, "great views").execute();
```

Structured parameters (complex types, entities) and collection parameters
(`Collection(Edm.String)` → `List<String>`) serialize into the JSON body. JSON body
keys use the CSDL parameter names, so parameters like `new-name` keep their wire
spelling even though the Java parameter is sanitized to `new_name`.

## Function Parameter Rules

Only Edm primitives and enums can be embedded in an invocation URL, so function-import
parameters are validated at generation time: a structured or collection-typed function
parameter fails generation with the import and parameter named (use an action instead —
actions accept any parameter type in their JSON body).

## Return-Kind Matrix

| CSDL return type | `execute()` | Execution path |
|---|---|---|
| *(none)* | `void` | `invokeVoidSync` — errors only |
| `Edm.Int32`, `Edm.String`, … | primitive (nullable → `Optional<T>`) | unwraps `{"value": x}` (control annotations like `@odata.context` tolerated) and bare literals |
| `Collection(Edm.String)` | `List<T>` | `{"value": [...]}` parsing |
| entity | typed class (nullable → `Optional<T>`) | entity at the JSON root, polymorphic via `@odata.type` |
| complex / enum | typed class (nullable → `Optional<T>`) | value-wrapped: unwraps `{"value": {...}}`, polymorphic via `@odata.type` |
| `Collection(Entity)`, `Collection(Complex)` | `List<T>` | collection deserialization, polymorphic elements via `@odata.type` |

Collection results return a plain list (empty on absent responses) — paging through a
function result is future work.

## Error Semantics

Invocations surface the standard typed exceptions (`NotFoundException`,
`PreconditionRequiredException`, …) via the shared response checker; async variants
deliver failures through the returned future only.
