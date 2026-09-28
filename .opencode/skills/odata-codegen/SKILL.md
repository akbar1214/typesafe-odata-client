---
name: odata-codegen
description: Use when a developer wants to generate and use a type-safe OData v4 Java client with the modern-odata-client library. Covers Maven plugin setup, generated packages, typed queries, CRUD, batch, media streams, operations, and current limitations.
---

# OData Codegen — End User Guide

Generate a type-safe OData v4 Java client from CSDL XML metadata.

## Quick Start

Add the Maven plugin to your project:

```xml
<plugin>
  <groupId>io.github.akbarhusain</groupId>
  <artifactId>odata-codegen-maven-plugin</artifactId>
  <version>${odata-codegen.version}</version>
  <configuration>
    <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
    <basePackage>com.example.trippin</basePackage>
    <generateWithMethods>true</generateWithMethods>
  </configuration>
</plugin>
```

Use `metadataFile` instead of `metadataUrl` for a local CSDL document. The plugin runs in the `generate-sources` phase and adds its output directory to the Maven source roots. Copy-on-write `with*()` methods are opt-in; `generateWithMethods` defaults to `false`.

Add the runtime dependency:

```xml
<dependency>
  <groupId>io.github.akbarhusain</groupId>
  <artifactId>odata-codegen-runtime</artifactId>
  <version>${odata-codegen.version}</version>
</dependency>
```

## Generated Code Layout

For a configured base package, generated Java packages and filesystem directories look like this:

```text
com/example/trippin/
├── entity/                 # Person.java, Trip.java, Airline.java
├── complex/                # Location.java, EventLocation.java
├── enums/                  # PersonGender.java
├── entity/request/         # PersonEntityRequest.java
├── collection/request/     # PersonCollectionRequest.java
├── operation/              # GetNearestAirportFunctionRequest.java
├── container/              # DefaultContainer.java
└── schema/SchemaInfo.java  # SchemaInfo.INSTANCE
```

The request packages are `entity.request` and `collection.request`; the directories are nested `entity/request` and `collection/request`. The per-output-package registry is `schema/SchemaInfo.java`, and polymorphic reads use `SchemaInfo.INSTANCE`.

## Creating a Client

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);
```

The context also accepts a custom `HttpTransport`, `Serializer`, `AuthProvider`, interceptors, and positive connect/read timeouts.

## Reading Data

### Get a collection

```java
CollectionPage<Person> people = client.people().get();
List<Person> page = people.currentPage();
```

`CollectionPage.getNextLink()`, `hasNextPage()`, `stream()`, and `toList()` operate on the returned page. `count()` on the page returns `Optional<Long>`.

### Type-safe filters

```java
CollectionPage<Person> people = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("Ke")))
    .get();
```

Property operations are type-specific. Common operations include:

| Property type | Operations |
|---------------|------------|
| `StringProperty` | `equalTo`, `notEqualTo`, `contains`, `startsWith`, `endsWith`, `matchesPattern`, `concat`, `toLower`, `toUpper`, `trim`, `length`, `indexOf`, `substring` |
| `NumberProperty` | comparisons, `add`, `subtract`, `multiply`, `divide`, `modulo`, `negate`, `ceiling`, `floor`, `round` |
| `DateTimeProperty` | comparisons, `year`, `month`, `day`, `hour`, `minute`, `second`, `date`, `time` |
| `BooleanProperty` | `equalTo`, `notEqualTo`, `isTrue`, `isFalse` |
| `GuidProperty` | validated `equalTo`, `notEqualTo`, `isNull`, `isNotNull` |
| `EnumProperty` | qualified-wire-name `equalTo`, `notEqualTo`, and `has` for flags enums |

Use `.asc()` or `.desc()` for ordering. `FilterExpression.of("...")` is the raw-expression escape hatch.

### Select, expand, order, and page

```java
CollectionPage<Person> people = client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .expand(Person.TRIPS.select(Trip.NAME).top(3))
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .skip(20)
    .get();
