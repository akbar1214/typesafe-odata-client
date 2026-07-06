# Modern OData Client — Project Plan

## What We're Improving Over the Reference (davidmoten/odata-client)

| # | Area | Reference Weakness | Our Improvement |
|---|------|-------------------|-----------------|
| 1 | **Query API** | `$filter`, `$select`, `$orderby` are raw strings | Type-safe expression builders with compile-time validation |
| 2 | **Immutability** | `protected` non-final fields; mutable `UnmappedFields`; `changedFields = null` after patch | Truly immutable with `final` fields; copy-on-write semantics |
| 3 | **HTTP Layer** | God interface; string URLs; inconsistent impls; no async | Narrow interfaces; typed URL; pluggable async support |
| 4 | **Error Handling** | Single `ClientException` for all errors; no OData error parsing | Typed exceptions (`NotFoundException`, `RateLimitException`); parse OData error responses |
| 5 | **Schema Info** | Enum singleton; can't mock or reload | Class-based `SchemaInfo`; testable; supports multiple instances |
| 6 | **Authentication** | MS Graph-specific; only client credentials | `AuthProvider` interface; pluggable auth strategies |
| 7 | **Serialization** | Jackson-hardcoded on every entity | `Serializer` interface; model is annotation-free; serializer plugs in |
| 8 | **Missing Features** | No batch, no ETag, no async, no middleware | Batch support; ETag concurrency; interceptor pattern; async via CompletableFuture |
| 9 | **Generated Code** | Mutable fields; `@JacksonInject` coupling; manual `_copy()` | Clean final fields; no framework annotations on model; proper `equals`/`hashCode` |
| 10 | **Paging** | No cancellation; thread-unsafe `AtomicReference` | Cancellable `Stream<T>`; thread-safe design |

---

## Module Structure

```
modern-odata-client/
├── pom.xml
├── AGENTS.md                     # Design decisions, lessons learned
├── ODATA.md                      # OData v4 knowledge reference
├── README.md                     # Usage examples
├── odata-client-core/            # Parser + Generator (no runtime deps)
│   └── src/main/java/.../core/
│       ├── model/                # CsdlModel records
│       ├── parser/               # StAX CSDL parser
│       └── generator/            # Code generator engine
├── odata-client-runtime/         # Runtime library (generated code depends on this)
│   └── src/main/java/.../runtime/
│       ├── entity/               # ODataEntityType, Context, ContextPath
│       ├── query/                # Type-safe filter/select/orderby/expand
│       ├── http/                 # HttpTransport + implementations
│       ├── auth/                 # AuthProvider interface
│       ├── serialization/        # Serializer interface
│       ├── paging/               # CollectionPage
│       ├── exception/            # Typed exceptions
│       └── internal/             # RequestHelper
├── odata-client-maven-plugin/    # Maven plugin (generate goal)
└── odata-client-test/            # Integration tests
```

---

## Milestone 1: Core Parser + Model ✅ COMPLETE

**Goal:** Parse OData v4 CSDL XML into a validated in-memory model.

- [x] Maven multi-module project with parent POM (Java 17+, target Java 16+ records)
- [x] StAX-based CSDL parser (not JAXB — avoids XSD dependency, handles namespace variations)
- [x] Internal model classes using Java records (29 record types)
- [x] Unit tests: parse TripPin metadata, parse Northwind metadata, verify model correctness (27 tests)

---

## Milestone 2: Type-Safe Query API + Code Generator ✅ COMPLETE

**Goal:** Generate type-safe entity classes with expression builders for filter/select/orderby/expand.

- [x] Expression type hierarchy: `FilterExpression`, `OrderExpression`, `StringProperty`, `NumberProperty`, `BooleanProperty`, `EnumProperty`, `CollectionProperty`, `NavProperty`
- [x] Generated entity classes with static property constants (UPPER_CASE)
- [x] Generated collection/entity request classes with type-safe query methods
- [x] Generator engine: `Names.java`, `Generator.java`, `EntityGenerator.java`, `RequestGenerator.java`, `EnumGenerator.java`, `ComplexTypeGenerator.java`, `ContainerGenerator.java`, `SchemaInfoGenerator.java`
- [x] Test: generate TripPin client (34 files) → verify compilation → verify content (29 tests)

---

## Milestone 3: HTTP Abstraction + Runtime ✅ COMPLETE

**Goal:** Clean HTTP layer with pluggable backends and async support.

