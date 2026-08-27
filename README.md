# OData Codegen

A type-safe OData v4 client generator for Java. Parses CSDL XML metadata and generates immutable Java classes with compile-time validated query builders.

## Features

- **Type-safe query API** — Expression builders for `$filter`, `$select`, `$orderby`, `$expand`, `$apply` with compile-time validation
- **Immutable-by-contract entities** — Copy-on-write `with*()` methods; getters return unmodifiable collections and `Optional`; `patch()` sends **only the tracked changes** (partial updates)
- **Entity & complex-type inheritance** — Subtypes emit real Java `extends` clauses; base-type query predicates type-check against subtypes (e.g. `Flight` is a `PlanItem`, `EventLocation` is a `Location`); `@odata.type` payloads deserialize to the actual subtype
- **Nested `$expand`** — Type-safe `$expand=Trips($select=...;$filter=...;$orderby=...;$top=...;$skip=...;$count=...)` via `NavQuery`
- **Batch with changesets** — Atomic groups with batch-wide `Content-ID`s, `getByContentId()` result correlation, and the `continue-on-error` preference
- **Media streams** — `HasStream` entities (`.../$value`) and `Edm.Stream` named properties
- **Open types** — Dynamic properties captured into `unmappedFields` with typed coercion
- **Type-driven key literals** — `Edm.String` always quoted, `Edm.Guid` bare, durations `duration'...'`, enums `NS.Enum'Member'` — no value-shape guessing
- **Pluggable HTTP** — `HttpTransport` interface; built-in JDK `HttpClient` implementation (zero extra dependencies)
- **Pluggable serialization** — `Serializer` interface; Jackson by default
- **Typed exceptions** — `NotFoundException`, `UnauthorizedException`, `RateLimitException` (with parsed `Retry-After`), etc. — every exception carries the structured `ODataError` when the service sends one
- **ETag concurrency** — `If-Match` header support for optimistic locking
- **`$count` support** — Inline count (`$count=true`) and the plain `/$count` endpoint (`countValue()`)
- **`$ref` support** — Add/remove navigation property links
- **Async HTTP layer** — `HttpTransport` is `CompletableFuture`-based; generated request methods are synchronous on top of it

## Quick Start

### 1. Add Maven Plugin

```xml
<plugin>
    <groupId>io.github.akbarhusain.odata</groupId>
    <artifactId>odata-codegen-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
                <basePackage>com.example.trippin</basePackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. Use the Generated Client

```java
import io.github.akbarhusain.odata.runtime.entity.Context;
import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Person;
import com.example.trippin.entity.Trip;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

// Create context with base URL
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

// Create client
DefaultContainer client = new DefaultContainer(ctx);
```

## Usage Examples

### Collection Queries

```java
// Get all people
CollectionPage<Person> people = client.people().get();

// Filter with type-safe expressions
CollectionPage<Person> scott = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .get();

// Complex filters
CollectionPage<Person> results = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .skip(5)
    .get();

// Get total count
CollectionPage<Person> page = client.people()
    .count()
    .top(10)
    .get();
long totalPeople = page.count().orElse(0L);
```

### Entity Navigation

```java
// Get entity by key
PersonEntityRequest personReq = client.people("scottketchum");
Person person = personReq.get();

// Navigate to collection
CollectionPage<Trip> trips = personReq.trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .orderBy(Trip.BUDGET.desc())
    .get();

// Get first trip
Trip trip = personReq.trips().top(1).get().currentPage().get(0);
```

### Expand with Navigation

```java
// Simple expand
CollectionPage<Person> peopleWithTrips = client.people()
    .expand(Person.TRIPS)
    .top(5)
    .get();

// Nested $expand options (select / filter / top / orderBy on the expanded nav)
CollectionPage<Person> peopleNested = client.people()
    .expand(Person.TRIPS.select(Trip.NAME).top(5)
        .filter(Trip.BUDGET.greaterThan(500.0f)))
    .get();
```

### CRUD Operations

```java
// Create (POST) — returns the created entity
Person newPerson = Person.builder()
    .userName("newuser")
    .firstName("New")
    .lastName("User")
    .build();
Person created = client.people().create(newPerson);

// PATCH with ETag — copy-on-write tracks changes, so only FirstName is sent
PersonEntityRequest req = client.people("newuser");
Person existing = req.get();
String etag = existing.getETag().orElse(null);

Person updated = req.patchWithETag(
    existing.withFirstName("Updated"),
    etag
);

// DELETE (use deleteWithETag(etag) for concurrency-checked deletes)
req.delete();
```

### `$ref` — Manage Navigation Links

```java
// Add friend (target entity path is resolved to an absolute @odata.id URL)
client.people("scottketchum")
    .addFriendsRef("People('ronaldmundy')");