```

Generated navigation constants are `NavQuery` and `NavCollectionProperty` values, and request `expand(...)` accepts `Expandable`. A collection navigation can also be expanded bare and then read from the typed model getter:

```java
Person person = client.people("scottketchum")
    .expand(Person.TRIPS)
    .get();
List<Trip> trips = person.getTrips();
```

Selector-lambda forms are available for request `filter`, `select`, `orderBy`, and `expand`:

```java
CollectionPage<Person> people = client.people()
    .filter(p -> p.FIRST_NAME.equalTo("Scott"))
    .select(p -> p.FIRST_NAME, p -> p.LAST_NAME)
    .orderBy(p -> p.LAST_NAME.asc())
    .expand(p -> p.TRIPS.select(t -> t.NAME).top(2))
    .get();
```

### Count

`count()` is a request builder that adds `$count=true`; it does not take a Boolean and it does not execute by itself. `countValue()` is terminal and returns the count-only endpoint result.

```java
CollectionPage<Person> page = client.people().count().get();
Optional<Long> inlineCount = page.count();

long onlyCount = client.people().countValue();
long filteredCount = client.people()
    .filter(Person.CONCURRENCY.greaterThan(25L))
    .countValue();
```

### Pagination

```java
CollectionPage<Person> first = client.people().top(50).get();
if (first.hasNextPage()) {
    CollectionPage<Person> next = client.people()
        .nextPage(first.getNextLink())
        .get();
}
```

## Writing Data

Collection requests create entities. Keyed entity requests read, replace, patch, and delete them.

```java
Person newPerson = Person.builder()
    .userName("alice")
    .firstName("Alice")
    .lastName("Smith")
    .build();

Person created = client.people().create(newPerson);

Person replacement = Person.builder()
    .userName("alice")
    .firstName("Alicia")
    .lastName("Smith")
    .build();

Person replaced = client.people("alice").put(replacement);
Person patched = client.people("alice").patch(replacement);
client.people("alice").delete();
```

The generated methods are `create`, `put`, `patch`, and `delete`, with `putWithETag`, `patchWithETag`, and `deleteWithETag` variants for `If-Match`. A service may return an empty write response; the direct generated return value can then be `null`. Fetch an entity first when the service requires its ETag.

When `generateWithMethods` is enabled, a `with*()` call creates a copy and tracks the changed CSDL field for partial PATCH. Setters used after ordinary deserialization do not mark fields as changed and use full-body PATCH semantics.

## Entity Keys

Keyed entity-set accessors return the entity request directly:

```java
PersonEntityRequest person = client.people("scottketchum");
Order_DetailEntityRequest detail = client.order_Details(10248, 11);
```

Composite keys use one generated parameter per key component; they do not require a `Map`. A keyed navigation overload follows the same rule:

```java
Trip trip = client.people("scottketchum").trips(2).get();
```

Key literals are formatted from the CSDL Edm type.

## Batch Requests

The runtime batch API uses `multipart/mixed`. `BatchRequest` accepts absolute HTTP(S) targets or service-root-relative targets and resolves relative targets before sending.

```java
BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .add(BatchOperation.get("Airlines('AA')"))
    .addChangeset(new Changeset(List.of(
        BatchOperation.post("People", personBody),
        BatchOperation.post("Trips", tripBody)
    )))
    .execute();

List<BatchResult<?>> results = response.results();
```

A changeset must be non-empty and cannot contain GET. `BatchResponse` exposes correlated results through `results()`, `getByContentId(...)`, and typed views. `BatchRequest.executeAsync()` is available when an asynchronous runtime batch is needed.

## Media Streams

Stream methods are generated on entity requests returned by keyed accessors.

For a `HasStream="true"` entity:

```java
try (InputStream media = client.advertisements(advertisementId).streamMedia()) {
    byte[] bytes = media.readAllBytes();
}