- [x] `HttpTransport` interface with `CompletableFuture<HttpResponse>`
- [x] `JdkHttpTransport` (zero deps), `ApacheHttpTransport` (optional)
- [x] `Serializer` interface + `JacksonSerializer` implementation
- [x] `AuthProvider` interface + `BearerAuthProvider`, `BasicAuthProvider`, `ApiKeyAuthProvider`
- [x] Typed exception hierarchy: `ODataException`, `NotFoundException`, `UnauthorizedException`, `RateLimitException`, etc.
- [x] `HttpInterceptor` pattern for logging, metrics, retry
- [x] `Context` record holding all dependencies (baseUrl, serializer, transport, auth, schemas, interceptors)
- [x] `RequestHelper` utility for executing HTTP requests
- [x] Generated request classes wired to `RequestHelper` for actual HTTP execution
- [x] `CollectionPage<T>` with OData `{"value": [...]}` response parsing (JsonNode-based)
- [x] Runtime tests (6 tests): collection response parsing, URL construction, key formatting

---

## Milestone 4: CRUD + Paging + Batch ✅ MOSTLY COMPLETE

**Goal:** Full CRUD operations, proper paging, and OData batch support.

- [x] Entity CRUD: GET, POST, PATCH (changed fields only), PUT, DELETE
- [x] ETag / If-Match support for optimistic concurrency
- [x] Collection paging with `@odata.nextLink` and `@odata.count`
- [ ] Cancellable streaming
- [ ] `$batch` requests
- [x] `$ref` support for navigation property links
- [x] `$count` support in collection queries
- [x] Integration tests: 9 live tests against TripPin service

---

## Milestone 5: Maven Plugin + Testing + Polish ✅ COMPLETE

**Goal:** Maven plugin, comprehensive tests, documentation.

- [x] `odata-client-maven-plugin` with `generate` goal (downloads metadata or reads from file)
- [x] Unit tests for generator (29 tests)
- [x] Runtime tests (6 tests)
- [x] Integration tests against live TripPin service (9 tests)
- [x] README with usage examples
- **Total: 44 tests passing**

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Java version | 17+ (source 16 for records) | Records give true immutability without Lombok |
| XML parsing | StAX (not JAXB) | No XSD dependency; handles namespace variations; lower memory |
| Serialization model | Annotation-free entities + `Serializer` interface | Pluggable; model usable without Jackson on classpath |
| Query API | Generated expression builders | Same level of safety as jOOQ/SAP SDK |
| HTTP abstraction | `HttpTransport` interface + `CompletableFuture` | Async-first; narrow interface; pluggable |
| Auth | `AuthProvider` interface | Pluggable; supports any auth mechanism |
| Error handling | Typed exception hierarchy | Catch specific errors, not generic `ClientException` |
| SchemaInfo | Class `ServiceSchemaInfo` (not enum) | Avoids name collision with runtime interface; testable |
| Property constants | UPPER_CASE (`Person.FIRST_NAME`) | Avoids shadowing with instance fields; follows Java constants |
| Collection parsing | JsonNode-based (`mapper.convertValue`) | Avoids type erasure issues with records + generics |
| Entity nav methods | `UnsupportedOperationException` | Entities don't hold Context; use container/entity requests |

---

## Type-Safe Query API Examples

```java
// Filter — compile-time safe
client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("B")))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .stream()
    .forEach(System.out::println);

// Expand with nested filter/select
client.people()
    .expand(Person.TRIPS
        .filter(Trip.BUDGET.greaterThan(500.0f))
        .select(Trip.NAME, Trip.BUDGET)
        .orderBy(Trip.BUDGET.desc()))
    .stream();

// Entity navigation via request
client.peopleByUserName("scottketchum")
    .trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .get();
```

## Generated Class Structure

```java
public final class Person implements ODataEntityType {
    // Type-safe property constants (UPPER_CASE)
    public static final StringProperty<Person> FIRST_NAME = ...;
    public static final NumberProperty<Person, Long> CONCURRENCY = ...;
    public static final NavProperty<Person, Trip> TRIPS = ...;

    // Immutable fields (final)
    private final String userName;
    private final String firstName;
    private final String lastName;
    private final List<String> emails;

    // Builder, with*() methods, getters
}
```

## User-Facing API

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);

// Collection query
CollectionPage<Person> people = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .top(5)
    .get();

// Entity navigation
TripCollectionRequest trips = client.peopleByUserName("scottketchum").trips();
CollectionPage<Trip> tripPage = trips.get();
```
