# Invoke Functions and Actions

Function and action imports generate final request classes in the `operation` package. The generated container exposes typed accessors. The returned request object owns the operation URL, parameters, and execution method.

## Function Imports

TripPin's `GetNearestAirport` import is a GET request with typed URL parameters:

```java
Airport airport = client.getNearestAirport(47.61357, -122.19375).execute();
```

A generated request with a single function result also exposes an asynchronous variant:

```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<Airport> future =
    client.getNearestAirport(47.61357, -122.19375).executeAsync();
```

A nullable return is represented as `Optional<T>`; a non-nullable return uses its Java type directly. Collection results are returned as `List<T>` and are empty rather than null when the response has no elements.

## Action Imports

Actions use POST and send structured action parameters as a JSON body. TripPin's parameterless `ResetDataSource` action is:

```java
client.resetDataSource().execute();
```

A parameterized action receives its parameters in the generated request constructor. Nullable parameters can be omitted; non-nullable reference parameters are checked at construction. JSON property names use the original CSDL parameter names even when Java identifiers were sanitized.

## Bound Operations

An operation whose first parameter binds to an entity type is exposed through that entity's request class. The binding parameter is not repeated in the generated invocation signature.

```java
client.people("russellwhyte")
    .shareTrip("friend", 1)
    .execute();

client.people("russellwhyte")
    .trips(1)
    .getInvolvedPeople()
    .execute();

List<Trip> trips = client.people("russellwhyte")
    .getFriendsTrips("russellwhyte")
    .execute();
```

Bound operations are addressed with their namespace-qualified operation name. An operation bound to an ancestor is reached from a subtype request through a type-cast segment in the generated path.

Same-name function overloads receive deterministic suffixes derived from their non-binding parameter names. If overloaded functions have the same parameter names but different ordered qualified parameter types, `_2`/`_3` suffixes distinguish them; overloads identical in both names and resolved types fail generation. Same-name bound actions with the same binding type fail generation, while distinct binding types can be represented by separate request accessors. Collection-bound operations are not currently surfaced as typed request accessors.

## Function Parameter Transport

Scalar primitive and enum parameters are embedded in the invocation URL. Collection and structured parameters use OData parameter aliases because they cannot be represented as a single inline literal.

For a function import named `search` with a `Collection(Edm.String)` parameter, the generated request takes `List<String>` and renders a form such as:

```text
search(tags=@p0)?@p0=['hiking','surfing']
```

Structured parameters use JSON aliases. For an import named `findNearby` with a parameter declared as `NS.Address`, the generated request takes the generated `Address` type and renders a form such as:

```text
findNearby(addr=@p0)?@p0={"Street":"1 Main St","City":"Springfield"}
```

Collection elements use a JSON array alias. Nullable structured parameters omit both the parameter pair and its alias when null.

## Return Handling

The generator chooses the runtime invocation path from the CSDL return kind:

| Return kind | Generated result |
|--------------|------------------|
| no return | `void execute()` |
| primitive | boxed Java type such as `Integer`, `Long`, or `Double`, or `Optional<T>` when nullable |
| entity | generated entity type, optionally `Optional<T>` |
| complex or enum | generated type, with the `{"value": ...}` envelope unwrapped |
| collection of primitives | `List<T>` |
| collection of entities or complex values | `List<T>` with polymorphic reads where applicable |

Operation errors use the same typed exceptions as ordinary requests. Asynchronous methods, when generated, deliver failures through the returned `CompletableFuture`.

## What's Next

- [Error Handling](error-handling.md)
- [Generated Code Structure](../reference/generated-code.md)
- [OData URL Patterns](../reference/odata-urls.md)