client.advertisements(advertisementId).setMedia(content);
```

For an `Edm.Stream` named-stream property:

```java
try (InputStream photo = client.personDetails(personId).streamPhoto()) {
    byte[] bytes = photo.readAllBytes();
}

client.personDetails(personId).setPhoto(content);
```

The media entity URL ends in `/$value`; a named stream ends at its property segment. The caller owns and must close returned streams. Generated uploads currently buffer the complete `InputStream` into a `byte[]` before sending it.

## Operations

Function and action imports are request objects in the `operation` package. TripPin examples include:

```java
Airport airport = client.getNearestAirport(47.61357, -122.19375).execute();
client.resetDataSource().execute();
```

Single-valued generated operation requests may also expose `executeAsync()`; collection-valued and void operation requests expose their applicable synchronous result methods. Bound operations are available from keyed entity requests, for example `client.people("russellwhyte").shareTrip(...)`.

## Inheritance and Models

CSDL `BaseType` values generate Java inheritance. Base properties, keys, and navigations are available on subtype requests, and expanded navigation JSON is materialized into typed model fields. Entity and complex-type models are immutable-by-contract: collection getters are unmodifiable, nullable scalar/navigation getters return `Optional`, and copy-on-write methods are available when enabled.

## Runtime and Parser Support

- `HttpTransport` has two asynchronous methods: `submit(HttpRequest)` and `stream(HttpRequest)`.
- `JdkHttpTransport` is the only bundled transport implementation. It uses the JDK `HttpClient`, supports native PATCH, and uses a bounded daemon executor by default. Applications can provide a custom implementation of the two-method interface.
- `StaxCsdlParser` accepts OData v4 CSDL. OData v3 and unknown/non-CSDL roots fail loudly rather than producing an empty model.
- The runtime provides typed HTTP exceptions, structured `ODataError`, ETag helpers, multipart batch support, media streams, and pluggable Jackson serialization.

## Current Limitations

- Generated entity and collection CRUD/read methods are synchronous. Runtime transport, batch, and supported single-result operation requests expose asynchronous APIs separately.
- Streaming reads return a caller-owned `InputStream`; there is no cancellable-stream API.
- Media uploads are buffered and do not provide a streaming request-body publisher.
- Spatial and geography Edm types are represented as `Object`; typed geography/geometry model support is not implemented.
- Collection-bound operations are not emitted as typed request accessors.
- Generated entities expose public setters and no-argument construction for Jackson; application updates should prefer builders or enabled `with*()` methods.
- A replacement serializer must honor the generated Jackson annotations and OData JSON conventions; no Gson or JSON-B implementation is bundled.

## Common Errors

| Error | Likely cause | Fix |
|-------|--------------|-----|
| `cannot find symbol ODataEntityType` | Runtime dependency is missing | Add `odata-codegen-runtime` |
| `The 'odata.etag' ... has a null value` | A hand-built write body includes lifecycle metadata | Use the configured runtime serializer |
| `Sequence contains no matching element` on POST | A hand-built body contains empty navigation arrays | Avoid manually adding empty navigation collections |
| `428 Precondition Required` on PATCH/DELETE | Missing `If-Match` | Read the entity first and use the ETag variant |
| Unsupported v3 metadata | The parser supports OData v4 only | Convert the metadata to an OData v4 CSDL document |

## Key Things to Remember

- Generated request builders are separate from model objects; models do not hold a `Context`.
- Use the container's keyed overload for a keyed entity and the entity request's keyed overload for a keyed navigation.
- `count()` returns a request; `countValue()` executes and returns a `long`.
- Navigation constants are current `NavQuery`/`NavCollectionProperty` values, not request-level key accessors.
- Property constants are `UPPER_CASE`, such as `Person.FIRST_NAME`.
- Keep returned media streams inside a `try`-with-resources block.
