# OData Codegen

A type-safe OData v4 client generator for Java. OData Codegen parses CSDL XML metadata and generates Java model classes, request classes, operation requests, and a schema registry with compile-time checked query expressions.

## Features

- **Type-safe query API** — `FilterExpression`, `PropertyExpression`, `OrderExpression`, `Expandable`, and `ApplyExpression` cover `$filter`, `$select`, `$orderby`, `$expand`, and `$apply`.
- **Immutable-by-contract models** — generated model classes expose protected fields, typed Jackson setters, immutable collection views, and copy-on-write `with*()` methods when `generateWithMethods` is enabled.
- **Entity and complex-type inheritance** — CSDL base types become Java `extends` relationships, and base-type properties remain usable in subtype queries.
- **Nested `$expand`** — `NavQuery` and generated `NavCollectionProperty` constants provide type-safe nested options.
- **Batch requests** — `multipart/mixed` requests support standalone operations, atomic changesets, batch-wide Content-ID assignment across changesets, and response correlation.
- **Media streams** — `HasStream` entities expose `streamMedia()` / `setMedia(...)`; `Edm.Stream` properties expose named-stream methods.
- **Open types** — undeclared JSON properties are captured in `unmappedFields` and can be read with typed conversion helpers.
- **Pluggable HTTP and serialization** — `HttpTransport` is asynchronous; `JdkHttpTransport` is the built-in implementation, and Jackson is the default serializer.
- **Typed exceptions** — HTTP failures are mapped to specific runtime exceptions, with the parsed `ODataError` available through `ODataException.getError()`.
- **ETag support** — conditional `patchWithETag`, `putWithETag`, and `deleteWithETag` methods send `If-Match` when a non-empty ETag is supplied.
- **Pagination and aggregation** — inline `count()`, count-only `countValue()`, server-driven `nextPage(...)`, `$search`, and `ApplyExpression` pipelines are available on collection requests.

## Quick Start

### 1. Add the Maven plugin

The project version is currently `0.1.0-SNAPSHOT`. Use the same version for the plugin and runtime when building against this checkout. Because the snapshot is not published, run `./mvnw -DskipTests install` in this repository before consuming it from another Maven project.

```xml
<plugin>
    <groupId>io.github.akbarhusain</groupId>
    <artifactId>odata-codegen-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
                <basePackage>com.example.trippin</basePackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. Add the runtime dependency

```xml
<dependency>
    <groupId>io.github.akbarhusain</groupId>
    <artifactId>odata-codegen-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 3. Create a client

```java
import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Person;
import com.example.trippin.entity.Trip;
import com.example.trippin.entity.request.PersonEntityRequest;
import com.example.trippin.entity.request.TripEntityRequest;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);
CollectionPage<Person> people = client.people().get();
PersonEntityRequest personRequest = client.people("scottketchum");
Person person = personRequest.get();
```

## Usage Examples

### Collection queries

```java
CollectionPage<Person> page = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .count()
    .get();

long total = page.count().orElse(0L);
```

`CollectionPage<T>` is iterable and also exposes `currentPage()`, `toList()`, `stream()`, `hasNextPage()`, `getNextLink()`, and `count()`.

### Entity requests and navigation

```java
PersonEntityRequest request = client.people("scottketchum");
Person person = request.get();

var trips = request.trips()
    .filter(com.example.trippin.entity.Trip.BUDGET.greaterThan(500.0f))
    .get();

TripEntityRequest tripRequest = request.trips(1);
Trip trip = tripRequest.get();
```

Entities contain data only. HTTP execution is performed by generated collection and entity request objects.

### Nested `$expand`

```java
var people = client.people()
    .expand(Person.TRIPS.select(com.example.trippin.entity.Trip.NAME).top(5))
    .get();
```

Expanded collection navigation values are available from typed model getters such as `person.getTrips()`.

### CRUD

```java
Person newPerson = Person.builder()
    .userName("newuser")
    .firstName("New")
    .lastName("User")
    .build();

client.people().create(newPerson);
Person replacement = Person.builder()
    .userName("newuser")
    .firstName("Updated")
    .build();

// A create response does not reliably carry an ETag, so read the entity back to obtain
// the current one before a conditional PATCH/DELETE (strict services return 428 without If-Match).
Person current = client.people("newuser").get();
client.people("newuser").patchWithETag(replacement, current.getETag().orElseThrow());

Person updated = client.people("newuser").get();
client.people("newuser").deleteWithETag(updated.getETag().orElseThrow());
```

The collection `create(...)` method performs POST. Entity requests perform GET, PATCH, PUT, DELETE, and `$ref`; media methods are added when the metadata declares `HasStream="true"` or an `Edm.Stream` property. `with*()` methods are generated when the plugin parameter `<generateWithMethods>true</generateWithMethods>` is enabled; the test module enables it.

### Navigation links (`$ref`)

```java
client.people("scottketchum")
    .addFriendsRef("People('ronaldmundy')");
client.people("scottketchum")
    .removeFriendsRef("People('ronaldmundy')");
```

Relative target paths are resolved against the service root before the request is sent; absolute HTTP(S) targets are accepted unchanged.

### Errors

```java
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.exception.ODataError;
import io.github.akbarhusain.odata.runtime.exception.ODataException;

try {
    client.people("missing").get();
} catch (NotFoundException e) {
    ODataError error = e.getError();
    System.out.println(error == null ? e.getMessage() : error.getMessage());
} catch (ODataException e) {
    System.out.println(e.getStatusCode() + ": " + e.getMessage());
}
```

### Authentication and transport

```java
import io.github.akbarhusain.odata.runtime.auth.BearerAuthProvider;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new JdkHttpTransport())
    .authProvider(new BearerAuthProvider(() -> "token"))
    .connectTimeout(java.time.Duration.ofSeconds(15))
    .readTimeout(java.time.Duration.ofSeconds(45))
    .build();
```

`BearerAuthProvider` and `ApiKeyAuthProvider` accept `Supplier<String>` values. The runtime includes the JDK transport; third-party HTTP clients are supplied by the application through `HttpTransport`.

## Generated Code Layout

For `basePackage` `com.example.trippin` and a TripPin schema, the output is organized as follows:

```text
com/example/trippin/
├── entity/                  # Person.java, Trip.java, ...
├── complex/                 # Location.java, City.java, ...
├── enums/                   # PersonGender.java, ...
├── entity/request/          # PersonEntityRequest.java, ...
├── collection/request/      # PersonCollectionRequest.java, ...
├── operation/               # GetNearestAirportFunctionRequest.java, ...
├── container/               # DefaultContainer.java
└── schema/                  # SchemaInfo.java
```

The generated `SchemaInfo` class is named `SchemaInfo` and exposes `SchemaInfo.INSTANCE`. A schema registry is emitted once per output package, even when several schemas share that package.

## Documentation

- [Getting Started](docs/content/getting-started.md)
- [How-to Guides](docs/content/how-to/index.md)
- [Generated Code Structure](docs/content/reference/generated-code.md)
- [Maven Plugin Configuration](docs/content/reference/maven-plugin.md)
- [Release Notes](docs/content/release-notes.md)

## Development

```bash
./mvnw test
./mvnw test -Plive-tests
```

The default test run is hermetic and excludes the `live-service` tag. The project targets Java 17 or later.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
