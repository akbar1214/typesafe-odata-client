# Invoke Functions and Actions

Container function imports (GET semantics) and action imports (POST semantics) are
generated as typed request classes in the `.operation` package. The container exposes
one accessor per import; call `execute()` (or `executeAsync()`) on the returned request
to invoke the operation.

## Bound Operations

Operations whose **binding parameter** (the first `Parameter`) is an entity type
surface as accessors on that entity's request — with the entity's keyed path as the
invocation context. They compose with the keyed accessor API (decision 95):

```java
// Bound action on Person — POST People('russellwhyte')/ShareTrip
client.people("russellwhyte").shareTrip("friend", 1).execute();

// Bound function on Trip — GET Trips(1)/GetInvolvedPeople
client.people("russellwhyte").trips(1).getInvolvedPeople().execute();

// Result handling matches imports: Optional<T> for nullable returns,
// List<T> for collections, typed entities/complexes otherwise
List<Trip> trips = client.people("russellwhyte").getFriendsTrips("russellwhyte").execute();
```

Ops declared on a **base type** also surface on subtype requests, with a type-cast
segment in the URL (`.../Flight('x')/NS.PlanItem/Op` when the op is bound to
`PlanItem`). Same-name bound functions overload by parameter names (per-overload
accessors like `getByName(...)`); identical parameter-name lists and duplicate
same-name actions fail at generation. Collection-bound operations
(`Collection(NS.Document)` binding) are not yet supported.

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

Edm primitives and enums embed inline in the invocation URL. Collection parameters
(`Collection(Edm.String)` → `List<String>`, `Collection(NS.Color)` → `List<Color>`)
cannot be inline literals, so OData passes them as **parameter aliases** — the URL
becomes `ByTags(tags=@p0)?@p0=['a','b']`, built for you from the typed list:

```java
// given <Function Name="ByTags"><Parameter Name="tags" Type="Collection(Edm.String)"/>
Integer hits = client.byTags(List.of("hiking", "surfing")).execute();
// GET .../ByTags(tags=@p0)?@p0=['hiking','surfing']
```

Nullable collection parameters may be passed null (both the pair and the alias are
omitted); required ones throw at construction when null.

Structured parameters — complex types (and entities) as single values or collection
elements — also ride **parameter aliases**, with the alias value being the serialized
JSON of the instance (URL Conventions §5.1.1 requires complex parameter values to use
aliases):

```java
// given <Function Name="Near"><Parameter Name="addr" Type="NS.Address"/>
Address here = new Address().withStreet("1 Main St").withCity("Springfield");
int hits = client.near(here).execute();
// GET .../Near(addr=@p0)?@p0={"Street":"1 Main St","City":"Springfield"}

// Collection(NS.Address) → List<Address>, one JSON array alias
int visited = client.visitAll(List.of(a, b)).execute();
// GET .../VisitAll(addrs=@p0)?@p0=[{...},{...}]
```

Nullable structured parameters may be passed null (pair and alias omitted);
required ones throw at construction when null.

### Overloaded Functions

OData identifies an unbound function overload by its parameter names, so one import
can expose several same-name overloads. Each generates its own request class and
container accessor, suffixed by the overload's parameter names:

```java
// IsSiteAdmin(username: Edm.String) and IsSiteAdmin(userId: Edm.String)
Boolean byName = client.isSiteAdminByUsername("scottketchum").execute();
Boolean byId   = client.isSiteAdminByUserId("u-123").execute();
```

Overloads are identified by the **binding parameter type** plus the **ordered set of
parameter types** (OData CSDL overload rules). Same-name overloads with different
parameter types — or bound to different types in an inheritance hierarchy — are legal
and generate distinct request classes/accessors; a derived-type request sees an
ancestor-bound overload via its cast segment. Only overloads identical in parameter
names AND types (invalid CSDL) fail generation; same-name unbound actions also fail
generation (actions cannot be overloaded by parameter names).

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
