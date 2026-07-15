---
name: odata-codegen
description: Use when a developer wants to generate and use a type-safe OData v4 Java client with the modern-odata-client library. Covers Maven plugin setup, generated client API, querying with $filter/$select/$expand/$orderby, CRUD operations, batch requests, media streams, and common errors.
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
    <!-- Pick one: metadataUrl or metadataFile -->
    <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
    <!-- <metadataFile>src/main/resources/metadata.xml</metadataFile> -->
    <basePackage>com.example.generated</basePackage>
  </configuration>
</plugin>
```

Run code generation:

```bash
mvn odata-codegen:generate
```

The generated sources are written under the configured package. Add the `odata-codegen-runtime` dependency to use them:

```xml
<dependency>
  <groupId>io.github.akbarhusain</groupId>
  <artifactId>odata-codegen-runtime</artifactId>
  <version>${odata-codegen.version}</version>
</dependency>
```

## Generated Code Layout

For a namespace `TripPinService` with base package `com.example.generated`:

```
com.example.generated/trippin/
├── entity/                 # Person.java, Trip.java, Airline.java, ...
├── complex/                # Location.java, EventLocation.java, ...
├── enums/                  # Status.java, ...
├── entity.request/         # PersonEntityRequest.java, ...
├── collection.request/     # PersonCollectionRequest.java, ...
├── container/              # DefaultContainer.java
└── schema/                 # ServiceSchemaInfo.java
```

Use `DefaultContainer` as the entry point.

## Creating a Client

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);
```

Optional: add auth, custom `HttpTransport`, or a custom `Serializer`.

## Reading Data

### Get a collection

```java
CollectionPage<Person> people = client.people().get();
List<Person> page = people.currentPage();
```

### Type-safe $filter

```java
client.people()
    .filter(
        Person.FIRST_NAME.equalTo("Scott")
            .and(Person.LAST_NAME.startsWith("Ke"))
    )
    .get();
```

Supported operators depend on property type:

| Type | Example operators |
|------|-------------------|
| `StringProperty` | `equalTo`, `notEqualTo`, `contains`, `startsWith`, `endsWith`, `equalToIgnoreCase`, `concat` |
| `NumberProperty` | `equalTo`, `greaterThan`, `lessThan`, `between`, `multiply`, `negate` |
| `DateTimeProperty` | `equalTo`, `greaterThan`, `year`, `month`, `day`, `hour`, `date`, `time` |
| `BooleanProperty` | `equalTo`, `notEqualTo` |
| `EnumProperty` | `equalTo`, `notEqualTo` |

### $select, $expand, $orderby, $top, $skip

```java
client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .expand(Person.TRIPS.select(Trip.NAME).top(3))
    .orderBy(Person.LAST_NAME.ascending())
    .top(10)
    .skip(20)
    .get();
```

Expanded navigation data is materialized on the entity:

```java
Person person = client.peopleByUserName("scottketchum").get();
List<Trip> trips = person.getTrips(); // from $expand=Trips
```

### Count

```java
CollectionPage<Person> page = client.people().count(true).get();
long inlineCount = page.count().get();
long onlyCount   = client.people().countValue();
```

### Pagination

```java
CollectionPage<Person> first = client.people().top(50).get();
CollectionPage<Person> next  = client.people().nextPage(first.nextLink()).get();
```

## Writing Data

```java
Person newPerson = new Person() // or Person.builder() if enabled
    .withUserName("alice")
    .withFirstName("Alice")
    .withLastName("Smith");

Person created = client.people().post(newPerson);

// Update
Person patched = created.withFirstName("Alicia");
Person updated = client.peopleByUserName("alice").patch(patched);

// Delete
client.peopleByUserName("alice").delete();
```

For `patch`/`delete` against services like TripPin, fetch the entity first to obtain its `ETag`/`If-Match` header.

## Entity Keys

- **Single key** entities: `client.peopleByUserName("scottketchum")`
- **Composite key** entities: `client.orderDetailsByKey(Map.of("OrderID", 1, "ProductID", 5))`

## Batch Requests

```java
BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .add(BatchOperation.get("Airlines('AA')"))
    .addChangeset(new Changeset(List.of(
        BatchOperation.post("People", jsonForNewPerson),
        BatchOperation.post("Trips", jsonForNewTrip)
    )))
    .execute();

List<HttpResponse> results = response.parts();
```

Batch operations must use **absolute URIs**; relative URLs are rejected by many real services.

## Media Streams

For entities declared `HasStream="true"`:

```java
InputStream media = client.advertisementsById(guid).streamMedia();
```

For `Edm.Stream` named-stream properties:

```java
InputStream photo = client.personDetailsById(id).streamPhoto();
```

## Inheritance

If CSDL declares `BaseType`, generated subclasses extend the base Java class. Base-type query predicates can be reused on subtypes:

```java
// Flight extends PublicTransportation extends PlanItem
client.flights()
    .filter(PlanItem.STARTS_AT.greaterThan(someInstant))
    .get();
```

## Async vs Sync

The **runtime** (`HttpTransport.submit`, `EntityOperations.executeAsync`) is async-first and returns `CompletableFuture`. The **generated client** wraps those calls into synchronous, blocking methods (`get()`, `post()`, `patch()`, `delete()`, `countValue()`, `streamMedia()`). If you need async behavior, call the runtime directly or wrap generated calls in `CompletableFuture.supplyAsync`.

## Common Errors

| Error | Likely cause | Fix |
|-------|--------------|-----|
| `cannot find symbol ODataEntityType` | Missing `odata-codegen-runtime` dependency | Add the runtime dependency |
| `The 'odata.etag' instance or property annotation has a null value` | Serialized body includes null `@odata.etag` | Use the runtime serializer (default Jackson config omits null lifecycle fields) |
| `Sequence contains no matching element` on POST | Empty navigation arrays in body | Default serializer omits empty collections; avoid hand-building JSON bodies |
| `428 Precondition Required` on PATCH/DELETE | Missing `If-Match` header | GET the entity first, then use its ETag |
| `a base URI was not specified for the batch writer` | Relative URIs in batch operations | Use absolute URLs in `BatchOperation.*` |

## Key Things to Remember

- Generated request methods are **synchronous** and block until the HTTP call completes.
- Generated entities are **immutable data objects**. Mutations use `with*(value)` copy-on-write methods or the generated `Builder` (if enabled).
- Property constants are **UPPER_CASE**: `Person.FIRST_NAME`, not `Person.firstName`.
- Always use the generated request methods for HTTP operations; entities themselves do not hold transport state.
