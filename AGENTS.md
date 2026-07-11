# AGENTS.md — OData Codegen

## Project Overview

A type-safe OData v4 client generator for Java. Parses CSDL XML metadata and generates immutable Java classes with compile-time validated query builders.

**Reference implementation analyzed:** [davidmoten/odata-client](https://github.com/davidmoten/odata-client)

---

## Key Design Decisions

### 1. StAX Parser Over JAXB

**Decision:** Use `javax.xml.stream` (StAX) instead of JAXB for CSDL XML parsing.

**Reason:** JAXB requires XSD schema files to generate binding classes. The OData CSDL XSD has namespace variations across versions. StAX is:
- No external dependencies (built into JDK)
- Handles namespace variations gracefully (v3 vs v4 namespaces)
- Lower memory footprint (cursor-based, not tree-based)
- More predictable error handling

### 2. Java Records for Internal Model

**Decision:** Use Java 16+ records for the internal CSDL model (`CsdlModel.EntityTypeModel`, `PropertyModel`, etc.).

**Reason:** Records provide:
- True immutability without Lombok or manual equals/hashCode
- Concise syntax — 30 record types in ~150 lines
- No framework dependency (unlike Lombok)
- Thread-safe by default (all fields final)

### 3. Type-Safe Query API (Not String-Based)

**Decision:** Generate expression builder classes for `$filter`, `$select`, `$orderby`, `$expand` instead of raw strings.

**Reason:** String-based filters (like the reference implementation) defeat the purpose of code generation:
- Typos in property names → runtime HTTP 400 errors
- Wrong operator for type → silent failures
- No IDE autocomplete or refactoring support

**Approach:** Inspired by SAP Cloud SDK and jOOQ:
- Each entity gets static property constants (`Person.FIRST_NAME`)
- Each property type has its own set of valid operations
- `StringProperty` has `contains()`, `startsWith()` *and* `greaterThan()` (OData supports lexicographic `gt`/`lt`/`ge`/`le` on strings)
- `NumberProperty` has `greaterThan()`, `multiply()` — not `contains()`
- Composable: `filter(a.and(b.or(c)))`

### 3a. UPPER_CASE Property Constants

**Decision:** Use `UPPER_CASE` for static property constants (`Person.FIRST_NAME`) instead of camelCase (`Person.firstName`).

**Reason:** Property constants share the same name as instance fields. Using camelCase creates name shadowing — the static constant `firstName` collides with the instance field `firstName` in the same class. UPPER_CASE follows Java constant naming conventions (like `Integer.MAX_VALUE`) and eliminates the collision entirely.

### 4. Truly Immutable Entities

**Decision:** All generated entities use `final` fields and copy-on-write semantics.

**Reason:** The reference implementation claims immutability but has:
- `protected` non-final fields (due to JVM 256-arg constructor limit)
- Mutable `UnmappedFieldsImpl` (wraps `HashMap`)
- `changedFields` set to `null` after `patch()` (NPE risk)

**Our approach:**
- All fields `final`
- `with*()` methods return new instances (no `_copy()` needed)
- `ChangedFields` is a separate `Set<String>` passed to constructor
- No `@JacksonInject` coupling — model is annotation-free

### 5. Narrow HTTP Interface

**Decision:** `HttpTransport` with just two methods: `submit()` and `stream()`.

**Reason:** The reference's `HttpService` is a God Interface with 20+ methods mixing:
- HTTP transport
- Content conversion
- Base path management
- Proxy configuration
- Connection lifecycle

**Our approach:**
```java
public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);
    CompletableFuture<InputStream> stream(HttpRequest request);
}
```
- Async-first (CompletableFuture)
- Separate `AuthProvider`, `Serializer`, `Context` as composable concerns
- Typed `HttpRequest`/`HttpResponse` records (no raw `String url`)

### 6. Class-Based SchemaInfo (Not Enum Singleton)

**Decision:** `SchemaInfo` is a regular class implementing an interface, not an enum singleton.

**Reason:** The reference's enum singleton pattern:
- Can't mock in tests
- Can't use same client against multiple service instances
- Can't reload if metadata changes
- Forces recompilation on metadata change

**Our approach:**
```java
public interface SchemaInfo {
    Class<?> getClassFromTypeWithNamespace(String name);
}
// Generated: public class ServiceSchemaInfo implements SchemaInfo { ... }
```

### 6a. Generated Class Named `ServiceSchemaInfo` (Not `SchemaInfo`)

**Decision:** Generate the schema info class as `ServiceSchemaInfo` instead of `SchemaInfo`.

**Reason:** The generated class lives in a different package than the runtime `SchemaInfo` interface. Using the same simple name would require fully qualified references everywhere. `ServiceSchemaInfo` avoids the name collision while still being clear about purpose.

### 6b. Entity Navigation Properties Materialize Expanded Data

**Decision:** Entity and complex-type nav properties deserialized from JSON via `@JsonProperty` hold expanded data in `final` fields, exposed via typed getters (e.g., `person.getTrips()` → `List<Trip>`, `location.getAirportRef()` → `Optional<Airport>`).

**Reason:** When `$expand` is used, OData returns navigation data inline with the entity. The generated `@JsonCreator` constructor accepts the nav JSON as `@JsonProperty("Trips") List<Trip> trips`, which Jackson deserializes automatically. This gives users direct access to expanded data without dropping to raw HTTP.

Navigation **requests** (which require `Context` for HTTP execution) remain on the entity request class: `client.peopleByUserName("scott").trips().get()`.

### 6c. JsonNode-Based OData Collection Response Parsing

**Decision:** Parse OData `{"value": [...]}` responses using Jackson `JsonNode` tree traversal instead of typed record deserialization.

**Reason:** Records with generic type parameters (`ODataCollectionResponse<T>`) have type erasure issues with Jackson's `constructParametricType()`. The `ParameterNamesModule` isn't always available, and `TypeReference` loses the element type. JsonNode approach:
- No dependency on Jackson parameter-names module
- Works with any Jackson version
- `mapper.convertValue(valueNode, listType)` correctly preserves the element type
- Simpler, fewer failure modes

### 7. Typed Exception Hierarchy

**Decision:** Specific exception types instead of a single `ClientException`.

**Reason:** The reference throws `ClientException` for everything (400, 401, 404, 429, 500). Users must parse the status code from the exception message.

**Our approach:**
```java
ODataException (base)
├── NotFoundException (404)
├── UnauthorizedException (401)
├── ForbiddenException (403)
├── BadRequestException (400)
├── ConflictException (409)
└── RateLimitException (429, with retryAfter)
```

### 8. Serialization-Agnostic Model

**Decision:** Generated entities use Jackson `@JsonCreator`/`@JsonProperty` annotations for deserialization, but the `Serializer` interface is pluggable.

**Reason:** The reference's entities are annotated with `@JsonAnySetter`, `@JacksonInject`, etc. We simplified to just `@JsonCreator` and `@JsonProperty` — the minimum needed for Jackson deserialization. The `Serializer` interface allows plugging in custom serialization logic, but Jackson annotations on the model are required for the default `JacksonSerializer`.

**Our approach:** Entities are annotated with `@JsonCreator`/`@JsonProperty` for Jackson. `Serializer` interface is pluggable for custom serialization:
```java
public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);
    <T> T deserialize(byte[] data, Class<T> type);
}
```

**Known limitation:** Swapping to Gson or JSON-B requires removing `@JsonCreator`/`@JsonProperty` and implementing a custom `Serializer` with schema-driven deserialization.

**Known limitation (as of v0.1.0):** The `EntityGenerator` currently emits `@JsonCreator`/`@JsonProperty` annotations on generated entities for Jackson deserialization. This couples the model to Jackson despite the pluggable `Serializer` interface. The `Serializer` interface works for serialization (POST/PATCH bodies) but deserialization relies on Jackson annotations. Swapping to Gson or JSON-B requires either removing annotations and writing a schema-driven deserializer, or registering Jackson mixins. This is documented tech debt tracked in issue #10 of the code review.

### 9. Maven Plugin (Not CLI Tool)

**Decision:** Distribute as Maven plugin for build-time generation.

**Reason:** Integrates naturally into Java build lifecycle:
- Code generated during `generate-sources` phase
- `build-helper-maven-plugin` adds generated sources automatically
- No separate CLI installation needed
- Version-locked with the project

**Implementation:** `GenerateMojo` accepts `metadataUrl` or `metadataFile`, `basePackage`, and optional `schemaPackages` map. Downloads metadata via `HttpClient`, follows redirects (TripPin requires this), parses with StAX, generates via `Generator`.

### 10. Context-Centric Request Execution

**Decision:** Generated request classes accept `Context` in their constructors and use `EntityOperations` for HTTP execution.

**Reason:** Entities are pure data models — they don't hold HTTP transport state. Request classes (entity requests, collection requests) are the execution layer that holds `Context` (transport, serializer, auth). This cleanly separates:
- **Model layer** (entities, complex types) — immutable data
- **Request layer** (entity requests, collection requests) — execution with Context
- **Container layer** — entry point that holds Context

**User-facing API:**
```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);
CollectionPage<Person> people = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .top(5)
    .get();
```

### 11. Key Format Follows OData v4 Spec

**Decision:** Single-key entities omit key name, composite keys include names.

**Reason:** OData v4 conventions:
- Single-key: `People('scottketchum')` — key name omitted (conventional)
- Composite: `OrderDetails(OrderId=1,ProductId=5)` — key names required
- Real services (TripPin) return 500 if key name is included for single keys

### 12. Batch Requests Use Absolute URIs

**Decision:** Batch operations resolve relative URLs to absolute URLs before sending.

**Reason:** The OData v4 spec says batch operations can use relative or absolute URIs, but real services (TripPin) require absolute URIs. The error "a base URI was not specified for the batch writer or batch reader" occurs when relative paths are used. We resolve `People('scott')` to `https://service/V4/TripPinService/People('scott')` before encoding.

### 13. Batch Uses Multipart/Mixed Format

**Decision:** Use `multipart/mixed` format for batch requests (not JSON batch).

**Reason:** `multipart/mixed` is the original OData v4 batch format supported by all services. JSON batch (`application/json`) is a newer alternative with limited adoption. Multipart is simpler to implement and more widely compatible.

### 14. Batch Response Parsing Separates Request/Response Roles

**Decision:** The encoder creates HTTP request lines (`GET /path HTTP/1.1`) while the decoder parses HTTP response lines (`HTTP/1.1 200 OK`).

**Reason:** Batch is a request-response protocol. The encoder builds the outgoing requests; the decoder parses the incoming responses. These are different formats — request lines start with the method, response lines start with `HTTP/x.x`. The decoder should not be tested with encoded requests (they have different formats).

### 15. Entity Inheritance Generation

**Decision:** For entity types that declare a `BaseType` in CSDL, the generator emits a Java `extends` clause, walking the full base chain for fields, keys, getters, navs, and property constants.

**Reason:** Real OData services model domain hierarchies — TripPin has `Flight → PublicTransportation → PlanItem`; OData Demo has `FeaturedProduct extends Product` and `Event extends PlanItem`. Without inheritance, generated subclasses repeat every base property and lose the type relationship, and base-type query predicates can't be reused on subtypes.

**Approach:**
- `EntityGenerator.findBase()` / `inheritedProperties()` walk the `baseType` chain recursively (own props appended after inherited).
- The subclass emits `extends BaseX`, `abstract` for `Abstract="true"` types, `protected final` fields for its own properties (base fields live in the parent), and `super(...)` calls in both the Jackson and internal constructors.
- `getKey()` resolves keys up the chain; `toString()` and `with*()` use the full resolved property list.
- `Builder` is generated **only** for concrete top-level entities (`base == null && !abstractType`) to avoid static-method clashes across the hierarchy; subtypes copy state via `with*()` methods.
- `with*()` copy-on-write methods are generated **only** for concrete (`!abstractType`) entities. Abstract entities cannot be instantiated, so emitting `with*()` (which constructs `new AbstractX(...)` via the protected constructor) is a **compile error**; this was a latent defect (lesson 53) fixed by gating generation on `!entityType.abstractType()` in `EntityGenerator`. Concrete subtypes generate their own `with*()` that reconstruct the subtype via `super()`.
- Shared lifecycle fields (`contextPath`, `etag`, `unmappedFields`, `changedFields`) are declared `protected final` **only** in the root class.

### 16. Generic `FilterExpression<E>` for Type-Safe, Inheritance-Aware Filtering

**Decision:** `FilterExpression` is generic over the entity type (`FilterExpression<E>`). Collection-request `filter()` accepts `FilterExpression<? super E>`; property filter methods return `FilterExpression<E>`.

**Reason:** This makes a cross-entity filter (e.g., filtering `People` with a `Trip` predicate) a compile-time error, while still allowing inheritance: a predicate written against a base type `PlanItem` is accepted when filtering a subtype `Flight`, because `FilterExpression<PlanItem>` satisfies `FilterExpression<? super Flight>`.
- `and`/`or`/`not` stay same-type (`FilterExpression<E>`) to avoid silently widening the bound.
- `RawFilterExpression<E>` is the record implementation; `FilterExpression.of("raw")` escapes to raw OData for advanced cases.

### 17. `PropertyExpression<T>` Unifies `select` and `orderBy`

**Decision:** Introduce `PropertyExpression<T> extends OrderExpression<T>` with `getEdmName()`, implemented by `StringProperty`, `NumberProperty`, `DateTimeProperty`, `BooleanProperty`, `EnumProperty`.

**Reason:** `select(...)` previously accepted only `StringProperty`. Widening to `PropertyExpression<?>` lets any property type be selected, and gives a single source of truth for the OData property name reused by both `$select` and `$expand` nested selects. `OrderExpression.getEdmName()` (which threw) was removed from the base and now lives only on `PropertyExpression`.

### 18. Nested `$expand` Options via `NavQuery`

**Decision:** `NavProperty` gains `select()`/`orderBy()`/`filter()`/`top()` methods returning a `NavQuery<T>` record; `expand()` accepts either `NavProperty<?, ?>` or `NavQuery<?>`.

**Reason:** OData allows `$expand=Trips($select=...;$filter=...;$top=...;$orderby=...)`. Exposing `NavQuery` lets users nest those options type-safely on the navigation target without string concatenation. `NavQuery.toODataExpand()` renders the full `Trips(...)` clause; `RequestGenerator` appends it to the `$expand` query option.

### 19. Complex-Type Inheritance Generation

**Decision:** Complex types that declare a `BaseType` in CSDL emit a Java `extends` clause, mirroring entity inheritance (decision 15) but adapted for keyless value types.

**Reason:** Real services model value-type hierarchies — TripPin has `EventLocation extends Location` and `AirportLocation extends Location`. Without inheritance, generated subtypes would repeat every base property and lose the `is-a` relationship (so a `Location` field couldn't hold an `EventLocation`).

**Approach:**
- `ComplexTypeGenerator.findBase()` / `inheritedProperties()` walk the `baseType` chain (own props appended after inherited).
- The subclass emits `extends BaseX`, `abstract` for `Abstract="true"` types, `protected final` fields for its own properties, and a `super(...)` call passing inherited properties in the Jackson all-args constructor.
- Own getters only (inherited getters come from the parent); `toString()` covers the full resolved property list via the protected fields.
- **`with*()` copy-on-write methods are generated for all concrete types** (including subtypes), referencing inherited properties by field name (not getter) so nullable `Optional<T>` getters aren't passed into the raw-typed constructor.
- **`Builder` is generated only for concrete top-level types (`base == null && !abstractType`).** A static `builder()` in a subtype would clash with the inherited one — Java forbids hiding a static method with an incompatible return type (`EventLocation.Builder` vs `Location.Builder`). Subtypes use `with*()` instead.
- `ComplexTypeGenerator.generate()` now takes the full `SchemaModel` (was just the namespace) so it can resolve the base type.

---

### 20. Media Stream Support (`HasStream` & `Edm.Stream`) via the Request Layer

**Decision:** Entities declared `HasStream="true"` (media entities) and properties of type `Edm.Stream` (named streams) get stream accessors on the generated **entity request** class: `streamMedia()` / `setMedia(InputStream[, etag])` for media entities, and `stream<Prop>()` / `set<Prop>(InputStream[, etag])` for named streams.

**Reason:** Real services expose binary content (OData Demo `Advertisement` media entity, `PersonDetail.Photo` named stream). Without it, users must drop to raw HTTP and hand-build the `$value` URL + headers. The parser already captures `hasStream`; `Edm.Stream` maps to `Object` (lesson 27) so the property getter alone was useless for the actual bytes.

**Approach:**
- Media entity: `streamMedia()` GETs `.../<EntitySet>(key)/$value`; `setMedia(...)` PUTs the same URL (with `If-Match` for concurrency).
- Named stream: `stream<Prop>()` GETs `.../<EntitySet>(key)/<PropertyName>` — the media resource itself. OData Demo rejects `/<Prop>/$value` (only `$value` is valid for media *entities*); the named stream is the terminal segment.
- Generated code delegates to `EntityOperations.streamMedia()` / `putMedia()`, which build the request (Accept `*/*` for raw bytes) and route through the transport chain. `HttpTransport.stream()` now honours a caller-supplied `Accept` (so media can request `*/*` instead of JSON), and the interceptor `stream()` wrapper delegates to the delegate instead of throwing.
- Entities (no `Context`) do **not** get stream methods — consistent with decision 6b; streaming is a request-layer concern.

**Known limitation:** `setMedia`/`set<Prop>` mutate server state; the live generated-client tests only exercise reads. Uploads are covered by the runtime mock-transport test (`EntityOperationsMediaTest`) and the generator-content test (`RequestGeneratorMediaTest`).

### 21. `$apply` Aggregation / Transformations (incl. `$compute`) via `ApplyExpression`

**Decision:** Generated collection requests gain `apply(ApplyExpression)` and `apply(String raw)` methods that emit the OData v4 `$apply` system query option. `ApplyExpression` is a runtime interface with a fluent `ApplyBuilder` (`groupBy`, `aggregate`, `compute`, `filter`, `orderBy`, `top`, `skip`) and a raw escape hatch `ApplyExpression.of("...")`.

**Reason:** OData v4 `$apply` performs server-side aggregation and transformations — `groupby((Category))/aggregate(Price with sum as Total)`, `compute(Price mul 2 as DoublePrice)`, etc. `$compute` is **not** a standalone query option; it is a transformation *inside* `$apply`, so it is exposed via `ApplyBuilder.compute(...)`. `$search` was already generated as a raw `search(String)` option. Hand-writing `$apply` strings is error-prone (slash-separated transformations, nested parentheses); a typed builder catches malformed pipelines at compile time and reuses `FilterExpression`/`PropertyExpression` for type-safe `filter`/`groupBy` clauses.

**Approach:**
- `ApplyExpression.toODataApply()` renders slash-separated transformations; `RawApplyExpression` is the record implementation behind `ApplyExpression.of(raw)`.
- `ApplyBuilder` appends each transformation; `groupBy` accepts either `String...` or `PropertyExpression<?>...` (uses `getEdmName()`); `filter` accepts either a raw `String` or a typed `FilterExpression<E>`.
- `RequestGenerator.generateCollectionRequest` stores `applyExpr` (the rendered string), emits it as `ctx.addQuery("$apply", applyExpr)` in `buildContext()`, and carries it through `copy()`.
- `ContextPath.encodeQueryParam()` already preserves `/`, `(`, `)`, `,` so the slash-separated `$apply` value survives URL encoding (verified by reading `ContextPath` — no change needed).

**Known limitation:** No live integration test exercises `$apply` — TripPin, Northwind, and OData Demo do not implement aggregation. Coverage is the runtime `ApplyExpressionTest` (8, builder output) plus the generator-content `RequestGeneratorApplyTest` (3, asserts the generated `apply` methods and `$apply` query emission). `GeneratorCompilationTest` confirms the generated client (with `apply`) still compiles against the runtime.

### 22. OpenType Dynamic Properties via `@JsonAnySetter`/`@JsonAnyGetter`

**Decision:** Entities and complex types declared `OpenType="true"` (or inheriting openness from a base) capture undeclared JSON fields into `unmappedFields` on deserialization, expose them via `getUnmappedFields()` / `getDynamicProperty(String)`, and round-trip them on serialization.

**Reason:** OData open types may carry dynamic properties not present in the CSDL — a service can return extra JSON fields (TripPin `Person`/`Event`/`Location`, OData Demo `Category`). Previously these were **silently dropped**: the `@JsonCreator` constructor lists only declared props, there was no any-setter, `FAIL_ON_UNKNOWN_PROPERTIES` is `false`, and `unmappedFields` was hard-coded to `Map.of()`. `getUnmappedFields()` always returned `{}` despite existing on the `ODataType` interface.

**Approach:**
- `openTypeResolved(type)` walks the base chain — a type is open if it or any ancestor is open (OpenType propagates to subtypes per the OData spec).
- The **root** class (`base == null`) initializes `unmappedFields` to a mutable `new HashMap<>()` when `subtreeHasOpen(root)` (any type in its hierarchy is open); otherwise it keeps the zero-alloc `Map.of()`. Non-open hierarchies are completely unchanged (no extra allocation, no annotations).
- A `@JsonAnySetter protected void putDynamicProperty(String,Object)` is generated **only at the topmost open type** in the chain (`firstOpen`) — `protected` (not `private`) so open subtypes inherit the single any-setter (Jackson rejects multiple any-setters). It filters out `@`-prefixed OData control annotations (`@odata.id`, `@odata.editLink`, ...).
- `getUnmappedFields()` gets `@JsonAnyGetter` and returns `Collections.unmodifiableMap(unmappedFields)` for every open-resolved type (so it survives override chains and re-serializes dynamic props on POST/PATCH).
- The same logic is mirrored in `ComplexTypeGenerator` (open complex types: TripPin `Location`/`EventLocation`/`AirportLocation`).
- A typed overload `getDynamicProperty(String name, Class<T> type)` coerces the stored Jackson "natural" value into the caller's type via `DynamicPropertyConverter.convert(...)` (Jackson `ObjectMapper.convertValue`), so nested objects become POJOs and numbers coerce (e.g. `Integer` → `Long`). `convertValue` is the correct tool because the value is an in-memory Jackson object — there are no JSON bytes to re-parse, and the pluggable `Serializer` cannot operate on it. Conversion failures throw `IllegalArgumentException` with the property name.

**Known limitations:** (a) Complex-type `with*()`/`Builder` reconstruct via the all-args `@JsonCreator` constructor, which starts a fresh empty `unmappedFields` — dynamic props are **not** preserved across a complex-type copy-on-write (entities preserve them via the internal constructor). (b) Dynamic values are typed as raw `Object` (Jackson's natural binding: `String`/`Number`/`Boolean`/`List`/`Map`); there is no schema-driven coercion. (c) `Edm.GeographyPoint` still maps to `Object`.

---

## Architecture

```
odata-codegen/
├── odata-codegen-core/        # Parser + Generator (no runtime deps)
│   ├── model/                # CsdlModel records
│   ├── parser/               # StaxCsdlParser
│   └── generator/            # Names, Generator, EntityGenerator, etc.
├── odata-codegen-runtime/     # Runtime types (generated code depends on this)
│   ├── entity/               # ODataEntityType, ContextPath, SchemaInfo
│   ├── query/                # Expression hierarchy (StringProperty, etc.)
│   ├── http/                 # HttpTransport, HttpRequest, HttpResponse
│   ├── auth/                 # AuthProvider
│   ├── serialization/        # Serializer interface
│   ├── paging/               # CollectionPage
│   └── batch/                # BatchOperation, BatchRequest, BatchResponse
├── odata-codegen-maven-plugin/ # Maven plugin wrapper
└── odata-codegen-test/        # Integration tests
```

---

## Testing Strategy

Run `mvn test` from the repo root. All modules build in one reactor; the runtime must be installed before `odata-codegen-core`/`odata-codegen-test` compile against it.

- **Parser tests:** Parse TripPin + Northwind + OData Demo metadata XML, verify model correctness (`StaxCsdlParserTest`, 47 tests)
- **Generator integration tests:** Generate TripPin client, verify file structure and code content (`GeneratorIntegrationTest`, 1 test)
- **Generator compilation tests:** Generate + compile TripPin client (with the `Event`/`Flight`/`PlanItem` inheritance hierarchy) against runtime JARs (`GeneratorCompilationTest`, 1 test)
- **Cross-namespace compilation tests:** Generate + compile OData Demo client (cross-namespace types) against runtime JARs (`CrossNamespaceCompilationTest`, 1 test)
- **Entity generator unit tests:** Composite-key `getKey()`, collection getter emission (`EntityGeneratorCompositeKeyTest` 1, `EntityGeneratorCollectionGetterTest` 2)
- **Complex type generator unit tests:** Complex-type inheritance — `EventLocation extends Location`, `with*` + Builder generation (`ComplexTypeGeneratorInheritanceTest` 3, `ComplexTypeGeneratorCollectionEnumTest` 4)
- **Entity generator abstract-type unit tests:** Abstract entity generation — abstract base declares no `with*()`, concrete subtype extends it + has `with*()`, and the pair compiles (`EntityGeneratorAbstractTest` 3)
- **Request generator tests:** Media-stream, `$apply` expression, composite-key (`RequestGeneratorMediaTest` 3, `RequestGeneratorApplyTest` 3, `RequestGeneratorKeyTest` 2)
- **Open-type generator tests:** Generated entity/complex-type captures undeclared JSON fields into `unmappedFields`; open subtype of non-open base captures via inherited root map; non-open complex type doesn't reference unmappedFields (`OpenTypeGeneratorTest` 6)
- **Runtime tests:** 166 (live TripPin & Northwind integration, query expression, context path, batch, exceptions, transport, **media `$value` stream/put via mock transport** — `EntityOperationsMediaTest` 3, **`$apply` builder** — `ApplyExpressionTest` 8, **collection parse** — `EntityOperationsCollectionParseTest` 6)
- **Generated client tests (92):** `NorthwindGeneratedClientTest` (24), `ODataDemoGeneratedClientTest` (23, exercises `FeaturedProduct extends Product`, `Customer`/`Employee extends Person`, `Event`/`PlanItem`), `TripPinGeneratedClientTest` (24, exercises `Flight`/`PublicTransportation`/`PlanItem` hierarchy, type-safe + nested `$expand` with materialized getters), `TripPinInheritanceTest` (11, exercises generated **complex-type** inheritance `EventLocation`/`AirportLocation extends Location` + **entity** inheritance `Flight → PublicTransportation → PlanItem`, `Event → PlanItem`: `instanceof`/polymorphic assignment, subtype `with*` copy-on-write preserving inherited fields, base `builder()` scoping, live `AirportLocation` deserialization), `ODataDemoMediaTest` (2, live media streams: `Advertisement` `HasStream` via `streamMedia()` at `.../Advertisements(id)/$value`, `PersonDetail.Photo` `Edm.Stream` named stream via `streamPhoto()` at `.../PersonDetails(id)/Photo`), `OpenTypeDynamicPropertyTest` (8, deserialization captures dynamic props into `unmappedFields`/`getDynamicProperty`, typed `getDynamicProperty(String, Class)` coercion to a POJO/number, round-trips on serialize, filters `@odata.*` control fields)
- **Total: 335 tests passing**
- **Future:** Cancellable streaming

---

## Lessons Learned

1. **Real-world OData metadata has quirks.** TripPin's session-based redirects required `curl -sL`. Self-closing XML tags with redundant closing tags required careful parsing.

2. **Java records work well for parsers.** The CsdlModel with 29 nested record types maps cleanly to CSDL structure without boilerplate.

3. **Expression builder type safety is worth the complexity.** The `StringProperty` vs `NumberProperty` approach prevents entire classes of runtime errors.

4. **Don't repeat reference implementation mistakes.** The `changedFields = null` after patch, mutable `UnmappedFields`, and `@JacksonInject` coupling were all avoidable.

5. **Test with real metadata early.** TripPin and Northwind metadata exposed edge cases (composite keys, inheritance, annotations) that synthetic tests wouldn't catch.

6. **Static property constants must use UPPER_CASE.** Using camelCase (`Person.firstName`) creates name shadowing with instance fields — the compiler allows it but it's confusing and error-prone. UPPER_CASE eliminates this entirely.

7. **Builder inner classes need explicit `contextPath` field.** Static inner classes can't access outer instance fields. The Builder is `static final` so it must have its own `contextPath` field.

8. **Entity nav methods can't return request objects.** Entities are pure data models without `Context`. Only entity request classes (which hold `Context`) can create context-aware navigation requests. This is a fundamental constraint — entities are deserialized from JSON and don't hold HTTP transport state.

9. **OData collection responses need JsonNode parsing.** Records with generic type parameters have type erasure issues with Jackson's `constructParametricType()`. Using `JsonNode` tree traversal + `mapper.convertValue(valueNode, listType)` is simpler and more reliable.

10. **Complex type inheritance is hard to generate correctly.** Subclasses need `super()` calls for inherited properties, constructors must chain properly, and a static `builder()` in a subtype clashes with the inherited `builder()` (static methods don't allow covariant return types, so `EventLocation.Builder` can't hide `Location.Builder`). **Entity** inheritance is implemented (see decision 15). **Complex-type** inheritance is now implemented too (see decision 19) — same `extends`/`super()` chain, but subtypes use `with*` for copy-on-write and the `Builder` is generated only for top-level concrete types to avoid the static-method clash.

11. **ContextPath keys must be segment-local, not a flat list.** OData key predicates apply to the segment they key into: `People('scott')/Trips`. If keys are stored as a flat list and appended at the end, the URL becomes `People/Trips('scott')` — wrong. Each segment must own its keys. The `addKey()` method must modify the last segment, not a separate list.

12. **URL query parameter encoding needs OData-safe characters preserved.** `URLEncoder.encode()` encodes `$`, `'`, `(`, `)` etc. which are valid in OData query strings. After encoding, restore these characters. Spaces must still be encoded as `%20`.

13. **OData collection responses use `{"value": [...]}` wrapper.** The response is not a plain array — it's wrapped in an object with `value`, `@odata.nextLink`, and `@odata.count` annotations. Parsing requires reading the root object, extracting `value`, and checking for annotations.

14. **TripPin service has strict URL validation.** Navigation URLs like `People/Trips('scott')` return 500 with "segment 'People' refers to a collection, this must be the last segment." The key must be on the correct segment.

15. **`$count` returns `@odata.count` at the response root level.** It's not inside each item — it's a top-level annotation: `{"value": [...], "@odata.count": 42}`.

16. **`$ref` creates/removes entity links.** POST to `People('scott')/Friends/$ref` with `{"@odata.id": "People('keith')"` adds a friend. DELETE removes the link.

17. **OData `$expand` doesn't nest inside `$filter`.** You can't filter on expanded navigation properties using dot notation in `$filter` — use `$expand` with nested `$filter` instead.

18. **OData v4 requires `OData-MaxVersion: 4.0` and `OData-Version: 4.0` headers.** Without these, some services return 406 or 500. TripPin requires them.

19. **Java's `HttpURLConnection` doesn't support PATCH.** The legacy `java.net.HttpURLConnection.setRequestMethod()` throws `ProtocolException` for PATCH. Use `java.net.http.HttpClient` (Java 11+) which supports PATCH natively via `builder.method("PATCH", ...)`. The `X-HTTP-Method-Override` workaround is not universally supported — TripPin rejects it with a 500 error.

20. **TripPin requires ETag (If-Match) for PATCH and DELETE.** Both PATCH and DELETE return HTTP 428 (Precondition Required) if no `If-Match` header is sent. You must GET the entity first to obtain the `ETag` or `odata.etag` response header, then include it as `If-Match` in the mutation request.

21. **TripPin `$ref` POST returns 500 for some entity types.** Adding a friend via `POST People('scott')/Friends/$ref` with `{"@odata.id": "People('keith')"}` returns 500. The `$ref` DELETE with `$id` query parameter works correctly: `DELETE People('scott')/Friends/$ref?$id=People('keith')`.

22. **TripPin returns 204 (No Content) for GET on deleted entities.** After DELETE succeeds, a subsequent GET returns 204 instead of the expected 404. This is a TripPin-specific behavior — the entity is gone, but the service returns 204 rather than 404.

23. **`java.net.http.HttpClient` normalizes response header keys.** The `HttpHeaders.map()` method returns a case-insensitive `TreeMap`. When looking up headers, use case-insensitive comparison (`equalsIgnoreCase`) rather than exact string matching. This applies to `Content-Type`, `ETag`, and any other header lookups.

24. **Multipart batch responses need case-insensitive header lookup.** The `BatchRequest.parseResponse()` method was using `headers.getOrDefault("Content-Type", ...)` which fails when the HTTP client returns lowercase header keys. Fixed to use `equalsIgnoreCase` iteration over all headers.

25. **`ContextPath.addKey()` value type matters for URL format.** `addKey("CategoryID", "1")` generates `Categories('1')` (quoted) while `addKey("CategoryID", 1)` generates `Categories(1)` (unquoted). OData services may reject quoted integer keys — always pass the correct Java type (int for integers, String for strings).

26. **OData datetime literals must NOT be quoted in filter expressions.** Using `StringProperty` for `Edm.DateTimeOffset` generates `OrderDate ge '1998-01-01T00:00:00Z'` (quoted), but OData requires `OrderDate ge 1998-01-01T00:00:00Z` (unquoted). Fixed by adding `DateTimeProperty` that generates unquoted datetime literals.

27. **OData Demo service tests inheritance, open types, geography, stream gaps.** The parser correctly parses `BaseType`, `OpenType`, `HasStream`, and `ConcurrencyMode` attributes. `BaseType` is now honored by the generator (see decision 15 and lesson 51) — `FeaturedProduct` genuinely `extends Product`. `HasStream` and `Edm.Stream` are now honored too (decision 20) — media entities and named streams get `stream*`/`set*` request methods. `OpenType` and `ConcurrencyMode` are still ignored by the generator, and `Edm.GeographyPoint` still maps to `Object`. Those remain clear future milestones.

28. **OData Demo service IDs start at 0, not 1.** The `Products` entity set has `ID=0` for "Bread". Assertions like `assertTrue(p.getID() > 0)` fail — use `assertNotNull()` or non-zero-specific checks instead.

29. **async-profiler TLAB sampling shows TLAB refill events, not individual small allocations.** With JDK 24, `event=alloc` captures TLAB boundary crossings (typically ~512KB each). Small per-request allocations (HashMap, ArrayList, String) don't individually trigger samples — they're swallowed by the TLAB. To see small allocations, use `event=alloc` with `forkCount=0` (tests in Maven JVM) and look for stacks involving your code at TLAB refill points.

30. **ObjectMapper per-request is the most expensive allocation hotspot.** `EntityOperations.executeAndGetCollection()` was creating a new `ObjectMapper` with module registration on every collection fetch. ObjectMapper initialization cascades into TypeFactory, SerializerProvider, DeserializerProvider, and serializer cache compilation. Fix: static final singleton, thread-safe for concurrent reads.

31. **`StringBuilder.toString().endsWith("/")` in a loop allocates a temp String per iteration.** `ContextPath.appendSegments()` called `sb.toString()` to check trailing slash — this allocates a full String copy on every segment. Fix: `sb.charAt(sb.length() - 1) != '/'` avoids the allocation entirely.

32. **Chained `String.replace()` calls create O(n) intermediate Strings.** `ContextPath.encodeQueryParam()` had 9 chained `.replace()` calls, each scanning the entire string and allocating a new one. Fix: single-pass `StringBuilder` with a `switch` on hex sequences — one allocation, one scan.

33. **`List.copyOf()` defensively copies an already-safe list.** `CollectionPage` constructor called `List.copyOf(currentPage)` which copies all element references into a new array. Since the Jackson-deserialized list is the only reference, `Collections.unmodifiableList()` wraps without copying — same immutability guarantee, zero allocation overhead.

34. **Double JSON parse is a hidden allocation multiplier.** `executeAndGetCollection()` parsed JSON into a `JsonNode` tree via `readTree()`, then called `convertValue()` which re-serializes the JsonNode back to bytes and re-parses into the target type. This is two full JSON parse passes — each allocating parsing buffers, intermediate trees, and string objects. Fix: `readValue(Map.class)` + `convertValue()` on the raw list. The Map tree is cheaper than JsonNode, and `convertValue()` on a raw `List<Map>` avoids the re-serialization step. Result: `executeAndGetCollection` completely disappeared from allocation stacks in post-fix profiling.

35. **`toMultiMap()` creates unnecessary intermediate allocations per request.** `executeAsync()` called `toMultiMap(authHeaders)` which allocated a new `HashMap` + `List.of()` wrappers, then `putAll()` copied into another `HashMap`. Fix: inline the auth header iteration directly into the request headers map with pre-sized capacity. Eliminated one `HashMap` allocation per request.

36. **String concatenation for small JSON bodies wastes an intermediate String.** `addRef()` used `("{\"@odata.id\":\"" + url + "\"}")` which creates an intermediate String then copies to byte[]. Fix: pre-sized `StringBuilder` with known capacity — one allocation, no intermediates.

37. **Remaining allocations after optimization are third-party library internals.** After all fixes, profiler data (5 runs) showed: `EntityOperations.executeSync/executeAsync` appeared only 1 time each; `executeAndGetCollection` was absent entirely. The remaining stacks were: `JacksonSerializer.createMapper` (one-time static init per JVM), `JdkHttpTransport.execute` (JDK HttpClient connection pool internals), and Jackson serialization buffers. These are all unavoidable — Jackson needs buffers for JSON parsing, JDK HttpClient needs connection pools. Our code's allocation footprint is now dominated by libraries, not our own code.

38. **Profiling methodology: use `forkCount=0` + `asprof` attachment for accurate results.** Maven's default `forkCount=1` forks a child JVM for tests, making it hard to attach the profiler. Using `forkCount=0` runs tests in the Maven JVM directly, allowing `asprof -e alloc -i 1000 -d 60 <pid>` to attach cleanly. For JFR-based profiling, use `.mvn/jvm.config` with `-agentpath:...=start,event=alloc,file=output.jfr` which is the most reliable approach. Always run 5+ iterations to get stable data — single runs show too much JVM startup noise.

39. **Profile BEFORE and AFTER to confirm fixes work.** Don't just fix based on code reading — the profiler reveals which allocations actually matter at the TLAB level. For example, `RawFilterExpression.and()` is O(n²) in string concatenation theory, but with typical 1-5 filter clauses it never appears in profiler stacks. Meanwhile, the `ObjectMapper` per-request was invisible in code review but dominant in profiler output.

40. **`EntityGenerator.generateGetter` silently drops collection-typed property getters.** The collection branch (`EntityGenerator.java:230-235`) calls `sb()` which returns a throwaway `new StringBuilder()`, writes the getter to it, then returns `""`. The caller does `sb.append(generateGetter(...))` which appends nothing. Collection properties get a field and a builder setter but no getter. Fix: hoist `StringBuilder sb = new StringBuilder()` to the top of the method and use it for both branches.
    - **FIXED in v0.1.1.** `generateGetter` now hoists `StringBuilder` and emits the getter for collections. Per the "Truly Immutable Entities" decision, collection fields are stored via `List.copyOf(fn)` (null-safe) in both constructors, and the getter returns `Collections.unmodifiableList(fn)`. Verified by `EntityGeneratorCollectionGetterTest` (TDD: failing test first, then fix).

41. **`INDENT_OUTPUT` on wire serialization wastes bandwidth.** `JacksonSerializer.createMapper()` enables `SerializationFeature.INDENT_OUTPUT`, which pretty-prints every POST/PATCH body sent over the wire. This adds ~30% to body size and CPU cost with no benefit. Disable for the wire mapper; keep a separate mapper for debug `toJson()`.
    - **FIXED in v0.1.1.** `INDENT_OUTPUT` removed from the wire mappers (`MAPPER`, `MAPPER_INCLUDE_NULLS`). A separate `MAPPER_PRETTY` (with `INDENT_OUTPUT`) is used only by `toJson()`/`serializeToString()` for human-readable debug output. Verified by `JacksonSerializerTest`.

42. **`CompletableFuture.supplyAsync` on `ForkJoinPool.commonPool()` blocks shared pool.** `JdkHttpTransport.submit()` and `stream()` submit blocking I/O to the common pool with no executor. Under concurrent load this starves other common-pool users. Use a dedicated `ExecutorService` (e.g., cached, IO-bounded).
    - **FIXED in v0.1.1.** `JdkHttpTransport` takes an injected `Executor`; the default constructor uses a dedicated daemon cached pool with named threads (`odata-http-N`). Both `submit()` and `stream()` pass the executor to `supplyAsync(...)`. Verified by `JdkHttpTransportTest` (asserts the request runs on the injected executor, not the common pool).

43. **Auth header + extra header collision throws `UnsupportedOperationException`.** `EntityOperations.executeAsync()` stores auth headers as `List.of(...)` (immutable), then `extraHeaders` calls `computeIfAbsent(...).add(...)`. If any extra header key collides with an auth header key, `.add()` on the immutable list throws. Low probability but a latent bug.
    - **FIXED in v0.1.1.** Auth headers are now stored as mutable `new ArrayList<>(List.of(value))`, so colliding extra headers merge via `computeIfAbsent(...).add(...)` without throwing. Verified by `EntityOperationsHeaderTest` (TDD: threw `UnsupportedOperationException` before the fix).

44. **Interceptors are "first wins" — multiple interceptors silently don't chain.** `EntityOperations.executeAsync()` returns the first interceptor's result inside the loop (`return` at line 71). If 3 interceptors are registered, only the first runs. The interface signature `intercept(request, delegate)` allows calling `delegate.submit()` internally, but this design should be documented clearly — or reworked into a proper middleware chain.
    - **FIXED in v0.1.1.** Replaced the early-return `for` loop with a middleware chain built by `EntityOperations.buildTransportChain()`. Interceptors are iterated in reverse order; each wraps the next as an `HttpTransport` delegate. The first interceptor in registration order becomes the outermost wrapper, so all interceptors run. No public interface change to `HttpInterceptor`. Same fix applied to `BatchRequest.executeSync()` and `BatchRequest.executeAsync()`. Verified by `EntityOperationsInterceptorChainTest` (TDD: threw `AssertionError` before the fix).

45. **`CollectionProperty.any()` has a no-op `.replace("x", "x").** `CollectionProperty.java:26` calls `.replace("x", "x")` on the filter expression — this does literally nothing. It works only because `FilterableElement.prefix` is hardcoded to `"x"` matching the `any(x: ...)` variable. If the prefix were ever changed, this would silently break.
    - **FIXED in v0.1.1.** The `.replace("x", "x")` no-op was removed. `FilterableElement.prefix` remains `"x"` (matches `any(x: ...)`/`all(x: ...)`). Verified by `CollectionPropertyTest` (locks `Emails/any(x: x/Value eq 'a')` and `Emails/all(x: x/Value eq 'a')`).

46. **Dead `toMultiMap` method after inlining.** The original `RequestHelper.toMultiMap` (lines 209-215) was inlined per lesson 35 but the method body was never deleted. Dead code — deleted along with the `RequestHelper` class itself in the migration to `EntityOperations`.

47. **`ContextPath.formatValue` doesn't encode special chars in string key values.** `ContextPath.formatValue()` wraps strings in single quotes but doesn't escape `'`, `&`, `?`, `#`, or `%` inside the value. A key value like `"O'Brien"` produces `People('O'Brien')` (broken OData literal), `"A&B"` produces `People('A&B')` (`&` interpreted as query separator), `"A?B"` starts a query string, `"A#B"` starts a fragment.
    - **FIXED in v0.1.1.** Added `encodeKeyValue()` helper in `ContextPath` that single-pass scans the value and encodes: `'` → `''` (OData string literal escaping), `&` → `%26`, `?` → `%3F`, `#` → `%23`, `%` → `%25`. Verified by `ContextPathTest` (6 tests: singleQuote, ampersand, questionMark, hash, percent, compositeKey).

48. **`JdkHttpTransport.stream()` throws generic `ODataException` on error, not typed exceptions.** The `stream()` method at line 97-100 threw a raw `ODataException` for any 4xx/5xx status code, while the `submit()` path produced typed exceptions (`NotFoundException`, `RateLimitException`, etc.) via `RequestHelper.checkResponse`. Inconsistent error handling between the two paths.
    - **FIXED in v0.1.1.** Added `ODataException.fromResponse(HttpResponse)` as a shared factory in the `exception` package that maps status codes to typed exceptions. Both `EntityOperations.checkResponse()` and `JdkHttpTransport.stream()` now delegate to it. `stream()` reads the error body, builds an `HttpResponse`, and throws the correct typed exception. Verified by `ODataExceptionTest` (7 tests covering 400/401/403/404/409/429/500).

49. **`EntityGenerator.getKey()` returns only the first key for composite-key entities.** The generated `getKey()` method used `entityType.keys().get(0).propertyRefs().get(0)` — the first property ref from the first key. Composite-key entities (like Northwind's `Order_Detail` with `[OrderID, ProductID]`) lost all key fields except the first one.
    - **FIXED in v0.1.1.** `EntityGenerator.getKey()` now checks the number of `propertyRefs`. Single key: returns the raw value (unchanged). Composite key: returns `java.util.Map.of("key1", field1, "key2", field2, ...)`. Verified by `EntityGeneratorCompositeKeyTest` (parses Northwind `Order_Detail`, checks the getKey body contains `Map.of` with both `OrderID` and `ProductID`).

50. **`RequestHelper` is public but lives in the `internal` package.** The generated code referenced `io.github.akbarhusain.odata.runtime.internal.RequestHelper` for all CRUD operations, exposing internal implementation details as public API. The `internal` package convention was undermined by requiring public access.
    - **FIXED in v0.1.1.** Created `io.github.akbarhusain.odata.runtime.client.EntityOperations` with the same public API. `RequestHelper` now delegates to `EntityOperations` and is deprecated. `RequestGenerator` emits `EntityOperations.*` instead of `RequestHelper.*`. `BatchRequest` uses `EntityOperations.buildTransportChain` directly. Verified by full test suite (183 tests passing, including all generated client tests that now compile against the new class).

51. **Inheritance generation: `with*` methods must reference fields, not getters, for inherited properties.** `generateWithMethod` built `new SubType(...)` passing `this.getBaseProp()` for inherited properties, but a nullable getter returns `Optional<T>` while the constructor expects raw `T`. Since inherited fields are `protected final` in the parent, reference the field name directly for all properties (own and inherited). Verified by `GeneratorCompilationTest` (TripPin `Event`/`Flight`/`PublicTransportation` hierarchy now compiles).

52. **Inheritance generation: shared lifecycle fields belong only in the root class.** `contextPath`, `etag`, `unmappedFields`, `changedFields` were redeclared in every generated class, shadowing the parent's `final` copies (which the `super()` call initializes, leaving the subclass's copy uninitialized → "variable ... might not have been initialized"). Fix: declare these four fields only when `base == null`, make them `protected` so subclasses can read them, and guard the constructor assignments (`this.unmappedFields = ...`, `this.changedFields = ...`) with `if (base == null)` since the parent `super()` already sets them.

53. **Abstract base entity types now supported in generation.** The generator emits `public abstract class` for `Abstract="true"` types and generates **no** `with*` methods (which would otherwise call `new AbstractX(...)` — a compile error). The fix gates `with*` generation on `!entityType.abstractType()` in `EntityGenerator` (mirroring the already-correct complex-type handling in `ComplexTypeGenerator`, lessons 10/54). The protected internal constructor and public Jackson constructor are still emitted so concrete subtypes can chain `super(...)`. Verified by `EntityGeneratorAbstractTest` (abstract `Animal` + concrete `Cat extends Animal`: the pair compiles, `Animal` has no `with*()`, `Cat` has `with*()` that reconstructs `new Cat(...)`).

54. **Complex-type inheritance implementation notes.** Mirrors entity inheritance but with two extra traps: (a) `baseSimpleName` must be derived from `complexType.baseType()` (the CSDL base reference), **not** `base.baseType()` — a resolved base type may itself have a `null` `baseType`, which throws `NullPointerException` in `Names.simpleNameFromFullName`. (b) A static `builder()` in a subtype clashes with the inherited `builder()` (no covariant return types for static methods), so the `Builder` is generated only for top-level concrete types and subtypes rely on `with*()`. (c) `with*` and `Builder` are both skipped for `Abstract="true"` complex types so no `new AbstractX(...)` is emitted. Verified by `ComplexTypeGeneratorInheritanceTest` (TripPin `EventLocation extends Location`).

55. **Edm.Guid keys must be unquoted in URLs.** `ContextPath.formatValue()` previously wrapped every `String` key in single quotes, producing `Advertisements('guid')` — OData Demo rejects this with HTTP 400 ("Error in query syntax") and also rejects the `guid'...'` literal. The service's own `@odata.mediaReadLink` uses the bare form `Advertisements(<guid>)/$value`. Fixed by detecting UUID-shaped `String` keys in `formatValue()` and emitting them unquoted. Verified end-to-end by `ODataDemoMediaTest` (the only Guid-key entity in the test metadata is `Advertisement`).

56. **`GeneratorCompilationTest` only resolves the runtime from the installed `.m2` jar (the sibling `target/classes` fallback is dead).** The test builds its compile classpath from `findClasspathJars()` (walks `.m2`) and *also* prepends `../odata-codegen-runtime/target/classes` — but Maven runs tests with the repo root as cwd, so `../odata-codegen-runtime` points above the repo and never exists. Therefore the `.m2` runtime jar is the only source, and it must be current. If you add classes to the runtime, `mvn install` the runtime (or run a full `clean install` reactor) **before** `odata-codegen-core` tests, or `GeneratorCompilationTest` fails with `cannot find symbol` for the new types. This bit when `ApplyExpression`/`ApplyBuilder`/`RawApplyExpression` were added to the runtime but the `.m2` jar was stale.

57. **Complex-type `with*` methods must not reference `unmappedFields` for non-open types.** `ComplexTypeGenerator.generateWithMethod` and `generateNavWithMethod` always appended `, this.unmappedFields)` in the copy-on-write constructor call. For non-open complex types (e.g. TripPin `City`), no `unmappedFields` field is declared, so the generated code doesn't compile. Fix: gate the `this.unmappedFields` append on `hierarchyHasOpen`. Verified by `OpenTypeGeneratorTest.nonOpenComplexTypeDoesNotReferenceUnmappedFieldsInWith` (TDD: compilation failure before fix, `assertFalse(code.contains("unmappedFields"))` after).

58. **`EntityGenerator` nav import must resolve the correct package suffix for complex-type nav targets.** The nav import loop used `Names.packageNameSuffixEntity()` for all nav target types. If a nav points to a complex type (not an entity), the generated import resolves to the wrong package (e.g. `com.example.trippin.entity.Location` instead of `com.example.trippin.complex.Location`). Fix: check `schema.complexTypes()` first; use `packageNameSuffixComplexType()` if matched. This was not caught in TripPin (all entity nav targets are entities), but would break for services where entities navigate to complex types. Verified by `OpenTypeGeneratorTest.nonOpenComplexTypeDoesNotReferenceUnmappedFieldsInWith` and full reactor BUILD SUCCESS.

59. **Expanded navigation property data materializes into typed getters on entities and complex types.** `getTrips()` returns `List<Trip>`, `getPhoto()` returns `Optional<Photo>` — the expanded JSON deserializes directly into the nav fields via `@JsonProperty`. This replaces the old throwing accessors (decision 6b). For complex types, `getAirportRef()` on `Location` returns `Optional<Airport>`. Live-verified by `TripPinGeneratedClientTest.expandWithNestedExpand` (asserts `person.getTrips()` is non-empty and `trip.getPlanItems()` contains nested expanded data).

60. **`toJavaFieldName` lowercasing undoes `sanitizeIdentifier`'s reserved-word protection.** `sanitizeIdentifier("class")` capitalizes to `"Class"` (not a reserved word), but `toJavaFieldName` then lowercases the first char back to `"class"` — a Java reserved word. The generated field `private final String class;` won't compile. Fix: check `isReservedWord` after lowercasing and append `_`. Same issue affects `getClass()` — a `final` method on `Object` that can't be overridden. The getter for property `"class"` becomes `getClass()`, which the compiler rejects. Fix: check generated getter names against `Object` method names and append `_` if they collide. Verified by `ReservedWordsCompilationTest` (compiles generated code with 40+ reserved-word property names: `class`, `new`, `int`, `return`, `package`, `import`, `abstract`, `interface`, etc.).