// Remove friend
client.people("scottketchum")
    .removeFriendsRef("People('ronaldmundy')");
```

### Error Handling

```java
import io.github.akbarhusain.odata.runtime.exception.*;

try {
    Person person = client.people("nonexistent").get();
} catch (NotFoundException e) {
    System.out.println("Person not found: " + e.getMessage());
} catch (UnauthorizedException e) {
    System.out.println("Authentication required");
} catch (RateLimitException e) {
    // getRetryAfter() is an Instant; hasServerRetryAfter() distinguishes
    // server-specified values from the client-side default
    System.out.println("Rate limited, retry at: " + e.getRetryAfter());
} catch (ODataException e) {
    System.out.println("OData error: " + e.getMessage());
}
```

### Custom HTTP Transport

```java
// Use the JDK HttpClient transport (zero extra dependencies)
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new JdkHttpTransport())
    .authProvider(new BearerAuthProvider("your-token"))
    .build();
```

## Architecture

```
odata-codegen/
├── odata-codegen-core/        # Parser + Generator (no runtime deps)
│   ├── model/                # CsdlModel records
│   ├── parser/               # StaxCsdlParser
│   └── generator/            # Names, Generator, EntityGenerator, etc.
├── odata-codegen-runtime/     # Runtime types (generated code depends on this)
│   ├── entity/               # ODataEntityType, ContextPath, Context
│   ├── query/                # Expression hierarchy (StringProperty, etc.)
│   ├── http/                 # HttpTransport, HttpRequest, HttpResponse
│   ├── auth/                 # AuthProvider
│   ├── serialization/        # Serializer interface
│   └── paging/               # CollectionPage
├── odata-codegen-maven-plugin/ # Maven plugin wrapper
└── odata-codegen-test/        # Integration tests
```

## Generated Code Structure

```java
// Entity — immutable by contract: protected fields populated via @JsonProperty setters
// (Jackson) or the Builder; copy-on-write with*() methods; unmodifiable getters
public final class Person implements ODataEntityType {
    public static final StringProperty<Person> FIRST_NAME = ...;
    public static final NumberProperty<Person, Integer> AGE = ...;
    public static final BooleanProperty<Person> IS_ACTIVE = ...;
    public static final DateTimeProperty<Person> BIRTHDAY = ...;
    public static final GuidProperty<Person> SHARE_ID = ...;
    public static final EnumProperty<Person, PersonGender> GENDER = ...;
    public static final CollectionProperty<Person, Trip, Trip.Filterable> TRIPS = ...;
    protected String userName;
    protected String firstName;
    // Builder, with*() methods, getters, typed Filterable for any()/all()
}

// Collection request (type-safe query building)
public final class PersonCollectionRequest {
    public PersonCollectionRequest filter(FilterExpression<Person> predicate);
    public PersonCollectionRequest select(PropertyExpression<? super Person, ?>... properties);
    public PersonCollectionRequest orderBy(OrderExpression<? super Person, ?>... expressions);
    public PersonCollectionRequest expand(NavProperty<? super Person, ?>... navs);
    public PersonCollectionRequest expand(NavProperty.NavQuery<? super Person, ?>... queries);
    public PersonCollectionRequest top(int count);
    public PersonCollectionRequest skip(int count);
    public CollectionPage<Person> get();
    public Person create(Person entity);
    public long countValue();               // GET /People/$count
    public PersonCollectionRequest nextPage(String nextLink);
    public PersonEntityRequest people(String userName);   // keyed overload
}

// Entity request (CRUD operations)
public final class PersonEntityRequest {
    public Person get();
    public Person patch(Person entity);           // partial when changes are tracked
    public Person patchWithETag(Person entity, String etag);
    public Person put(Person entity);             // full replace
    public void delete();
    public void deleteWithETag(String etag);
    public TripCollectionRequest trips();
}
```

## Documentation

Full documentation lives in [`docs/content/`](docs/content/) — tutorials, how-to guides,
concepts, and API reference:

- [Getting Started](docs/content/getting-started.md)
- [How-to Guides](docs/content/how-to/index.md) — filtering, CRUD, expand, batch, media, ETags, errors, auth, custom transports
- [Reference](docs/content/reference/maven-plugin.md) — Maven plugin configuration, generated-code structure, query/HTTP/serialization APIs
- [Release Notes](docs/content/release-notes.md)

## Development

```bash
./mvnw test                  # hermetic: offline unit/integration tests only
./mvnw test -Plive-tests     # everything, including the live services.odata.org suites
```

The build requires Java 17+ (`./mvnw` is the wrapped Maven — no local install needed).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
