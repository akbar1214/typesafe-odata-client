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

### 4. Immutable-by-Contract with Copy-on-Write

**Decision:** Generated entities use non-final `protected` fields but enforce immutability through copy-on-write `with*()` methods and immutable getters.

**Reason:** The reference implementation claims immutability but has:
- `protected` non-final fields (due to JVM 256-arg constructor limit)
- Mutable `UnmappedFieldsImpl` (wraps `HashMap`)
- `changedFields` set to `null` after `patch()` (NPE risk)

**Our approach:**
- Fields are `protected` (non-final) — allows no-args constructor for Jackson, Builder, and `with*()`
- Jackson deserialization via public no-args constructor + `@JsonProperty` setters (correct types)
- Public no-args constructor for Builder/`with*()` copy-on-write
- Public setters for all own properties (used by Jackson, Builder, and `with*()`)
- Getters return immutable views: `Collections.unmodifiableList()` for collections, `Optional` for nullable
- `with*()` methods deep-copy collections (`List.copyOf`) and `unmappedFields` (`new HashMap<>`)
- `ChangedFields` is a separate `Set<String>` tracked via `EntityUtil.mergeChanged()`
- No `@JacksonInject` coupling — model is annotation-free except for `@JsonProperty`

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
// Generated: public class SchemaInfo implements SchemaInfo { ... }
```

### 6a. Generated Schema-Info Registry Class Named `SchemaInfo`
**Decision:** The generated per-package type registry is `<basePackage>.schema.SchemaInfo` (exposing `SchemaInfo.INSTANCE`), implementing the runtime `io.github.akbarhusain.odata.runtime.entity.SchemaInfo` interface — which is always referenced by its fully-qualified name inside the generated file, so the shared simple name is safe.

**Reason:** Originally named `ServiceSchemaInfo` to dodge a perceived simple-name collision with the runtime interface; in practice no generated file ever imports the runtime interface (it appears only in runtime method signatures), and the generator already emitted the `implements` clause as an FQN. Users found `ServiceSchemaInfo` noisy — the registry IS the schema info. Renamed to `SchemaInfo` (breaking, pre-1.0): `Names.schemaInfoClassName()` is the single source of the name, and every emission site interpolates it rather than hardcoding. Verified by `GeneratorSchemaInfoAggregationTest`/`GeneratorIntegrationTest` (file names + content), the full compile-harness suite (uncompilable output would fail), and reactor 751.

### 6b. Entity Navigation Properties Materialize Expanded Data

**Decision:** Entity and complex-type nav properties deserialized from JSON via `@JsonProperty` setters hold expanded data in `protected` fields, exposed via typed getters (e.g., `person.getTrips()` → `List<Trip>`, `location.getAirportRef()` → `Optional<Airport>`).

**Reason:** When `$expand` is used, OData returns navigation data inline with the entity. The generated `@JsonProperty("Trips") public void setTrips(List<Trip> trips)` setter accepts the nav JSON, which Jackson deserializes automatically. This gives users direct access to expanded data without dropping to raw HTTP.

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
├── BadRequestException (400)
├── UnauthorizedException (401)
├── ForbiddenException (403)
├── NotFoundException (404)
├── ConflictException (409)
├── PreconditionFailedException (412)
├── RateLimitException (429, with retryAfter)
└── ServerException (5xx)
```

### 8. Serialization-Agnostic Model

**Decision:** Generated entities use Jackson `@JsonProperty` setter annotations for deserialization, but the `Serializer` interface is pluggable.

**Reason:** The reference's entities are annotated with `@JsonAnySetter`, `@JacksonInject`, etc. We simplified to just `@JsonProperty` on public setters — the minimum needed for Jackson deserialization. The `Serializer` interface allows plugging in custom serialization logic, but Jackson annotations on the model are required for the default `JacksonSerializer`.

**Our approach:** Entities are annotated with `@JsonProperty` setters for Jackson. `Serializer` interface is pluggable for custom serialization:
```java
public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);
    <T> T deserialize(byte[] data, Class<T> type);
}
```

**Known limitation:** Swapping to Gson or JSON-B requires removing `@JsonProperty` and implementing a custom `Serializer` with schema-driven deserialization.

**Known limitation (as of v0.1.0):** The `EntityGenerator` currently emits `@JsonProperty` annotations on generated entity/complex-type setters for Jackson deserialization. This couples the model to Jackson despite the pluggable `Serializer` interface. The `Serializer` interface works for serialization (POST/PATCH bodies) but deserialization relies on Jackson annotations. Swapping to Gson or JSON-B requires either removing annotations and writing a schema-driven deserializer, or registering Jackson mixins. This is documented tech debt tracked in issue #10 of the code review.

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

### 17. `PropertyExpression<E, T>` Unifies `select` and `orderBy`

**Decision:** Introduce `PropertyExpression<E, T> extends OrderExpression<E, T>` with `getEdmName()`, implemented by `StringProperty`, `NumberProperty`, `DateTimeProperty`, `BooleanProperty`, `EnumProperty`.

**Reason:** `select(...)` previously accepted only `StringProperty`. Widening to `PropertyExpression<?, ?>` lets any property type be selected, and gives a single source of truth for the OData property name reused by both `$select` and `$expand` nested selects. `OrderExpression.getEdmName()` (which threw) was removed from the base and now lives only on `PropertyExpression`.

**Note:** `E` is the owning entity type and `T` is the property value type. This extra entity parameter lets collection-request `select()`/`orderBy()` and `NavProperty`/`NavQuery` options reject properties belonging to a different entity at compile time (see decision 27).

### 18. Nested `$expand` Options via `NavQuery`

**Decision:** `NavProperty<E, T>` gains `select()`/`orderBy()`/`filter()`/`top()` methods returning a `NavQuery<E, T>` record; `expand()` accepts either `NavProperty<? super E, ?>` or `NavQuery<? super E, ?>`.

**Reason:** OData allows `$expand=Trips($select=...;$filter=...;$top=...;$orderby=...)`. Exposing `NavQuery` lets users nest those options type-safely on the navigation target without string concatenation. `NavQuery<S, T>` carries both the source entity `S` (for type-checking in collection-request `expand`) and the target entity `T` (for type-checking nested `select`/`orderBy`/`filter`), and `NavQuery.toODataExpand()` renders the full `Trips(...)` clause. `RequestGenerator` appends it to the `$expand` query option.

### 18a. Type-Safe Polymorphic Expands (Cast Segments)
**Decision:** `NavQuery` carries a nullable `castSegment` rendered as `NavName/Cast(options)`; `NavProperty<E,T>.as(String qualifiedCast, Class<S> subtype)` with `<S extends T>` returns `NavQuery<E,S>` — nested options then validate against the SUBTYPE (its constants and inherited base constants both satisfy `? super S`), and casting to an unrelated type is a compile error via the bound alone. `NavQuery.raw(String)` seeds the expand ROOT path — options chained afterwards render, MERGED into any option group the raw string already carries (`raw("A($expand=x)").top(1)` → `A($expand=x;$top=1)`), never silently dropped and never a second paren group. The generator emits a `<NAV>_AS_<TYPE>` constant per (own navigation, known target subtype) with the resolved qualified CSDL name baked in (`VERSIONS_AS_DOC = VERSIONS.as("NS.Doc", Doc.class)`), deduped per the decision-54 policy; no constants when the target has no subtypes. The subtype index is identity-keyed and built once per generator (lesson 178: O(1) lookups or large-metadata generation degrades quadratically).

**Amendment (decision 18a, same-simple-name subtypes):** Two schemas may declare entities with the SAME simple name (split-merge metadata), and a subtype may share its simple name with the class being generated. Importing either produces ambiguous or self-colliding references — and javac cascades confusing follow-on errors across the file. `subtypeReferences` classifies every cast-subtype before imports print: the generated class itself stays an unqualified self-reference and is never imported; any subtype whose simple name collides (with the generated class from another package, or with another subtype from a different package) is referenced by FULLY-QUALIFIED name and never imported.

**Amendment 2 (decision 18a, general contested-reference resolution):** the same ambiguity arises everywhere per-schema packages put same-named types in different output packages — nav-target classes on entities, request classes on entity requests, collection/entity request classes in the container, property complex/enum types. A shared deterministic resolver (`TypeRefs.resolve`) is populated per generated file BEFORE imports print: a simple name claimed by exactly one type stays simple + imported; a name claimed by more than one type makes EVERY claimant referenced fully-qualified and never imported (no first-wins — that would be order-dependent, lesson 131's class of bug). Wiring: `AbstractTypeGenerator.typeRefs` + `refFor`/`isContested` hooks applied in `navJavaType`, `resolveClassNameForConstant`, `addPropertyImports`; per-generator candidate collection in `EntityGenerator` (nav targets + property types + cast subtypes), `RequestGenerator` (nav collection/entity request classes), and `ContainerGenerator` (set/singleton request classes incl. keyed-overload returns). Verified by `CrossSchemaSimpleNameCompilationTest` (two `A` entities across `One`/`Two` packages, cross-schema base — and the concrete cross-package subtype exercises with*/navWith copy code touching the parent's protected lifecycle fields (`e.contextPath`/`e.etag`/`mergeChanged`) in the foreign package: legal per JLS 6.6.2 and compiling; FQN pins hold).

**Reason:** The davidmoten-style raw query `Versions/ABC.Doc($expand=abc)` was unreachable in the typed API (the cast narrows to the subtype and unlocks subtype-only navigations), and expanded navs do not subtype-resolve on deserialization (decision 46 is request-level only), so no workaround exists. The generics do the safety work: `? super S` bounds already accept subtype constants post-cast. Verified by `NavPropertyExpandTest` +3 (cast rendering with options, raw verbatim, blank-cast rejection), `EntityGeneratorPolymorphicExpandTest` 2 (constant + import emission; none-when-no-subtypes), `QueryTypeSafetyCompilationTest.asCastToNonSubtypeFailsToCompile` (negative compile), and the full TripPin compile harness (cast constants compile against the runtime).

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

**Decision:** Entities and complex types declared `OpenType="true"` (or inheriting openness from a base) capture undeclared JSON fields into `unmappedFields` on deserialization, expose them via `getUnmappedFields()` / `getDynamicProperty(String)`, and round-trip them on serialization. Known properties go through `@JsonProperty` setters with correct types; only unknown properties pass through `@JsonAnySetter`.

**Reason:** OData open types may carry dynamic properties not present in the CSDL — a service can return extra JSON fields (TripPin `Person`/`Event`/`Location`, OData Demo `Category`). The `@JsonProperty` setters handle known properties with full type safety; the `@JsonAnySetter` catches the rest.

**Approach:**
- `openTypeResolved(type)` walks the base chain — a type is open if it or any ancestor is open.
- The **root** class initializes `unmappedFields` to a mutable `new HashMap<>()` when `subtreeHasOpen(root)`; otherwise `Map.of()`.
- A `@JsonAnySetter` is generated **only at the topmost open type** — filters out `@`-prefixed control annotations.
- `@JsonAnyGetter` on `getUnmappedFields()` returns an unmodifiable view for round-trip serialization.

### 23. Batch Changeset Support

**Decision:** `BatchRequest` supports grouped operations (`Changeset`) that the server executes atomically. Changesets are encoded as nested `multipart/mixed` boundaries with `Content-ID` headers per the OData v4 spec.

**Reason:** Real OData services require transactional mutation: POST a Customer AND POST an Order in one atomic unit. Without changesets, users must implement their own rollback logic.

**API:**
```java
Changeset cs = new Changeset(List.of(
    BatchOperation.post("Customers", customerJson),
    BatchOperation.post("Orders", orderJson)
));

BatchResponse response = context.batch()
    .addChangeset(cs)
    .add(BatchOperation.get("Customers"))
    .execute();
```
- `Changeset` wraps `List<BatchOperation>`, immutable via `List.copyOf`.
- Encoding nests changeset ops in a separate `multipart/mixed; boundary=...` with `Content-ID: 1`, `Content-ID: 2`, etc.
- Decoding is recursive: `decodeParts()` detects nested `multipart/mixed` boundaries in the response and flattens all results.
- `MultipartHelper.encodeChangeset()` / `encodeBatchRequest()` / `decodeParts()` / `decodePartOrNested()` support the nesting.
- 7 new tests covering encode, decode, mixed entries, round-trip.

**Known limitation:** Content-ID references (`$N` patterns in URLs within a changeset) are not yet resolved. Tracked as follow-up.

### 24. Simplified Entity/Complex-Type Deserialization via No-Args Constructor + `@JsonProperty` Setters

**Decision:** All entities and complex types use a public no-args constructor plus `@JsonProperty` setters for Jackson deserialization. The previous hybrid approach (`@JsonCreator` for normal entities, `@JsonAnySetter` switch for wide entities) has been removed.

**Reason:** The hybrid approach added significant generator complexity and the wide-entity path relied on unsafe direct casts (`(SomeType) value`) that failed for nested complex types and enums. Aligning with `davidmoten/odata-client`, setter-based deserialization is simpler, avoids the JVM 255-parameter limit entirely, and lets Jackson handle type conversion for nested objects and collections. The same no-args constructor also serves the Builder and `with*()` copy-on-write paths.

**Amendment (decision 43):** setters are now emitted for own properties on abstract types as well — the original `!abstractType` gating silently dropped base properties when deserializing concrete subtypes.

**Details:**
- `@com.fasterxml.jackson.annotation.JsonProperty` setters are generated for all own properties and navigation properties (entity and complex type).
- `@JsonAnySetter` is still generated only at the topmost open type to capture dynamic properties; known properties are handled by typed setters.
- Lifecycle fields (`etag`, `contextPath`, `unmappedFields`, `changedFields`) are initialized in the root class no-args constructor.
- Collection getters are null-safe: `return field == null ? List.of() : Collections.unmodifiableList(field);`.
- Setters are generated for `ownProps` only; inherited fields are copied by name (`this.fieldName`) in `with*()` methods.
- Builder is created for root-level concrete entities and complex types only; subtypes rely on `with*()` to avoid static-method clashes.

**Note:** Complex-type fields changed from `protected final` to `protected` so Jackson setters and `with*()` field assignment can mutate them.

### 25. Copy-on-Write Defensive Copying in `with*()` Methods

**Decision:** The `with*()` and nav-`with*()` methods defensively copy collection fields and `unmappedFields` to prevent shared mutable state between original and copy.

**Reason:** Without defensive copies, two entities post-`with*` share the same `List` and `HashMap` internals. A mutation on one (e.g., via a setter) would affect the other, violating copy-on-write semantics.

**Implementation:**
- Collection-typed properties: `e.colors = this.colors == null ? null : List.copyOf(this.colors);`
- Collection navs: `e.trips = this.trips == null ? null : List.copyOf(this.trips);`
- `unmappedFields`: `e.unmappedFields = unmappedFields == null ? null : new java.util.HashMap<>(unmappedFields);`
- `changedFields`: via `EntityUtil.mergeChanged()`

### 26. Nav Property Getter/With-Method Name Sanitization

**Decision:** Nav property getter and `with*` method names use `Names.navGetterMethod()` / `Names.navWithMethod()` which apply `sanitizeIdentifier` and `isObjectMethodName` checks (same as property getters — lesson 60).

**Reason:** A nav property named `class` would produce `getClass()` which collides with `Object.getClass()` (final, can't override). The sanitizer produces `getClass_()` instead.

**Implementation:**
- `Names.navGetterMethod(navName)` = `isObjectMethodName(get<sanitized>) ? "getClass_" : "get<sanitized>"`
- Applied in `EntityGenerator.navGetterName()` and `ComplexTypeGenerator.navGetterName()`
- 3 new tests in `NavReservedWordTest`

### 27. Narrowed `select`/`orderBy`/`expand` Type Bounds

**Decision:** Generated collection-request `select()`, `orderBy()`, and `expand()` methods use `? super E` bounds so only properties/navigations belonging to the entity (or its base types) are accepted. `PropertyExpression<E, T>` and `OrderExpression<E, T>` carry the owning entity type `E`; `NavQuery<S, T>` carries both source `S` and target `T`.

**Reason:** `filter()` was already type-safe (`FilterExpression<? super E>`), but `select()`/`orderBy()`/`expand()` accepted any `PropertyExpression`/`NavProperty`. This allowed clearly wrong code like `client.people().select(Trip.NAME)` or `client.people().expand(Trip.FLIGHTS)` to compile. Adding the entity type parameter catches cross-entity mistakes at compile time while preserving inheritance: a base-type property is accepted on a subtype collection because `PropertyExpression<PlanItem, ?>` satisfies `PropertyExpression<? super Flight, ?>`.

**Implementation:**
- `OrderExpression<E, T>` and `PropertyExpression<E, T>` updated to expose the owning entity type.
- `StringProperty<E>`, `NumberProperty<E, N>`, `BooleanProperty<E>`, `DateTimeProperty<E>`, `EnumProperty<E, V>` implement the two-parameter interfaces.
- `NavProperty<E, T>` and `NavQuery<S, T>` use `PropertyExpression<? super T, ?>`, `OrderExpression<? super T, ?>`, and `NavProperty<? super T, ?>` for nested options.
- `RequestGenerator` emits:
  - `select(PropertyExpression<? super E, ?>...)`
  - `orderBy(OrderExpression<? super E, ?>...)`
  - `expand(NavProperty<? super E, ?>...)`
  - `expand(NavProperty.NavQuery<? super E, ?>...)`
- 6 new tests: `RequestGeneratorNarrowQueryTest` (5, content assertions) + `QueryTypeSafetyCompilationTest` (1, negative compile test proving cross-entity usage fails).

### 28. Typed Collection Lambda Operators (`any` / `all`) via Generated `Filterable`

**Decision:** `CollectionProperty<E, T, F>` gains a third type parameter `F` (the filterable type) and `any()`/`all()` accept `Function<F, FilterExpression<T>>`. Generated entity and complex types expose a `public static class Filterable` with typed property fields, and collection property constants are instantiated with the target type's `Filterable::new` factory.

**Reason:** The previous `CollectionProperty.FilterableElement<T>` only offered stringly-typed accessors (`stringField("Budgt")`), so typos in property names produced invalid OData at runtime. A generated per-type `Filterable` lets users write `Person.TRIPS.any(trip -> trip.BUDGET.greaterThan(500f))`, catching wrong property names and operator/type mismatches at compile time.

**Implementation:**
- `CollectionProperty` now carries `Supplier<F> filterableFactory`; `any`/`all` call `factory.get()` and pass the instance to the lambda.
- `EntityGenerator` and `ComplexTypeGenerator` emit a `Filterable` inner class exposing:
  - Scalar primitive properties (`StringProperty`, `NumberProperty`, `BooleanProperty`, `DateTimeProperty`, `EnumProperty`) with `x/` prefix.
  - Collection navigation properties as `CollectionProperty<Type, Target, Target.Filterable>` with `x/` prefix (enables nested `any`/`all`).
  - Collection structural properties: entity/complex element types use the element's `Filterable`; primitive element types fall back to `CollectionProperty.FilterableElement<T>`.
- 7 new tests: `EntityGeneratorFilterableTest` (5, content assertions) + `CollectionPropertyTypedLambdaTest` (2, runtime lambda rendering).

### 29. Pagination Helpers: `nextPage(String)` and `countValue()`

**Decision:** Generated collection requests expose `nextPage(String nextLink)` for server-driven paging and `countValue()` for the count-only `/$count` endpoint. The existing `count()` method continues to request an inline count via `$count=true`.

**Reason:** `docs/content/how-to/pagination.md` documented `nextPage(nextLink)` but the generator never emitted it. Also, `count()` was documented as `GET /People/$count` even though it emitted `$count=true`, which confused users. Providing both options makes the API match the docs and the OData spec:
- `$count=true` returns the collection plus `@odata.count`.
- `/$count` returns just the number.

**Implementation:**
- `ContextPath.fromNextLink(String)` resolves absolute `@odata.nextLink` URLs and service-root-relative URLs against the current base path.
- `ContextPath.addCountSegment()` appends `/$count` to the resource path while moving existing query parameters onto the new terminal segment, producing URLs like `/People/$count?$filter=Age%20gt%2025`.
- `EntityOperations.executeCount(Context, ContextPath)` performs a GET on the count path and parses the plain numeric response.
- `RequestGenerator.generateCollectionRequest` emits `nextPage(String)` and `countValue()` on every collection request class.

### 30. Structured OData Error in Base `ODataException`

**Decision:** The base `ODataException` carries the parsed `ODataError` from the response body. All typed exceptions inherit `getError()` from the base class, and `ODataException.fromResponse()` parses the error once for every status code (including unmapped codes).

**Reason:** Previously `ODataError.fromResponse()` was only used by `RateLimitException`, and other typed exceptions either stored the error in a duplicated field or discarded it entirely. Unmapped status codes (e.g. 500) produced a generic `ODataException` with no structured `error.code` or `error.message`. Callers had to parse the exception message to inspect server errors.

**Implementation:**
- Added an `ODataError error` field and `getError()` getter to `ODataException`.
- Added constructors accepting an `ODataError`.
- `ODataException.fromResponse()` parses `ODataError` once and passes it into typed exception constructors; the generic fallback for unmapped codes now also carries the error.
- Removed the duplicated `error` field from `BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`, and `RateLimitException`.

### 31. Maven Plugin Incremental Build

**Decision:** `GenerateMojo` supports incremental generation via an MD5 hash marker file and exposes `skip` and `forceRegenerate` parameters.

**Reason:** The plugin previously parsed metadata and regenerated every file on every build, wasting time for large schemas when the metadata had not changed.

**Implementation:**
- Added `skip` parameter (`-Dodata.skip=true`) to bypass generation entirely.
- Added `forceRegenerate` parameter (`-Dodata.forceRegenerate=true`) to override incremental behavior.
- The plugin computes an MD5 hash of the resolved metadata (file or downloaded URL) and stores it in `.odata-generation-marker` inside the output directory.
- On subsequent runs, if the marker exists, the hash matches, and at least one `.java` file is present, generation is skipped and the existing source root is reused.
- URL metadata is downloaded to a temp file so it can be hashed before parsing.

### 32. Shared `AbstractTypeGenerator` Base Class

**Decision:** `EntityGenerator` and `ComplexTypeGenerator` extend a common `AbstractTypeGenerator` base class that houses duplicated helper logic.

**Reason:** The two generators were independently maintaining near-identical code for:
- Type resolution (`resolvePropertyJavaType`, `resolveSingleJavaType`, `resolveTypeDefinition`)
- Cross-schema package/type resolution (`basePackageForType`)
- Import collection (`addPropertyImports`, `addNavImports`)
- Navigation-property rendering (`navJavaType`, `navGetterName`, `navWithMethod`, `generateNavGetter`)
- Typed `Filterable` inner-class generation for collection lambdas (`any`/`all`)

Keeping two copies made bug fixes and consistency work (e.g., reserved-word sanitization, cross-namespace resolution) error-prone. A shared base means a fix in one place benefits both entity and complex-type generation.

**Approach:**
- `AbstractTypeGenerator` is package-visible abstract infrastructure; it does **not** generate code on its own.
- It exposes `protected` helper methods that preserve the exact generated strings and semantics of the previously duplicated code.
- It stores `basePackage`, `schemaPackages`, `defaultBasePackage`, `allSchemas`, and `effectiveSchemas` so cross-schema helpers work uniformly.
- Inheritance walking (`findBase`, `inheritedProperties`, `inheritedNavProperties`, `openTypeResolved`, `subtreeHasOpen`) remains in each subclass because it is tightly coupled to `EntityTypeModel` vs `ComplexTypeModel`.
- `with*`/`Builder` generation and lifecycle-field handling also remain subclass-specific because entities track `changedFields`, `etag`, and `contextPath` while complex types do not.

**Files:**
- New: `odata-codegen-core/.../generator/AbstractTypeGenerator.java`
- Modified: `odata-codegen-core/.../generator/EntityGenerator.java`, `ComplexTypeGenerator.java`

**Tests:** Full `odata-codegen-core` test suite (122 tests) plus the cross-module reactor (450 tests) verifies generated output is byte-identical.

### 33. Null-Safe Property Comparison Operators

**Decision:** `equalTo(null)` and `notEqualTo(null)` on typed property expressions route to `isNull()` and `isNotNull()` instead of throwing or emitting invalid OData.

**Reason:** The query API docs already show `equalTo(null)` as the natural way to express a null check. The behavior was inconsistent and broken:
- `StringProperty.equalTo(null)` threw NPE inside `escape(value)`
- `NumberProperty.equalTo(null)` silently emitted `Field eq null`
- `DateTimeProperty.equalTo(null)` threw NPE
- `BooleanProperty`/`EnumProperty` threw NPE

Making the operators null-safe is the least surprising choice and keeps users from having to switch between `equalTo(value)` and `isNull()` depending on whether the value is known at compile time.

**Approach:**
- Added null checks in `StringProperty.equalTo`/`notEqualTo`, `NumberExpression.equalTo`/`notEqualTo`, `DateTimeProperty.equalTo`/`notEqualTo`, and `EnumProperty.equalTo`/`notEqualTo`.
- Added `BooleanProperty.equalTo(Boolean)` and `notEqualTo(Boolean)` overloads (the primitive `equalTo(boolean)`/`notEqualTo(boolean)` variants remain unchanged). Null `Boolean` routes to `isNull()`/`isNotNull()`.
- Comparison operators (`greaterThan`, etc.) are left unchanged; passing `null` to those is a programming error and should be caught early.

**Tests:** `StringPropertyTest`, `NumberExpressionTest`, `DateTimePropertyTest`, `BooleanPropertyTest`, `EnumPropertyTest` — 2 null-handling tests each.

### 34. Query API Completeness

**Decision:** Implement documented query methods that were missing from the runtime rather than trimming the docs.

**Reason:** The documentation already described several operations (`StringProperty.equalToIgnoreCase`, `DateTimeProperty.year`, `NumberExpression.negate`, `CollectionProperty.contains`, etc.) but the runtime did not expose them. Trimming the docs would reduce API surface; implementing them makes the typed query API feel complete and consistent.

**Approach:**
- `StringProperty`: added `concat(String)` and `concat(StringProperty<E>)`. (`equalToIgnoreCase`/`notEqualToIgnoreCase` were added here but later removed — they are non-standard OData wrappers; `StringProperty.toLower().equalTo(...)` achieves the same with the real `tolower()` function.)
- `DateTimeProperty`: added `year()`, `month()`, `day()`, `hour()`, `minute()`, `second()` returning `NumberExpression<Integer, E>`, plus `date()` and `time()` returning `DateTimeProperty<E>`.
- `NumberExpression`: added `negate()` returning `NumberExpression<N, E>`.
- `BooleanProperty`: `notEqualTo(boolean)` / `notEqualTo(Boolean)` were already added for null handling (#3).
- `CollectionProperty`: added `contains(T value)` with type-aware string quoting and `length()` returning `NumberExpression<Integer, E>`.

**Tests:** `StringPropertyTest` (5), `DateTimePropertyTest` (8), `NumberExpressionTest` (1), `CollectionPropertyTest` (3).

### 35. Serialization Excludes Lifecycle Metadata and Empty Collections

**Decision:** The default Jackson serializer omits `null`/empty `Optional` values, lifecycle metadata (`changedFields`, `key`, `etag`, `unmappedFields`, `contextPath`), and empty collections from serialized entity/complex-type bodies.

**Reason:** The initial serializer produced POST/PATCH bodies like:
```json
{
  "changedFields": ["UserName", ...],
  "key": "testuser",
  "Photo": null,
  "@odata.etag": null,
  "Friends": [],
  "Trips": []
}
```
- `@odata.etag: null` caused TripPin to reject the request: "The 'odata.etag' instance or property annotation has a null value."
- Empty navigation arrays (`Friends: []`, `Trips: []`) caused TripPin to fail with "Sequence contains no matching element" while trying to resolve navigation links.
- `changedFields` and `key` are client-side bookkeeping, not OData payload properties.

**Approach:**
- Added `@JsonIgnore` to `ODataType` and `ODataEntityType` interface methods (`getUnmappedFields`, `getContextPath`, `getChangedFields`, `getKey`, `getETag`) so Jackson never treats them as serializable properties.
- Changed the default `ObjectMapper` serialization inclusion from `NON_NULL` to `NON_ABSENT` so empty `Optional` values (e.g. unset nullable nav properties) are omitted.
- Added type-config overrides for `Collection`, `List`, and `Set` with `NON_EMPTY` so empty collections are omitted while empty strings and zero numbers are still serialized.
- Kept `serializeIncludeNulls()` with `ALWAYS` for callers that explicitly need nulls.

**Tests:** `TripPinGeneratedClientTest.createAndDeletePerson` now creates and deletes a real Person without a catch block; full reactor (450 tests) verifies no regressions.

### 36. `@SafeVarargs` and Private Request State

**Decision:** Generated request classes are `final`, their `context`/`contextPath` fields are `private final`, and the varargs query methods are `public final` with `@SafeVarargs`.

**Reason:** 
- The type-safe query API uses bounded wildcard varargs such as `PropertyExpression<? super E, ?>...`. Java warns about heap pollution for any parameterized vararg unless the method is `final` and the author asserts safety with `@SafeVarargs`.
- `context` and `contextPath` are internal request state; exposing them as `protected` leaks implementation details and invites subclass coupling. Generated request classes are not designed for inheritance.

**Approach:**
- Changed generated collection-request classes from `public class` to `public final class`.
- Changed `context` and `contextPath` fields from `protected final` to `private final` in both collection and entity request generation.
- Declared the four varargs methods (`select`, `expand(NavProperty...)`, `expand(NavQuery...)`, `orderBy`) as `public final`.
- Added `@SafeVarargs` to each of them.

**Tests:** Full reactor (450 tests) compiles without varargs heap-pollution warnings; `TripPinGeneratedClientTest`, `NorthwindGeneratedClientTest`, and `ODataDemoGeneratedClientTest` exercise the generated methods at runtime.

### 37. Entity Creation (`create`) and Full Replace (`put`) on Generated Request Classes

**Decision:** Generated collection requests expose `create(Entity)` (POST to the entity set) and `postToBatchOperation(Entity)`; generated entity requests expose `put(Entity)`, `putWithETag(Entity, etag)`, and `putToBatchOperation(Entity)`.

**Reason:** The runtime had `EntityOperations.executePostEntity` since the beginning, but no generated code ever called it — the only way to create an entity was hand-building `BatchOperation.post` with a raw URL and serializing the body yourself (code review H1). CRUD was incomplete without C, and PUT full-replace existed only for media streams (`putMedia`).

**Approach:**
- `RequestGenerator` emits `create()` right after `toList()` on collection requests. POST must not carry query options, so it uses `contextPath`, not `buildContext()`.
- `put()`/`putWithETag()` are emitted on entity requests after the patch methods; `putWithETag` adds `If-Match` for optimistic concurrency.
- Batch variants mirror `patchToBatchOperation`: serialize via `context.serializer().serialize(entity, X.class)` and wrap in `BatchOperation.post/put(contextPath.toRelativeUrl(), body)`.
- `EntityOperations` gains `executePutEntity` / `executePutEntityWithETag`; both POST and PUT return `null` on empty response bodies (some services return 204) via a shared `deserializeOrNull()` guard instead of failing to deserialize.

**Tests:** `EntityOperationsCreatePutTest` (5, mock transport), `RequestGeneratorCreateTest` (5, generated-code content), and the live `TripPinGeneratedClientTest.createAndDeletePerson` now creates via `client.people().create(...)`.

### 38. `ContextPath.fromNextLink` Parses Query Strings into the Trailing Query Segment

**Decision:** `fromNextLink(String)` splits the nextLink at `?`, resolves the path part against the current base path, and appends each decoded `k=v` pair through `addQuery()` so queries live on the trailing query segment.

**Reason:** `@odata.nextLink` values routinely carry query parameters (`?$skiptoken=...&$top=5`). The old implementation stored the entire nextLink — query included — as the new `basePath`, so chaining `.filter()`/`.top()` on a next page produced `https://service/People?$skiptoken=x?$filter=...`, a double `?` (code review H2). `addCountSegment()` on a next page also rendered `/$count` *after* the query string (`...People?$skiptoken=abc/$count`).

**Approach:**
- Parse path/query at the first `?`, decode each `k=v` pair with `URLDecoder` (values in a nextLink are URL-encoded), and re-encode at render time via the existing `encodeQueryParam` (which restores OData-safe characters — `%27` re-encodes as a literal `'`; `%20` stays `%20`).
- Handles absolute and root-relative/relative nextLinks; `addQuery` semantics (append to last segment, or create the empty-name trailing segment) are reused unchanged.

**Tests:** 5 new `ContextPathTest` cases: chaining query options onto absolute/relative nextLinks yields a single `?`, multiple query params are preserved, encoded values re-encode per the OData-safe rules, and `$count` lands before the query string.

### 39. Byte-Based Multipart Framing and Interceptor Streaming Hook

**Decision:** `MultipartHelper` operates on `byte[]` end-to-end — operation bodies are copied verbatim into encoded requests, and decoded response bodies are returned byte-for-byte. Separately, `HttpInterceptor` gains a default `stream(request, delegate)` method; the transport-chain wrapper routes stream requests through it.

**Reason:** The old encoder/decoder round-tripped every payload through `String` (UTF-8), silently corrupting binary content (e.g. a media PUT inside a batch) and stripping legitimate body bytes (`.strip()` in the decoder) (code review H3). For streaming: the chain wrapper's `stream()` buffered the whole response through `intercept()` whenever any interceptor was registered, defeating `HttpTransport.stream()` for media downloads (code review H4).

**Approach:**
- Encoders write ASCII framing directly to a `ByteArrayOutputStream`; bodies are written with `out.writeBytes(op.body())`.
- The decoder splits parts on byte-level boundary markers, trims exactly one trailing CRLF/LF, and never re-joins body lines; response headers are collected into a case-insensitive `TreeMap` (fixes M5 — `BatchResult.getHeader` case sensitivity).
- `HttpInterceptor.stream(request, delegate)` defaults to buffering via `intercept()` (backwards compatible); interceptors that want true streaming override it and delegate to `delegate.stream(request)`.
- Verified the zero-interceptor fast path already returns the real transport (no wrappers allocated) — the original H4 claim about zero-interceptor wrapping was inaccurate and is corrected in the review.

**Tests:** `MultipartHelperTest` 17 (2 binary round-trip cases + 1 case-insensitive header case), `EntityOperationsInterceptorChainTest` 5 (2 fast-path + 1 stream-hook + 2 existing).

**Known limitation (M10, partially addressed):** the interceptor chain is still rebuilt on *every* request when interceptors are registered — `buildTransportChain()` allocates N anonymous `HttpTransport` wrappers per call in `executeAsync`/`streamMediaAsync`/`BatchRequest`. The zero-interceptor fast path allocates nothing (verified by tests), but caching the chain per `Context` would add mutable state to the `Context` record and is deferred.

### 40. Parser Fails Loudly on v3/Unknown Namespaces

**Decision:** `StaxCsdlParser.parse()` validates the root element before parsing: OData v4 `edmx:Edmx` proceeds; v3 EDMX namespaces and non-CSDL documents throw `IllegalArgumentException` with a clear message.

**Reason:** The parser only understands the v4 namespaces, but AGENTS.md claimed v3 support and v3 metadata silently parsed into an *empty model* — no warning, no error (code review H5). A wrong documented guarantee is worse than a loud failure.

**Approach:** `validateRootElement()` consumes the first start element and checks `localName == "Edmx"` against `EDMX_NS`, `EDMX_NS_V3`, or anything else, throwing with the offending namespace; non-`Edmx` roots and empty documents also throw.

**Tests:** 2 new `StaxCsdlParserTest` cases (v3 metadata → message contains "v3"; HTML document → throws).

### 41. Generated-Code Member-Name Reservations and CRUD/Count Hardening

**Decision:** (a) `Names.toJavaFieldName` reserves the generator's own emitted member names (`etag`, `contextPath`, `changedFields`, `unmappedFields`, `builder`) with a trailing `_`; leading-digit/empty identifiers are prefixed `_`; `Builder` and `Filterable` join the JDK class-name shadow list. (b) Composite-key `getKey()` builds a `HashMap` instead of `Map.of` (which throws NPE on null key values). (c) `$ref` method names are derived from the sanitized field name. (d) `EnumGenerator` keeps valid member names verbatim (no API break) and sanitizes hostile ones; `IsFlags` enums get `fromFlags(long)` returning the set of set members. (e) `countValue()` clears `$top`/`$skip` and `executeCount` sends `Accept: text/plain`; `executeAndGetCollection` returns an empty page on 204/empty bodies. (f) `BatchOperation.get` validates its URL like the other factories. (g) `GenerateMojo` folds the plugin version into the marker hash, follows multi-hop/relative redirects (`resolveRedirectUri`), sets timeouts, and cleans up temp files.

**Reason:** All driven by code review H6/H8 and M1/M2/M6/M8: hostile-but-legal CSDL names could produce uncompilable generated code (property `etag` collided with the lifecycle field; entity `Filterable` shadowed its own inner class; property `2FA` produced an invalid identifier); composite `getKey()` threw NPE on deserialized entities with null keys; upgrading the plugin silently skipped regeneration; `$count` could carry forbidden options or a JSON `Accept`.

**Tests:** `EntityGeneratorMemberNameTest` 6, `EnumGeneratorTest` 3, `RequestGeneratorPaginationTest` +1, `EntityOperationsCollectionParseTest` +1, `EntityOperationsCountTest` +1, `BatchRequestTest` +1, `GenerateMojoIncrementalTest` 8.

**Known limitation (H6, partially addressed):** constants still collide on case — properties `budget` and `Budget` both map to `toConstantName()` → `BUDGET`, producing duplicate static constants (and duplicate `Filterable` fields) and uncompilable output. Fixing requires per-class dedupe tracking of emitted constant names (`Set` passed through `generatePropertyConstant`/`generateFilterablePropertyField`) with a deterministic suffix policy; deferred.

---

### 42. Aggregate `SchemaInfo` per Output Package
**Decision:** `Generator` groups schemas by resolved output package and emits ONE merged `SchemaInfo` per package (`SchemaInfoGenerator.generate(List<SchemaModel>)`), not one per schema.
**Reason:** The Maven plugin's single `basePackage` maps every schema to one package; per-schema registries under a fixed class name silently overwrote each other, so the runtime type registry kept only the last schema. Distinct packages still get separate registries.

### 43. `@JsonProperty` Setters Are Emitted on Abstract Types Too
**Decision:** Own-property/own-nav setters are generated unconditionally, including on `Abstract="true"` types.
**Reason:** A concrete subtype of an abstract base has no setter anywhere for the base's properties otherwise (subclasses emit only their own), and Jackson's lenient mapper silently dropped them — base fields came back null. Verified by compile-**and-deserialize** tests (`AbstractHierarchyDeserializationTest`), which is the only test shape that can catch this class of bug.

### 44. `GuidProperty` for `Edm.Guid` Filter Literals
**Decision:** `Edm.Guid` properties map to a dedicated `GuidProperty<E>` that emits unquoted 8-4-4-4-12 literals (validating the shape, throwing on anything else); `getPropertyConstantType` routes `Edm.Guid` before the String fallback.
**Reason:** `Edm.Guid` mapped to `StringProperty` rendered `Id eq 'guid'` — a type error services reject — while keys were already handled correctly. One routing change covers static constants and `Filterable` fields via the shared method.

### 45. Live-Service Tests Are Hermetic-by-Exclusion
**Decision:** The seven classes hitting `services.odata.org` are `@Tag("live-service")`; root-pom surefire excludes the tag by default (plain `mvn test` is fully offline) and a `live-tests` profile includes everything.
**Reason:** Network-dependent tests fail offline/behind proxies/throttled; destructive tests on a shared public service also clean up in `finally` and skip via `assumeTrue` (never silent `return`), and eventual-consistency waits poll instead of fixed sleeps.

### 46. Polymorphic `@odata.type` Deserialization via the `SchemaInfo` Registry
**Decision:** `EntityOperations.executeAndGetEntity/executeAndGetCollection` gained SchemaInfo-aware overloads that read `@odata.type` (stripping the `#` prefix) and deserialize to the resolved subtype when assignable — per element for collections. Generated requests pass `SchemaInfo.INSTANCE`.
**Reason:** Base-typed reads silently dropped subtype properties under the lenient mapper; the registry built since decision 42 was generated but never consumed by the runtime.

### 47. Generated Enums Map JSON Numerics by Value, Strings by Name
**Decision:** Generated enums carry `@JsonCreator fromJson(Object)`: numbers resolve by CSDL **value**, strings by member name (tolerating the qualified `NS.Enum'Member'` form). `@JsonValue` is deliberately NOT added.
**Reason:** Jackson's default maps numeric payloads by **ordinal** — wrong whenever member values aren't `0..n-1` in declaration order. `@JsonValue` would flip POST/PATCH serialization from the OData v4 JSON name-string form to numbers, changing the wire format.

### 48. Plugin Incremental State: Source-Identity Markers + Stale-File Manifests
**Decision:** The generation marker file is keyed by the metadata SOURCE identity (URL/file path hash) and records a manifest of generated files; on regeneration, files absent from the new manifest are deleted (path-traversal-guarded).
**Reason:** One marker per output directory let multiple executions invalidate each other (full regeneration every build — this repo's own test module). Keying by `${mojo.execution.id}` did not resolve at runtime, and keying by the content hash breaks the manifest lookup when metadata changes (the marker name changes with it). Source identity is stable across content changes and distinct per execution.

### 49. Query Parameters Render Once, After All URL Segments
**Decision:** `ContextPath.appendSegments` collects queries from all segments and renders a single trailing `?...`.
**Reason:** Per-segment rendering produced a `?` mid-URL whenever a segment was appended after a query-bearing segment (`addQuery(...).addSegment("$ref")`). The `addCountSegment` query-migration workaround collapsed to a plain `addSegment("$count")`.

### 50. Collection Reads Honor the Pluggable `Serializer`
**Decision:** `executeAndGetCollection` branches on the configured serializer: the default `JacksonSerializer` keeps the profiled in-memory `convertValue` fast path; custom serializers receive each element's bytes via `deserialize`.
**Reason:** Custom serializers' modules/naming/date config silently never applied to collection reads. The `Serializer` interface has no tree/convert API, so the custom path re-serializes elements individually (page sizes are small).

### 51. Partial PATCH via `changedFields`, with a Safe Full-Body Fallback
**Decision:** `Serializer` gained a default `serialize(value, type, includeFields)` (Jackson filters the serialized tree); `executePatchEntity[WithETag]` uses it only when the entity is an `ODataEntityType` with a non-empty `changedFields`.
**Reason:** The change-tracking machinery existed but was never consumed. Builder and `with*` flows get true partial updates; GET-deserialized entities mutated via setters deliberately track nothing (setter-side tracking would mark deserialization as change) and keep legal full-body merge semantics. Note: a changed-but-null field serializes to nothing under `NON_ABSENT`.

### 52. Type-Driven Key Literals
**Decision:** Generated key accessors pass the key's resolved Edm type to `ContextPath.addKey(name, value, edmType)`; formatting switches on the type — `Edm.String` always quoted (even when UUID-shaped), Guid/Date/DateTimeOffset bare ISO, TimeOfDay `HH:mm:ss`, Duration `duration'...'`, qualified enums `NS.Enum'Member'`. The untyped two-arg `addKey` keeps legacy heuristics for direct callers.
**Reason:** The GUID value-shape heuristic sent UUID-shaped *string* keys unquoted, and datetime/enum keys rendered invalid literals; the generator had the Edm type all along and was discarding it.

### 53. Interceptor Chain Cached per `Context`
**Decision:** `buildTransportChain` memoizes chains in a `WeakHashMap<Context, HttpTransport>`; the zero-interceptor fast path returns the real transport untouched.
**Reason:** Building N wrapper transports per request was the last per-request allocation hotspot once interceptors are registered. Context is a record — value equality means identical configurations share one chain, and weak keys avoid pinning discarded contexts.

### 54. Constant Names Auto-Deduplicate; Field Names Still Fail Loudly
**Decision:** Per-type allocation assigns property-constant names with deterministic `_2`/`_3` suffixes on collision (covering static constants, nav constants, and `Filterable` fields). Field-level collisions (e.g. `Name` vs `name` folding to field `name`) still abort generation with a clear error.
**Reason:** Case-colliding constants previously produced duplicate declarations that didn't compile. Constants are internal API (renaming is safe), but silently renaming *fields* would break `@JsonProperty` mapping — there the metadata must change.

### 55. Multipart Batch Decoding Fails Loudly with Correlatable Results
**Decision:** Part-level `Content-ID`s propagate into `BatchResult.contentId` (+ `BatchResponse.getByContentId`); changeset Content-IDs are numbered batch-wide; undecodable parts, missing boundaries, and separator-less parts throw; delimiters are line-anchored per RFC 2046; URLs/headers reject CR/LF/NUL at `BatchOperation` construction; quoted and case-varying boundary parameters are honored.
**Reason:** A failed changeset collapses N operations into one error part — without Content-ID correlation every subsequent index silently shifts, and silent drops/truncations made garbled responses look like empty batches. Count validation is deliberately omitted (the collapse makes naive counts wrong); Content-ID correlation is the correct mechanism.

### 56. `$ref` Sends Absolute URIs; Live Tests Skip Only on Verified Service Faults
**Decision:** `EntityOperations.addRef`/`removeRef` resolve relative entity paths against the service root (mirroring batch URL resolution, decision 12); already-absolute URIs pass through, and bare key values pass through for services that accept them. The `addAndRemoveFriend` live test dynamically picks an existing person who is not currently a friend (never deletes seed links), verifies the added link is visible, and skips **only** when a 500's body matches the service's own fault signature ("Property set method not found", empty body, or `InternalServerError` without client-side markers like "relative URI"/"odata.context"/"target"). Request construction is pinned deterministically by `RefUrlResolutionTest` (mock transport), so the live test measures only the service.
**Reason:** TripPin (and spec-conformant services) require absolute `@odata.id` and `$id` URIs; the long-standing "known service limitation" was in fact our relative-URI bug plus a nonexistent seed user, and only the deterministic tests make the live skip honest — a skip must never be the only place a code path is exercised.

### 57. Identifier Sanitization Covers Constants, Enum Members, Container Accessors, and Key-Accessor Names
**Decision:** `toConstantName` maps non-identifier characters to `_` (dashes, dots, spaces — all legal CSDL NCNames); enum members whose constant differs from their CSDL name get a generated `BY_NAME` wire-name map consulted by `fromJson` after `valueOf`; container accessors (entity sets + singletons) run a collision check that fails loudly; duplicate `<Key>` elements and `PropertyRef` without `Name` are rejected at parse with context; Builder nav setters record `changed` like property setters; `EnumProperty` literals pass the `resolveTypeDefinition`-resolved type through `qualifiedEdmName`; and key-accessor method names sanitize before capitalizing (`capitalize(toJavaFieldName(keyProp))`).
**Reason:** Round-4's execution-based review proved the unsanitized paths produced output that compiled falsely or generation that "succeeded" while wrong: `FIRST-NAME` constants and `A-B(0L)` members failed compilation; prop `BUDGET` + nav `budget` produced two `BUDGET` fields in `Filterable`; set+singleton `People` emitted duplicate `people()` methods; typedef-of-enum literals rendered `Ns.Tint'Red'`; multi-`<Key>` metadata yielded per-key single-key accessors for composite entities; and a missing `PropertyRef@Name` surfaced as a bare NPE from `KeyModel`'s defensive copy.

### 58. Action ReturnType Alias Resolution (H1)

**Decision:** `StaxCsdlParser.parseAction` `ReturnType` `Type` now goes through `resolveTypeRef` like `parseFunction` does.

**Reason:** `parseAction` stored `getAttr(child,"Type")` verbatim while `parseFunction` resolved `self.Person` → `NS.Person`. An alias-qualified `Type="self.Person"` in an `Action` stayed `self.Person`, fell through `Names.resolveTypeKind` as `UNKNOWN`, and generated wrong imports. One-line parity fix (`StaxCsdlParser.java:415`). Verified by `StaxCsdlParserActionAliasTest` (expects `NS.Person`).

### 59. Global Alias Map for Cross-Schema References (H2)

**Decision:** `StaxCsdlParser` maintains `globalAliasMap: alias → namespace` (populated per `parseSchema` via `putIfAbsent`) and `resolveTypeRef` checks the global map first (then `currentAlias` fallback). After all schemas are parsed, `fixupCrossSchemaAliases` rewrites any remaining alias-qualified types (including `Collection(...)` wrappers) in every model component (entity/complex `BaseType`, property `edmType`, nav `type`, entity-set/singleton types, function/action params/return types, `TypeDefinition` underlying type, container `Extends`).

**Reason:** Prior `currentAlias` singleton only resolved aliases declared in the currently-parsed schema. A multi-schema document `Schema A (Alias=a, Foo)` + `Schema B (prop Type="a.Foo")` left `a.Foo` verbatim. Forward-order schemas (`B` before `A`) would also miss. Global map + post-pass makes resolution order-independent. Verified by `StaxCsdlParserCrossSchemaAliasTest` (2, expects `NS.A.Foo`).

### 60. Unqualified Cross-Schema Inheritance Resolution (H3)

**Decision:** `EntityGenerator.findBase` / `ComplexTypeGenerator.findBase` now fall back to `findBaseGlobal` (scan `effectiveSchemas` for simple-name match) after the qualified and same-schema lookups. `EntityGenerator.extendedBasesForSchema` resolves the base’s actual namespace via `findBaseGlobal` + `entityNamespace` instead of string-splitting `baseType`, so an unqualified `BaseType="Base"` in `NS.B` that refers to `Base` in `NS.A` is found and the base is emitted `public class Base` (not `public final class Base`).

**Reason:** Prior `findBase` only checked `entityTypeByQualifiedName` then same-schema `entityTypeMap`. Cross-schema unqualified inheritance (`Derived` in `NS.B` extends `Base` in `NS.A` via `BaseType="Base"`) returned `null`, inheritance was lost and the base stayed `final` (compile error for `extends`). Qualified `BaseType="NS.A.Base"` already worked; the fix closes the unqualified variant. Verified by `CrossSchemaInheritanceFinalTest` (qualified passes, unqualified now passes; prior unqualified was `public final class Base`).

### 61. Query-Parameter `=` Must Stay Encoded (H4)

**Decision:** `ContextPath.encodeQueryParam` no longer restores `%3D` → `=`. OData-safe restores are now `$ ' ( ) , / : @` only; `=` stays `%3D`.

**Reason:** `URLEncoder.encode("a=b")` → `a%3Db`; restoring to `a=b` made `?q=a=b` parse as `q=a` + stray `b`. `appendSegments` adds the single `name=value` separator; value-internal `=` must be `%3D`. Prior restore was over-broad (lesson 12). Verified by `ContextPathEncodeQueryParamTest` (2, `a=b` → `a%3Db`) and updated `ContextPathTest.fromNextLinkDecodesPercentEncodedPlusAndReencodesAsPercent2B` to expect `%3D` (`a%2Bb%3Dc` not `a%2Bb=c`).

### 62. Key-Encoding Must Cover `/` and `+` (H5)

**Decision:** `ContextPath.encodeKeyValue` adds `'/'=>%2F` and `'+'=>%2B` (alongside existing `' ''`, `& %26`, `? %3F`, `# %23`, `% %25`, `space %20`).

**Reason:** `People('a/b')` where `/` is a path separator split the URL; `a+b` is ambiguous (`+` decoded as space on some stacks). Both are legal inside CSDL `String` key values (e.g. file paths, tokens). Verified by `ContextPathEncodeKeyValueSlashPlusTest` (4, expects `%2F`/`%2B`).

### 63. Batch URL Resolution Handles Leading Slash and Case-Insensitive Scheme (H6)

**Decision:** `BatchRequest.resolveOperationUrl` now does `url = baseUrl + (url.startsWith("/") ? "" : "/") + url` (was unconditional `baseUrl + "/" + url`) and tests `url.regionMatches(true,0,"http",0,4)` instead of `startsWith("http")`.

**Reason:** `ContextPath.toRelativeUrl()` returns `People('scott')` (no leading `/`), but hand-written `BatchOperation.get("/People('scott')")` and any `"/"`-prefixed path produced `https://svc//People` (strict services reject double-slash). Unconditional `"/"` also broke when `baseUrl` was already trimmed but `url` had leading slash. Case-insensitive `http` avoids `HTTP://` false-positive relative resolution. Verified by `BatchRequestDoubleSlashTest` (3, leading-slash body now `…/People` not `…//People`).

### 64. Path Traversal Guard on Generated Package Names (H7)

**Decision:** `Generator.validatePackage` rejects any package containing `/`, `\`, `:` or `.` edge cases (`..`, empty segment, leading/trailing `.`) and requires each dot-segment to be a valid Java identifier (`isJavaIdentifierStart/Part`). `writeCode` additionally normalizes the target path and asserts `target.startsWith(outputDir)` after `toAbsolutePath().normalize()` so any `../../evil` or absolute `/tmp/evil` that slipped through cannot escape the output directory.

**Reason:** `Generator.writeCode` did `outputDir.resolve(packageDir)` with no validation. A hostile `basePackage=../../evil` or `schemaPackages` value `com/test/../evil` wrote outside `target`. Validated early in `generate()` for `defaultBasePackage` and each resolved `basePackage`, plus defense-in-depth in `writeCode`. Verified by `GeneratorPathTraversalTest` (3, hostile `../../evil`, slash, absolute).

### 65. Marker Hash Folds Header Values, Not Just Names (H8)

**Decision:** `GenerateMojo.computeMarkerHash` appends `header=<name>=<value>` (was `header=<name>` only), so rotating an `Authorization: Bearer <token>` invalidates the marker and forces regeneration.

**Reason:** Only hashing the header name kept the hash stable when a secret rotated, so the second build incorrectly reused stale generated sources. Verified by `GenerateMojoMarkerValueTest` (1 failing → pass: `token1` vs `token2` hashes differ).

### 66. Only 301/302/303/307/308 Are Redirects (H9)

**Decision:** `GenerateMojo.downloadMetadata` treats only `301,302,303,307,308` as redirects (was `300–399`). Other 3xx such as `304 Not Modified` fall through to `status != 200` → `Failed to download metadata: HTTP 304`.

**Reason:** `304` has no `Location` header and is not a redirect; the old `>=300 && <400` branch threw `Redirect without Location: HTTP 304`, masking a legitimate non-200 failure. Verified by `GenerateMojoRedirectTest` (304 now `Failed...`, 302 still follows).

### 67. `toPackageName` Sanitizes Leading-Digit Segments (H10)

**Decision:** `Names.toPackageName` lowercases with `Locale.ROOT`, maps `.`/`-` to `_`, maps any non-identifier char to `_`, and prefixes `_` when the result is empty or does not start with a valid Java identifier start. `3D.Model` → `_3d_model` (was illegal `3d_model`).

**Reason:** `3d_model` starts with a digit → `package 3d_model;` is illegal. The fix is component-safe for the flattened package form while preserving `Com.Example.Model` → `com_example_model`. Verified by `NamesToPackageNameTest` (leading-digit now valid, hyphen still sanitized).

### 68. Order-Independent Simple-Name Resolution (H11)

**Decision:** `Names.buildTypeKindMap` registers a simple name only when it maps to a single `TypeKind` across all schemas; colliding names (e.g., `Address` as `ENTITY` in one schema and `COMPLEX` in another) are left absent so resolution requires the qualified `NS.Address` form. `AbstractTypeGenerator.resolveTypeDefinition` does the same for typedef underlying types — simple `Length` with conflicting `Int32` vs `Double` stays unregistered, so unqualified `Length` is not order-dependent.

**Reason:** `putIfAbsent(e.name(), kind)` made unqualified resolution first-wins. With two schemas sharing a simple name but different kinds, the first kind in iteration order won, yielding nondeterministic packages/imports. Collecting qualified entries first and only promoting unambiguous simple names makes `mapAB.get("Address") == mapBA.get("Address")` regardless of schema order. Verified by `TypeKindCacheFirstWinsTest` (2, simple lookup now order-independent; TypeDefinition test drives real `AbstractTypeGenerator` subclass).

### 69. Atomic Interceptor Chain Construction (H12)

**Decision:** `EntityOperations.buildTransportChain` wraps the `get`→build→`put` compound in `synchronized (CHAIN_CACHE)` (was unsynchronized `get` then `put` on a `synchronizedMap(WeakHashMap)`).

**Reason:** `Collections.synchronizedMap` only synchronizes per-method, so two threads racing on the same `Context` could each build a distinct wrapper chain. The `synchronized` block makes construction atomic while retaining weak keys (contexts don't get pinned). Zero-interceptor fast path still returns `real` without touching the cache. Verified by `EntityOperationsChainCacheTest` (concurrent 10-thread barrier now returns single instance).

### 70. ReferentialConstraint PropertyRef Requires Name (M1)

**Decision:** `StaxCsdlParser.parseReferentialConstraint` now uses `requireAttr(el, "Name", "PropertyRef in ReferentialConstraint")` for `<PropertyRef>` inside `<Principal>/<Dependent>` (was `getAttr` → null).

**Reason:** Missing `Name` produced `ReferentialConstraintModel(null, ...)` → NPE downstream, not a clear parse error. Verified by `StaxCsdlParserMediumTest.m1_propertyRefMissingNameThrows` (expects IllegalArgumentException mentioning Name).

### 71. Unqualified Container Extends Must Be Unique (M2)

**Decision:** `StaxCsdlParser.mergeContainer` collects all `byQualifiedName` candidates with matching simple name; if exactly one, uses it; if >1, throws `IllegalArgumentException` "Ambiguous unqualified EntityContainer Extends ... use qualified name" (was break on first HashMap hit, nondeterministic).

**Reason:** `HashMap.values()` iteration order is nondeterministic; two containers named `SharedContainer` in different namespaces made `Extends="SharedContainer"` pick randomly. Verified by `StaxCsdlParserMediumTest.m2_unqualifiedExtendsAmbiguousThrows` (expects exception) and `m2_qualifiedExtendsWorks`.

### 72. sanitizeClassName Maps Hostile Chars to '_' (M3)

**Decision:** `Names.sanitizeClassName` now maps any non-`isJavaIdentifierPart` char to `'_'` (was only `'.'`/`'/'` → `'_'`, others dropped). `"A-B"` → `"A_B"` (was `"AB"` colliding with `"AB"`).

**Reason:** Dropping `'-'` made `A-B` and `AB` collide to same `AB.java` → duplicate detection misleading. Verified by `NamesMediumTest` (A-B → A_B, AB → AB).

### 73. Typedef-of-Complex Navigation Skipped (M4)

**Decision:** `RequestGenerator.isComplexTypeNav` unwraps `Collection(...)`, then `resolveTypeDefinition(unwrapped, schema)`, then `resolveTypeKind` (was direct `resolveTypeKind` → UNKNOWN for typedef).

**Reason:** `TypeDefinition MyAddr → NS.Shared.Address` (complex) was considered non-complex → generated `MyAddrEntityRequest` (no such class) → uncompilable. Verified by `RequestGeneratorMediumTest` (MyAddr and Collection(MyAddr) navs skipped).

### 74. Enum Member Collision Deduped (M5)

**Decision:** `EnumGenerator.generate` tracks `usedNames` and `usedCount`; colliding sanitized constants (`A-B` → `A_B` vs verbatim `A_B`) get suffix `A_B_2`, `A_B_3` (was duplicate `A_B(0), A_B(1)`).

**Reason:** Two CSDL members `A-B` and `A_B` both sanitize to `A_B` → `enum E { A_B(0), A_B(1) }` compile error. `BY_NAME` map uses deduped constants. Verified by `EnumGeneratorMediumTest`.

### 75. DateTimeProperty Nano Zero-Padded (M6)

**Decision:** `DateTimeProperty.formatTime` now pads nano with `String.format("%09d", nano)` (was `String.valueOf(nano)`).

**Reason:** `LocalTime.of(10,15,30,1)` → `10:15:30.1` (should be `10:15:30.000000001`); `1_000_000` → `10:15:30.1000000` (should be `.001000000`). Verified by `DateTimePropertyMediumTest` (4 checks).

### 76. NumberExpression div vs divby Uses Property Type (M7)

**Decision:** `NumberProperty` now stores `edmType` (e.g., `Edm.Double`) and overrides `divide` to use `divby` when property is floating (`Edm.Double/Single/Decimal`) or value is floating (`Double/Float/BigDecimal`); `Edm.Int*` only uses `div` for integer values. `NumberExpression` fallback remains value-based. Generators now emit `new NumberProperty<>("Price", Person.class, "Edm.Double")`.

**Reason:** Dividing `Edm.Double` property by `Integer 2` should be `divby` (floating), but heuristic used only `value instanceof Integer` → `div`. Verified by `NumberExpressionMediumTest` (Double+Integer → divby, Int+Int → div).

### 77. Unique Lambda Aliases for Nested any/all (M8)

**Decision:** `CollectionProperty.lambda` uses `ThreadLocal<Integer> LAMBDA_DEPTH`; alias is `baseAlias` (from `FilterableElement.prefix()` or `"x"`) for depth 0, else `baseAlias + depth` (`x1`, `d1`). Predicate result's prefix `baseAlias/` is remapped to `alias/` for unique aliases.

**Reason:** `Tags/any(x: x/Name eq 'a' and x/Names/any(x: x/Value eq 'b'))` inner `x` shadows outer `x`. Needs `x` and `x1` (or `d` and `d1`). Verified by `CollectionPropertyMediumTest` (nested any contains `x1:`) and `CollectionPropertyTest.l13LambdaAliasFollowsFilterableElementPrefix` (custom prefix `d` preserved).

### 78. BatchOperation Rejects Encoded CRLF/NUL (M9)

**Decision:** `BatchOperation.rejectLineBreaks` now also scans for `%HH` where decoded byte is `\r`, `\n`, or `\0` (`%0D`, `%0A`, `%00` case-insensitive, was raw checks only).

**Reason:** `%0D%0A` decodes to CRLF on server → header injection via encoded URL. Verified by `BatchOperationMediumTest` (5 checks, 3 encoded rejections).

### 79. JdkHttpTransport Dedupes OData Headers Case-Insensitively (M10)

**Decision:** `JdkHttpTransport.buildJdkRequest` checks `hasMaxVersion`/`hasVersion` via `equalsIgnoreCase` before adding defaults `OData-MaxVersion: 4.01` / `OData-Version: 4.0` (was unconditional → duplicate `4.01, 4.0`).

**Reason:** Caller-supplied `OData-MaxVersion: 4.0` produced duplicate header values. Verified by `JdkHttpTransportMediumTest` (3 checks).

### 80. JacksonSerializer Patch Includes Empty Collections (M11)

**Decision:** `JacksonSerializer` adds `MAPPER_FOR_PATCH` (baseMapper without `NON_EMPTY` collection override) and `serialize(value, type, includeFields)` now uses it for `valueToTree`/`writeValueAsBytes` (was `MAPPER` with `NON_EMPTY` → empty `List.of()` omitted even when explicitly requested).

**Reason:** Clearing `tags` with `[]` was impossible: `NON_EMPTY` hid `[]` even when field was in `changedFields`. Patch path must include empty when explicitly requested. Verified by `JacksonSerializerMediumTest`.

### 81. GenerateMojo Explicit Temp File Cleanup (M12)

**Decision:** `GenerateMojo.downloadMetadata` wraps `Files.copy` in try-catch with `Files.deleteIfExists(tempFile)` on failure; `execute` deletes temp file after `parseMetadata` when `metadataUrl` was used (was `deleteOnExit` only → `/tmp` accumulation in daemon builds).

**Reason:** `deleteOnExit` never runs in long-lived daemons/`-T` builds. Verified by `GenerateMojoMediumTest` (method contains explicit delete handling).

### 82. Generator Cleans Stale Files Across Calls (M13)

**Decision:** `Generator.generate` saves `previousFiles = new HashSet<>(written.keySet())` before `written.clear()`, then after generation deletes any `old ∈ previousFiles \ written` via `Files.deleteIfExists` (was `written.clear()` only, old `Foo.java` remained after rename `Foo`→`Bar`).

**Reason:** Incremental builds via same `Generator` instance left stale `.java` on classpath after type rename. `GenerateMojo` handles cross-process staleness via marker manifest; `Generator` now handles in-process. Verified by `GeneratorMediumTest`.

### 83. Case-Insensitive HTTP URL Check (M14)

**Decision:** `EntityOperations` adds `isAbsoluteHttpUrl(String)` via `regionMatches(true,0,"http",0,4)` and uses it in `addRef`/`removeRef` (was `startsWith("http")` case-sensitive → `HTTP://` treated as relative).

**Reason:** `HTTP://` should be considered absolute; case-sensitive check prepended `baseUrl` → `https://service/HTTP://...`. Verified by `EntityOperationsMediumTest` (4 checks).

### 84. Reserved Words Cover Java 17+ Contextual Keywords (L1)

**Decision:** `Names.isReservedWord` now includes `"when"` and `"non-sealed"` (was missing). `"when"` is a contextual keyword for pattern matching (Java 17+), `"non-sealed"` for sealed classes. Enum member or property named `when` is now sanitized, not kept verbatim as an invalid identifier.

**Reason:** `isReservedWord` listed up to `transitive` (Java 17) but omitted `when` (added in 17) and `non-sealed` (hyphenated sealed variant). A CSDL enum member `when` stayed verbatim `when` → invalid or shadowed. Verified by `NamesLowTest` (`when` now reserved, `non-sealed` reserved).

### 85. Acronym-Aware Constant Splitting (L2)

**Decision:** `Names.toConstantName` now inserts `'_'` when current char is upper and next char is lower while previous is upper (acronym boundary). `XMLHttp` → `XML_HTTP` (was `XMLHTTP`); `XMLHttpRequest` → `XML_HTTP_REQUEST`.

**Reason:** Previous check only `prev is lower` missed `XML|Http` where `H` is upper, prev `L` upper, next `t` lower → boundary. Verified by `NamesLowTest` (XMLHttp cases).

### 86. Malformed Percent-Encoding Tolerated (L3)

**Decision:** `ContextPath.decodePercent` leaves malformed `%ZZ` verbatim (was already) and `encodeQueryParam` correctly encodes literal `'%'` as `%25` only for valid contexts; `fromNextLink` round-trips without double-encoding valid `%20` and without throwing for malformed. No code change beyond ensuring `decodePercent` handles incomplete `%` at end gracefully (already did).

**Reason:** `%ZZ` is not a valid `%HH` — leaving verbatim then re-encoding as `%25ZZ` is actually correct single-encoding of a literal `'%'`. The low-severity note is documented, and `LowIssuesTest.l3` verifies malformed is handled without exception. No double-encode beyond expected.

### 87. Unknown Edm Type Strings Are Quoted (L4)

**Decision:** `ContextPath.formatTypedValue` default case now checks `value instanceof String s` → `"'" + encodeKeyValue(s) + "'"` before falling through to `Enum` or `String.valueOf`. Unknown `Edm.UnknownType` with `"test"` now renders `'test'` (was bare `test`).

**Reason:** String key literals must be quoted per OData ABNF regardless of whether the Edm type is known; bare `test` is not a valid key literal. Verified by `LowIssuesTest.l4`.

### 88. Boundary Pattern Allows Spaces Around '=' (L5)

**Decision:** `MultipartHelper.BOUNDARY_PATTERN` now `boundary\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))` (was `boundary=`). `boundary = "myBoundary"` with spaces now parses (was missed → changeset not decoded).

**Reason:** RFC 2046 allows `boundary = "abc"` with optional whitespace. Real services and tests may emit spaced form. Verified by `LowIssuesTest.l5`.

### 89. Missing Closing Boundary Detected (L6)

**Decision:** `MultipartHelper.decodeParts` tracks `foundClosing` and throws `ODataException` if body ends at delimiter without trailing `"--"` (was silently returning partial results).

**Reason:** A batch response that ends at `--boundary` without `--` is malformed truncated; previous code exited loop without error, returning partial results. Verified by `LowIssuesTest.l6`.

### 90. CollectionPage Defensive Copy (L7)

**Decision:** `CollectionPage` now `Collections.unmodifiableList(new ArrayList<>(currentPage))` (was `unmodifiableList(currentPage)` without copy).

**Reason:** Caller mutation of the original list (e.g., `list.add("c")` after `new CollectionPage(list, null)`) was visible through `currentPage()` because it wrapped the same list instance. Copy isolates. Verified by `LowIssuesTest.l7`.

### 91. BatchResponse Null-Safe Content-ID Lookup (L8)

**Decision:** `BatchResponse.getByContentId` now `Objects.equals(contentId, result.contentId())` (was `contentId.equals(...)` → NPE when `contentId == null`).

**Reason:** `BatchResult` without Content-ID has `null` contentId; lookup with `null` should return the matching null entry or null, not NPE. Verified by `LowIssuesTest.l8`.

### 92. DynamicPropertyConverter Reuses Shared Mapper (L9)

**Decision:** `DynamicPropertyConverter` now `private static final ObjectMapper MAPPER = JacksonSerializer.sharedMapper()` (was `new ObjectMapper()` with duplicate `Jdk8Module`/`JavaTimeModule` config). `JacksonSerializer` exposes `sharedMapper()` package-private.

**Reason:** Duplicate `ObjectMapper` with identical `Jdk8Module`+`JavaTimeModule` config was wasteful (two instances, same modules). Reusing the shared mapper saves memory and ensures consistent date handling. Verified by `LowIssuesTest.l9` (`assertSame`).

### 93. Review-of-Review Hardening (PR #8 follow-ups)
**Decision:** Six fixes from a critique of the critique-fix PR: (a) `CollectionProperty.rebindAlias` replaces the blind `expr.replace(baseAlias + "/", alias + "/")` with a quote-aware, word-boundary-bounded rewrite — quoted literals like `'x/value'` and longer paths like `Max/` survive nested-lambda alias rebinding verbatim. (b) `EntityGenerator/ComplexTypeGenerator.findBaseGlobal` throw on ambiguous unqualified `BaseType` simple-name matches (>1 across schemas) instead of first-wins — one ambiguity policy everywhere: containers throw, type-kind maps leave absent, inheritance throws. (c) `StaxCsdlParser` extracts the shared `applyAliasMap` rewrite used by both `resolveTypeRef` (parse time) and `fixAlias` (post-pass), and rejects duplicate `Alias` declarations mapping to different namespaces (spec violation that previously resolved order-dependently via `putIfAbsent`). (d) Typedef-of-entity navs resolve through the TypeDefinition chain in `AbstractTypeGenerator.navJavaType` and `RequestGenerator` imports/nav methods — previously they emitted references to a nonexistent `<Typedef>EntityRequest`. (e) `GenerateMojo.execute` deletes downloaded metadata temp files in a `finally` covering ALL exit paths — the up-to-date early return previously leaked them (the exact daemon-build leak M12 claimed to fix). (f) Dead code removed (`NumberExpression.getExpression/formatValueStatic`, unreachable `".."` package-segment check), M13 stale-file delete failures now `log.warn` instead of swallowing.

**Reason:** The original fixes were correct in their happy paths but had sharp edges visible only under adversarial review. The alias rebinding corrupted data inside string literals; first-wins base resolution reintroduced the nondeterminism H11 removed elsewhere; the temp-file cleanup missed the most common build path (incremental up-to-date). Verified by `CollectionPropertyMediumTest` (+2 corruption cases), `CrossSchemaInheritanceFinalTest.unqualifiedAmbiguousBaseFailsLoudly`, `StaxCsdlParserMediumTest` (+2 duplicate-alias), `RequestGeneratorMediumTest.m4_typedefOfEntityNavResolvesUnderlyingType`, `EntityGeneratorNumberEdmTypeTest` (M7 generator-side emission), `BatchRequestDoubleSlashTest` trailing×leading-slash combo pin, and behavioral `GenerateMojoMediumTest` (replaces source-grep tests that read an absolute path off one developer machine).

### 94. FunctionImport & ActionImport Generation (Request-Object Style)
**Decision:** Unbound container imports generate a final `<Name>FunctionRequest` / `<Name>ActionRequest` in the `.operation` package; the container emits one typed accessor per import. Functions invoke via GET with parameters embedded in the URL fragment (`Name(p1=v1,p2=v2)` — type-driven literal formatting reused from key predicates via the new `OperationPath` helper); actions invoke via POST with a JSON parameter body (`EntityOperations.buildActionBody`). Nullable return types wrap in `Optional<T>`; collection results return `List<T>` (empty on absent, never null); void actions emit only `execute()`. Result-kind mapping reuses lessons 93/136 discipline: unwrap `Collection(...)` first, then typedefs. Cross-schema resolution keys output packages to the operation's OWNING schema namespace; ambiguous simple-name references and bound function AND action references fail at generation with messages naming the import (one ambiguity policy everywhere). Function parameters validate as Edm-primitive-or-enum at generation time — collection-typed function parameters are rejected BEFORE the element check (lesson 170's documented behavior, now actually enforced) — while action parameters may be structured or collections (JSON body handles them; collections map to `List<element>`). Wire-shape variance for primitive results is handled deterministically: the `"value"` envelope is unwrapped whenever it is the only non-`@`-control property (spec-conformant responses carry `@odata.context`), bare JSON literals pass through, element bytes route through the configured Serializer.

**Amendment (decision 94, review round 6):** Six fixes for shapes TripPin doesn't exercise. (a) **Parameter-type imports**: structured/enum parameters (and collection elements) contribute imports in BOTH the request class and the container accessor — `collectParameterImports` mirrors `addPropertyImports`; previously `Color c`/`Address addr` generated without imports (uncompilable). (b) **Value-wrapped results**: complex/enum single results route to `EntityOperations.invokeComplexSync/Async` which unwrap the `{"value": {...}}` envelope (entities stay on `invokeSync` — they arrive at the JSON root); previously the envelope deserialized as the complex itself, silently yielding all-null fields. (c) **Polymorphic results**: entity singles, complex singles, and object collections pass `SchemaInfo.INSTANCE` (decision 46 parity) via new SchemaInfo overloads. (d) **Wire names**: function URL pairs and action JSON keys use the CSDL parameter name (`class=`, `First-Name=`, `new-name`), not the sanitized Java field name. (e) **Collection function params** fail generation naming import+parameter; **collection action params** map to `List<E>` with element imports (previously a garbage `String_` type). (f) `objectCollectionMethods` now imports `CollectionPage` (was referenced unimported — every object-collection return was uncompilable). Additionally generated enums implement the new runtime `ODataEnumValue` interface (`wireName()`), and URL enum literals (`ContextPath.formatTypedValue`, `EnumProperty`) render the CSDL member name instead of the sanitized Java constant, with a `name()` fallback for pre-interface enums.

**Amendment 2 (decision 94, unbound function overloads):** OData identifies an unbound function overload by its PARAMETER NAMES — one FunctionImport exposes every same-name unbound overload, and the invocation URL's parameter names select one (`IsSiteAdmin(username='x')` vs `IsSiteAdmin(userId='y')`). Resolution previously aborted any same-name match with "Ambiguous operation reference". Now: (a) bound same-name siblings are filtered out BEFORE ambiguity — an unbound import target never competes with a bound overload (same for action imports); (b) same-namespace same-name unbound functions resolve as an OVERLOAD SET — each overload generates its own `<Import>By<Params>FunctionRequest` class and container accessor (`isSiteAdminByUsername`, multi-param `ByAAndB`; a lone overload keeps the historical unsuffixed names); (c) hostile parameter names folding onto one suffix dedupe deterministically `_2`/`_3` (decision 54 policy); (d) overloads with IDENTICAL parameter-name lists are invalid CSDL (indistinguishable in a URL) and fail loudly naming the import; (e) same-name UNBOUND actions also fail loudly — actions cannot be overloaded by parameter names; (f) cross-namespace simple-name ambiguity still throws (one ambiguity policy everywhere). Overloaded accessors register their per-overload names (not the import name) in the container collision registry. No runtime change: `OperationPath` already embeds each overload's own parameter names in the URL.

**Amendment 3 (decision 94, collection function parameters):** Collection-typed function parameters (`Collection(Edm.String)`, `Collection(NS.Color)`, …) no longer fail generation — they pass via OData **parameter aliases** (URL Conventions §5.1.1: collection values cannot be inline path literals; the spec's required form is `Name(param=@p)?@p=[...]`). The generated constructor takes `List<element>` (boxed); the segment pair references the alias (`__pairs.add("ids=@p0")`) and the alias value rides a query option appended after the segment via the new runtime `OperationPath.collectionParameter(List, elementEdmType)` — elements formatted by the same `parameter()` rules (quoted strings, bare numerics, qualified enum wire names), `[]` for an empty list, null list/elements rejected like scalar null. Nullable collection params omit BOTH the pair and the alias when null; required ones keep the non-null guard. Scalars stay inline, and output for collection-free functions is byte-identical. Aliases are numbered `@p0, @p1, …` per collection parameter (positional — hostile CSDL names like `new-name` are not legal alias identifiers). Alias queries chain through a local `ContextPath __path` because `contextPath` is a blank final (single-assignment). Structured ELEMENTS (`Collection(NS.Address)`) still fail generation naming import+parameter — no alias-literal form exists for them.

**Amendment 4 (decision 94, structured function parameters):** Single structured parameters (`NS.Address`, `NS.Person`) and structured collection elements (`Collection(NS.Address)`) no longer fail generation — they ride the SAME parameter-alias mechanism as primitive collections, with the alias value being the SERIALIZED JSON of the instance (URL Conventions §5.1.1: complex parameter values MUST use parameter aliases; lesson 177's escape-hatch rule applied to the last rejected shape). The generated constructor takes the complex/entity type (`Address addr`) or `List<Address>`; the segment pair references the alias (`__pairs.add("addr=@p0")`) and the alias value rides the query option via the new runtime `EntityOperations.jsonParameter(Object)` — shared `COLLECTION_MAPPER`, exactly like `buildActionBody` (plain JSON, no entity-specific serialization policy; `OperationPath` stays Context-free). Nullable structured params omit BOTH pair and alias when null; required ones keep the non-null guard. Aliases share the positional `@p0, @p1, …` counter with collection params; scalars stay inline. Validation now accepts element kinds primitive/enum/COMPLEX/ENTITY and rejects only unresolvable types (message updated). Bound functions inherit all of it through the shared `validateFunctionParameters` + `constructorBody`. Verified by `OperationImportCollectionParamTest` (+5 structured), `OperationImportValidationTest` (negative rewritten to unresolvable types), `OperationGeneratorBoundTest.boundFunctionWithStructuredParameterRidesJsonAlias`, `EntityOperationsJsonParameterTest` 3, and `OperationImportsHostileParamsCompilationTest` extended with `NearAddresses(Address, Address)` + `VisitAll(Collection(NS.Address))` — the full client compiles.

**Reason:** Request-object style matches the repo's model/request-layer split (decision 10) and forces explicit `execute()` for mutating actions. Reusing key-predicate formatting avoids a parallel literal-formatting codebase. Verified by `OperationPathTest` 11, `EntityOperationsInvokeTest` 13 (incl. buildActionBody), `EntityOperationsInvokeEnvelopeTest` 13 (@odata.context-tolerant primitive unwrap, value-wrapped complex/enum results incl. polymorphic and async parity), `EnumPropertyTest` 9, `OperationGeneratorTest` 15 (return-kind matrix + parameter-shape matrix on synthetic metadata), `ContainerGeneratorImportTest` 5 (accessors, collision registry join, parameter-type imports), `OperationImportValidationTest` 5 (unknown/bound-function/bound-action/ambiguous/unresolvable-type negatives), `GeneratorOperationFilesTest` 1, `OperationImportsCompilationTest` 1 (FULL TripPin client incl. operations compiles against runtime), `OperationImportsHostileParamsCompilationTest` 1 (enum/structured/collection/hostile-name parameters generate AND compile — the referee test for the shapes TripPin lacks), `OperationImportOverloadTest` 10 + `OperationImportsOverloadCompilationTest` 1 (same-name/different-param-name overloads, identical-param-name rejection, bound-sibling filtering, per-overload classes/accessors/files — the referee for the overload shape TripPin lacks), `EnumGeneratorTest` 4 + `EnumGeneratorMediumTest`/`HostileNamesCompilationTest`/`CrossNamespaceCompilationTest`/`EnumJsonDeserializationTest` updated for the wire-name constructor, live `TripPinOperationImportTest.getNearestAirportRoundTrip` (read-only round-trip; destructive ResetDataSource deliberately never invoked).

### 95. Keyed Accessor API — Container + Nav Overloads Replace the `byID` Family (Breaking, Option A)
**Decision:** Every keyed entity set gains a keyed container overload (`client.people("russellwhyte")`, `client.orderDetails(orderId, productId)`) returning the entity request directly; collection navigations to keyed entity types gain keyed nav overloads on entity requests (`person.trips(2)` renders `People('x')/Trips(2)`). The `byID`/`byKey` accessor family on collection requests is REMOVED (breaking, pre-1.0 — no version released). Keyless entity sets/keyless nav targets emit no overloads; composite and inherited keys surface as multi-parameter overloads via the shared `RequestGenerator.keyParamSpecs` (base-chain walk, typedef-resolved Edm types, boxed `keyJavaType` contract). Key literals remain type-driven (decision 52). Emission reuses the existing accessor-name collision registry (overloads share the set/nav name, so no new names).

**Amendment (decision 95, entity-request read options):** Keyed accessors return the ENTITY request, which originally carried no read-shaping options — `expand()`/`select()` existed only on collection requests, so `Containers(id)?$expand=Folders($expand=Abc)` was unreachable even though the docs' "Expand on Entity Requests" section documented it (docs drift, lesson 94's class of bug). Entity requests now emit `select(PropertyExpression<? super E, ?>...)`, `expand(NavProperty<? super E, ?>...)`, and `expand(NavQuery<? super E, ?>...)` — all `@SafeVarargs public final`, mirroring the collection-request copy-snapshot chaining — plus a private `buildContext()` applying `$select`/`$expand`, and `get()` routes through it. Filter/top/skip/orderby stay collection-only (not valid on a single-entity GET). Verified by `RequestGeneratorEntityQueryOptionsTest` 3; reactor 768.

**Reason:** The two-step chain (`client.people().personByUserName("x")`) mirrored URL structure but read poorly at call sites, and bound operations would have compounded it (`...().trips().tripByID(2).shareTrip(...)`). With overloads at BOTH levels, every keyed access has a shorter spelling and the `byID` family is fully redundant — carrying both invites the docs drift fixed once already (lesson 94). Verified by `ContainerGeneratorKeyedOverloadsTest` 4, `RequestGeneratorKeyedNavTest` 6, `RequestGeneratorKeyTest` 3 (composite/inherited via overloads + byID deletion pinned), `TypedKeyLiteralTest` rewritten, full reactor sweep of generated-client tests + README + 10 docs pages + release-notes migration notes.

### 96. Bound Operations on Entity Requests (Request-Object Style)
**Decision:** Operations whose BINDING parameter (first `Parameter`) resolves to an entity type surface on that entity's request ecosystem: `OperationGenerator.boundOperationsFor(entityType)` yields per-op specs (invocation params EXCLUDE the binding param; class name `<Entity><Op>FunctionRequest|ActionRequest` in the owning schema's `.operation` package; accessor name `toJavaFieldName(opName)` + overload suffix). The generated request class takes `(Context, ContextPath basePath, params...)` — the entity request's keyed `contextPath` flows in as `basePath` — and appends the cast segment (qualified binding type, when the op is bound to an ANCESTOR: `.../Flight('x')/N.NS.PlanItem/Op`) then the operation segment. Ops bound to a SUBTYPE are not visible on ancestor requests. Same-name bound functions form overload sets (suffixes from non-binding params, `_2/_3` dedupe); identical full parameter-name lists and duplicate same-name actions fail loudly. Result-kind matrix, param literals/aliases, JSON bodies, `Optional<T>`/`List<T>` rules are reused verbatim from imports via the extracted `emitExecuteMethods` + parameterized `constructorBody(pathBaseExpr, castSegment, opSegmentName, ...)`. Collection-bound operations (`Collection(NS.Document)` binding) are deferred. Bound ACTIONS are invoked live never — TripPin's only bound action (`ShareTrip`) mutates shared state; the live test covers the read-only bound function `GetFriendsTrips`.

**Reason:** Binding-param-as-URL-context is the spec's model; the keyed accessor API (decision 95) made the composition read naturally (`client.people("x").shareTrip("y", 1).execute()`). Cast segments make base-type ops usable from subtype requests without lying about the instance type. Verified by `OperationGeneratorBoundTest` (resolution, cast segments, overload sets, loud failures, request-class content), `RequestGeneratorBoundOpsTest` 2 (accessor embedding + Generator file wiring), `OperationImportsCompilationTest` (full TripPin client with bound ops compiles), live `TripPinOperationImportTest.boundFunctionRoundTripGetFriendsTrips` (read-only).

**Amendment (decision 96, overload identity = binding type + ordered parameter types):** The original bound-overload check identified overloads by PARAMETER NAMES ONLY (`bindingName + invocation names`) — legal metadata threw "Bound function 'X' has overloads with identical parameter names". Per ODATA-500 (accepted: overloads must differ by the ordered set of qualified parameter types) and ODATA-425 (bound actions overload by binding parameter; one bound action per binding type), the identity is now `bindingQualified | name:resolvedType | …` for functions, and bound actions with DISTINCT binding types generate with suffixes (same binding type still fails loudly). Unbound function imports got the same corrected identity (mirrored path, lesson 156c) — same names with different types now generate (`IsSiteAdminByX`/`IsSiteAdminByX_2`), names+types identical still fails. `overloadSuffix` returns `""` for empty invocation params so binding-only overload sets dedupe as bare/`_2` rather than `By`/`By_2`. Typedefs resolve to their underlying type in the identity (two overloads differing only by typedef-of-same-primitive would render identical URLs — stricter than spec, honest about what the URL renderer can express). Verified by `OperationGeneratorBoundTest` 14 (`sameNameDifferentTypeOverloadsGenerate`, `sameBindingDifferentTypesInheritedOverloadsGenerate` — the reported bug: Get bound to Base + Get bound to Derived both surface on the Derived request with cast/no-cast and `getByX`/`getByX_2`, lone overload on Base stays bare — `trulyIdenticalOverloadsStillFailLoudly`, `sameNameBoundActionsOnDifferentBindingTypesGenerate`) and `OperationImportOverloadTest` 11 (`overloadsIdenticalInParameterNamesButNotTypesGenerate`, `overloadsIdenticalInNamesAndTypesStillFailLoudly`); reactor 764.

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

**Live-service tests are excluded by default.** The seven classes that hit `services.odata.org` are tagged `@Tag("live-service")`; surefire excludes the tag, so plain `mvn test` runs only the ~396 offline tests (hermetic — works offline/behind proxies). Run everything (including live TripPin/Northwind/OData Demo) with `mvn test -Plive-tests`.

- **Parser tests:** Parse TripPin + Northwind + OData Demo metadata XML, verify model correctness; v3/non-CSDL documents fail loudly (`StaxCsdlParserTest`, 49 tests)
- **Generator integration tests:** Generate TripPin client, verify file structure and code content (`GeneratorIntegrationTest`, 1 test)
- **Generator compilation tests:** Generate + compile TripPin client (with the `Event`/`Flight`/`PlanItem` inheritance hierarchy) against runtime JARs (`GeneratorCompilationTest`, 1 test)
- **Cross-namespace compilation tests:** Generate + compile OData Demo client (cross-namespace types) against runtime JARs (`CrossNamespaceCompilationTest`, 1 test)
- **Entity generator unit tests:** Composite-key `getKey()`, collection getter emission, member-name reservations (`EntityGeneratorCompositeKeyTest` 1, `EntityGeneratorCollectionGetterTest` 2, `EntityGeneratorMemberNameTest` 6)
- **Enum generator unit tests:** Hostile member-name sanitization, `fromFlags()` for `IsFlags` enums, valid names kept verbatim (`EnumGeneratorTest` 3)
- **Complex type generator unit tests:** Complex-type inheritance — `EventLocation extends Location`, `with*` + Builder generation (`ComplexTypeGeneratorInheritanceTest` 3, `ComplexTypeGeneratorCollectionEnumTest` 4)
- **Entity generator abstract-type unit tests:** Abstract entity generation — abstract base declares no `with*()`, concrete subtype extends it + has `with*()`, and the pair compiles (`EntityGeneratorAbstractTest` 3)
- **Request generator tests:** Media-stream, `$apply` expression, composite-key, narrowed query bounds, pagination helpers, entity creation/replace (`RequestGeneratorMediaTest` 3, `RequestGeneratorApplyTest` 3, `RequestGeneratorKeyTest` 2, `RequestGeneratorNarrowQueryTest` 5, `RequestGeneratorPaginationTest` 4, `RequestGeneratorCreateTest` 5)
- **Open-type generator tests:** Generated entity/complex-type captures undeclared JSON fields into `unmappedFields`; open subtype of non-open base captures via inherited root map; non-open complex type doesn't reference unmappedFields (`OpenTypeGeneratorTest` 6)
- **Runtime tests:** 248 (live TripPin & Northwind integration, query expression, context path, batch, exceptions, transport, **media `$value` stream/put via mock transport** — `EntityOperationsMediaTest` 3, **`$apply` builder** — `ApplyExpressionTest` 8, **collection parse** — `EntityOperationsCollectionParseTest` 7, **batch changeset encode/decode/round-trip** — `MultipartHelperTest` 17, `BatchRequestTest` 11, **typed collection lambdas** — `CollectionPropertyTypedLambdaTest` 2, **count endpoint** — `EntityOperationsCountTest` 5, **ContextPath next-link/count-segment** — `ContextPathTest` additions 12, **entity create/put with mock transport** — `EntityOperationsCreatePutTest` 5, **interceptor chain + stream hook** — `EntityOperationsInterceptorChainTest` 5, **structured OData error in exceptions** — `ODataExceptionTest` 14, **null-safe property comparisons** — `StringPropertyTest`/`NumberExpressionTest`/`DateTimePropertyTest`/`BooleanPropertyTest`/`EnumPropertyTest` additions 11, **query API completeness** — `StringPropertyTest`/`DateTimePropertyTest`/`NumberExpressionTest`/`CollectionPropertyTest` additions 17)
- **Generated client tests (92):** `NorthwindGeneratedClientTest` (24), `ODataDemoGeneratedClientTest` (23, exercises `FeaturedProduct extends Product`, `Customer`/`Employee extends Person`, `Event`/`PlanItem`), `TripPinGeneratedClientTest` (24, exercises `Flight`/`PublicTransportation`/`PlanItem` hierarchy, type-safe + nested `$expand` with materialized getters, `create()`/`createAndDeletePerson`), `TripPinInheritanceTest` (11, exercises generated **complex-type** inheritance `EventLocation`/`AirportLocation extends Location` + **entity** inheritance `Flight → PublicTransportation → PlanItem`: `instanceof`/polymorphic assignment, subtype `with*` copy-on-write preserving inherited fields, base `builder()` scoping, live `AirportLocation` deserialization), `ODataDemoMediaTest` (2, live media streams: `Advertisement` `HasStream` via `streamMedia()` at `.../Advertisements(id)/$value`, `PersonDetail.Photo` `Edm.Stream` named stream via `streamPhoto()` at `.../PersonDetails(id)/Photo`), `OpenTypeDynamicPropertyTest` (8, deserialization captures dynamic props into `unmappedFields`/`getDynamicProperty`, typed `getDynamicProperty(String, Class)` coercion to a POJO/number, round-trips on serialize, filters `@odata.*` control fields)
- **Generator unit tests (22 new):** `WithMethodCopyOnWriteTest` 5 (copy-on-write defensive copying of collections and unmappedFields), `NavReservedWordTest` 3 (nav getter/with-method sanitization for `class` and other Object-method collisions), `EntityGeneratorFilterableTest` 5 (typed Filterable inner class for `any`/`all` lambdas), `EntityGeneratorSimplifiedDeserializationTest` 5 (no `@JsonCreator`, no wide-entity switch, public no-args constructor + `@JsonProperty` setters), `ComplexTypeGeneratorSimplifiedDeserializationTest` 4 (same simplification for complex types)
- **Query type-safety tests:** `QueryTypeSafetyCompilationTest` 1 (negative compile test proving cross-entity `select`/`orderBy`/`expand` fails)
- **Review round-3 tests (~90 new, all TDD, findings H1-H10/M1-M28/L1-L33/carry-forwards):** parser — `StaxCsdlParserEnumMemberValueTest`, `StaxCsdlParserAliasAndValidationTest`, `StaxCsdlParserPolishTest`, `CarryForwardParserTest`; generators — `GeneratorSchemaInfoAggregationTest`, `AbstractHierarchyDeserializationTest` (compile **and deserialize** abstract hierarchies), `GeneratorDuplicateDetectionTest`, `RequestGeneratorInheritedMembersTest`, `RequestGeneratorCollectionComplexNavTest`, `GeneratorGuidPropertyTest`, `GeneratorPolishTest`, `TypedKeyLiteralTest`, `NamesPolishTest`, `EnumJsonDeserializationTest`; runtime — `EntityOperationsEmptyBodyTest`, `EntityOperationsPolymorphismTest`, `EntityOperationsCollectionParseTest` additions, `MultipartHelperHardeningTest`, `BatchRequestBoundaryTest`, `BatchRecordImmutabilityTest`, `GuidPropertyTest`, `TypedKeyFormatTest`, `CarryForwardFixesTest` (partial PATCH, chain caching, typed batch errors), `HttpRequestBuilderTest`, `JdkHttpTransportHeadersTest`, `DynamicPropertyConverterTest`; plugin — `GenerateMojoIncrementalTest` additions (auth headers, per-execution markers, stale-file cleanup, threadSafe, JSON-metadata rejection); round-4 — `HostileNamesCompilationTest` 2 (hostile-name constants/enum members generate **and compile**), `EnumJsonDeserializationTest` +1 (sanitized enum member wire-name round-trip), `GeneratorPolishTest` +3 (container collision, resolved enum literals, builder nav change tracking), `StaxCsdlParserPolishTest` +2 (duplicate-Key/PropertyRef validation)
- **Review round-5 tests (all TDD, findings H1-H2/M1-M11/L-tier — see REVIEW_ROUND5_PLAN.md):** generators — `RequestGeneratorFilterParenTest` 3 (chained `filter()` predicates parenthesized before the AND join), `RequestGeneratorEntityFinalTest` 1 (entity requests are `final`, parity with collection requests), `EntityGeneratorEtagHeaderOverrideTest` 2 (`applyETagFromResponse` override on root entities); runtime — `BatchResponseTypedViewTest` 4 (typed views preserve `contentId`), `ContextPathNextLinkDecodingTest` 3 (UTF-8 percent-decoding, case-insensitive scheme), `NavQueryValidationTest` 4 (`top/skip >= 0` parity with ApplyBuilder), `SyncAsyncErrorContractTest` 3 (interceptor stream errors ride the future; interrupt flag restored), `TypedStatusExceptionTest` 5 (typed 408/410/428), `EntityOperationsEtagHeaderTest` 4 (header-only ETag captured after GET, body annotation wins, flows into If-Match), `MultipartHttp2StatusLineTest` 2 (`HTTP/2 200` status lines decode), `Round5LowTierTest` 8 (addRef blank guard, `$search` control-char guard, `ceiling/floor/round`, spliterator SIZED); hygiene refactors without observable behavior (no red-green theater): BatchRequest resolves into a local copy instead of mutating entries, JdkHttpTransport client cache made per-instance, putMedia dead branch removed
- **Operation import tests (all TDD, decisions 94 + 94-amendment):** runtime — `EntityOperationsInvokeTest` 13 (function GET/entity result, action POST body incl. null-body 204, primitive unwrap both wire shapes, primitive collections, typed errors, interrupt restoration, async parity, `buildActionBody`), `EntityOperationsInvokeEnvelopeTest` 13 (spec-conformant envelopes: @odata.context-tolerant primitive unwrap, value-wrapped complex results with/without envelope, polymorphic entity/complex reads, async parity), `OperationPathTest` 11 (empty parens, comma join, string quoting/encoding, bare numerics/dates/guids, TimeOfDay seconds, duration, null guard, enum wire names); core — `OperationGeneratorTest` 15 (return-kind matrix + enum/structured/collection parameter imports, List mapping, CSDL wire names, invokeComplex/SchemaInfo wiring), `ContainerGeneratorImportTest` 5 (accessors + collision registry join + parameter-type imports), `OperationImportValidationTest` 5 (unknown/bound-function/bound-action/ambiguous/unresolvable-type negatives), `GeneratorOperationFilesTest` 1 (pipeline writes .operation files + container references), `OperationImportsCompilationTest` 1 (FULL TripPin client incl. operations compiles against runtime), `OperationImportsHostileParamsCompilationTest` 1 (enum/structured/collection/hostile-name parameters generate AND compile; extended with collection-alias shapes `ByTags`/`ByScores` and structured-alias shapes `NearAddresses`/`VisitAll`), `OperationImportOverloadTest` 11 (unbound function overloads: per-overload classes named `By<Params>`, lone-overload name stability, identical-names-different-types generate with `_2` dedupe, names+types identical rejection, bound-sibling filtering for functions AND actions, per-overload container accessors + collision registry, one file per overload), `OperationImportsOverloadCompilationTest` 1 (the overload referee: overloaded + bound-sibling metadata generates AND the full client compiles), `OperationImportCollectionParamTest` 11 (collection params ride parameter aliases: `List<element>` constructor, alias pair + `addQuery("@p0", collectionParameter(...))`, mixed scalar/collection, nullable omission, enum elements, container accessor; PLUS structured params ride JSON aliases: single complex ctor + `addQuery("@p0", jsonParameter(...))`, `Collection(Complex)` array alias, nullable omission, non-null guard, scalar/structured positional mixing; `OperationPathTest` 14 incl. `collectionParameter` literal rendering; runtime `EntityOperationsJsonParameterTest` 3 — compact JSON shape, array serialization, null rejection); `OperationImportValidationTest` 5 negatives rewritten (structured params now legal; unresolvable types still fail naming import+parameter)
- **Keyed accessor tests (decision 95):** `ContainerGeneratorKeyedOverloadsTest` 4, `RequestGeneratorKeyedNavTest` 7 (+1: cross-schema keyed nav imports the target EntityRequest — same-package targets had masked the missing import), `RequestGeneratorEntityQueryOptionsTest` 3 (typed select/expand on entity requests, get() via buildContext, collection-only options pinned absent), `RequestGeneratorKeyTest` 3 (composite/inherited keys via overloads, byID-family deletion pinned)
- **Bound-operation tests (decision 96):** `OperationGeneratorBoundTest` 14 (binding-type resolution, base-chain cast segments, overload sets incl. same-names-different-types and inherited binding-type overloads, loud failures, request-class content incl. basePath ctor, structured function parameter riding the shared JSON alias), `RequestGeneratorBoundOpsTest` 3 (+1: bound accessor parameter-type imports — structured/enum classes and List via the now-public `collectParameterImports`; previously only the op-request class was imported, same-schema-primitive corpora masked it), live `TripPinOperationImportTest.boundFunctionRoundTripGetFriendsTrips` (read-only round-trip)
- **Round-8 contested-resolution tests:** `TypedefNavAndContestedBaseCompilationTest` 3 (typedef nav target imports the UNDERLYING class and compiles; contested entity base → zero `Base` imports + FQN extends + FQN nav ref + compiles; contested complex base likewise), `NavPropertyExpandTest` +3 (raw chaining merges into an existing option group with `;`, empty group replaced, nested-paren group merges at top level), `CrossSchemaSimpleNameCompilationTest` regression-green; live `TripPinOperationImportTest.boundFunctionRoundTripGetFriendsTrips` now skips ONLY on the curl-verified OpenType bound-function service fault (lesson 56 discipline)
- **Round-9 correctness-batch review tests:** `GeneratorCorrectnessTest` +3 (contextual-keyword packages `com.record`/`com.to`/`com.open` ACCEPTED — javac-verified legal; `com.true`/`com.continue` rejected; end-to-end: schema namespace `Record` derives package `record` via `toPackageName` and generates), `SchemaInfoCrossKindTest` rewritten 2 (same QUALIFIED key across kinds → loud `IllegalStateException` naming `NS.Foo` — three `classes.put` with one key would silently keep the last; the LEGAL cross-schema same-simple-name-in-one-package case → 0 imports, FQN registry refs, whole output compiles), `ContestedPropertyTypeTest` strengthened (0-import policy pin + javac referee), `OperationImportsHostileParamsCompilationTest` +hostile IMPORT names `A"B\C`/`D"E\F` (quote+backslash via single-quoted XML attributes; escaped invocation segments pinned AND the full client compiles)
- **Total: 812 tests passing offline by default** (357 core + 422 runtime + 25 maven + 8 test module; the remaining generated-client/live classes run via `-Plive-tests` — 936 total incl. live)
- **Future:** Cancellable streaming, streaming media UPLOAD (needs an HttpRequest body-publisher abstraction — uploads currently buffer via readAllBytes), Context-level timeout/policy config (timeouts hardcoded 30s/60s in EntityOperations), async variants on generated requests, auto-paging `pages()` stream, typed `$ref` overloads, collection-bound operations (`Collection(NS.Document)` binding → `client.documents().checkOut()`), composable-function continuation ($filter/$top over a function result chain), paging through function-import collection results, `$N` Content-ID *URL references* within changesets (correlation itself is done), JSON batch (`application/json` + `atomicity-group`), WireMock-based destructive tests, redirect/proxy/TLS transport options, `distributionManagement` once a registry is chosen

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

21. **TripPin `$ref` requires ABSOLUTE URIs — and the 500s we saw were mostly our own bugs.** The original claim ("$ref POST returns 500 for some entity types; DELETE works") was wrong on both counts. Deep-dive (verified with curl against the live service): (a) `@odata.id` with a RELATIVE path is rejected 500 "relative URI value ... odata.context annotation is missing" — fix: `EntityOperations.addRef` resolves entity paths against the service root, exactly like batch URLs (decision 12); (b) the `$id` query parameter is likewise resolved as a URI by the service — `removeRef` now resolves entity paths too (bare keys pass through); (c) the test's target user `keithcombs` does NOT exist in TripPin's seed data (users: russellwhyte, ronaldmundy, keithpinckney, ...) — nonexistent targets make the service's `CreateLink` throw 500 (target=null). With absolute URIs + a real user, add returns 204 and the link is visible. Remaining genuine service flakiness: the reflection provider intermittently fails ALL link mutations with 500 "Property set method not found" (identical requests succeed or fail minutes apart) — the live test detects that message and skips. Always curl the raw service and read the error BODY before blaming the service.

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

61. **`sanitizeClassName` must also avoid JDK class name collisions.** `sanitizeClassName("object")` produces `"Object"`, which collides with `java.lang.Object`. The generated `Object.java` file and `public class Object` declaration shadow the JDK class, causing import/type resolution failures. Fix: check against a set of well-known JDK class names (`Object`, `String`, `System`, `Class`, `Number`, etc.) and append `_` if colliding. Verified by `ReservedWordsCompilationTest`.

62. **`Generator.writeCode` file names must use sanitized class names.** The original code used raw CSDL names (`entityType.name()`, `complexType.name()`, `enumType.name()`, `container.name()`) for file names. This produced `Object.java` instead of `Object_.java` when a type name collided with a JDK class. Fix: use `Names.entityClassName()`, `Names.complexTypeClassName()`, `Names.enumClassName()`, `Names.containerClassName()` for file names, matching the class declaration inside the generated code.

63. **`basePackageForType` must use `defaultBasePackage` as fallback.** When the Generator receives a `basePackage` parameter (via the Maven plugin), it uses it for all schemas not in `schemaPackages`. But `basePackageForType()` in EntityGenerator/ComplexTypeGenerator/RequestGenerator/ContainerGenerator had no access to this fallback — it used `Names.toPackageName(namespace)` instead, which could produce a different package than what the Generator actually wrote files to. Fix: pass `defaultBasePackage` through from `Generator` to all sub-generators and use it as fallback in `basePackageForType()`.

64. **Import generation must iterate all properties, not just own properties.** Both `EntityGenerator` and `ComplexTypeGenerator` iterated `entityType.properties()` / `ownProps` when collecting imports via `addPropertyImports`. Inherited properties using cross-namespace complex types or enums were silently missed, producing unresolvable imports and compilation failures. Fix: iterate `allProps` (own + inherited) in both generators. Verified by `ReservedWordsCompilationTest.inheritedCrossNamespaceImportGenerated` (Child extends Base, Base has cross-namespace `SharedAddress` property — Child's generated import resolves correctly).

65. **Import class names must be sanitized, not raw CSDL names.** Nav target imports, enum imports, and complex-type imports used `Names.simpleNameFromFullName()` directly without applying `Names.entityClassName()` / `Names.complexTypeClassName()` / `Names.enumClassName()`. If the target type name is a reserved word (e.g., `Object`) or a JDK class name, the import produces `import ...entity.Object` which collides with `java.lang.Object`. Fix: wrap all import class names with the appropriate `Names.*ClassName()` sanitizer. Also applies to both `EntityGenerator` and `ComplexTypeGenerator` (which had identical `addPropertyImports` / `addNavImports` methods with the same bug). Verified by `ReservedWordsCompilationTest.reservedWordNavTargetImportIsSanitized` (Entity has nav to `Object` entity — import must be `Object_`, compilation succeeds).

66. **Lambda effectively-final constraint requires a separate variable for sanitized names.** When sanitizing nav target class names inside the import loop, the pattern of reassigning `elementClassName` after the `anyMatch()` lambda fails compilation ("local variables referenced from a lambda expression must be final or effectively final"). Fix: introduce a separate `edmSimpleName` for the lambda comparison and a distinct `className` / `navTargetClass` for the sanitized result. Same issue hit in both `EntityGenerator` and `ComplexTypeGenerator` — the lambda captures `elementClassName` before it can be reassigned.

67. **All code emitting nav target type names must sanitize, not just imports.** `navJavaType()` (field/getter/constructor/with/builder types), `generateNavPropertyConstant()` (static constant type parameters), and `resolveClassNameForConstant()` (collection element types, enum class literals) all used raw `Names.simpleNameFromFullName()` — producing unsanitized type names like `Object` in generated code. Fix: each method now checks `schema.complexTypes()` and `schema.entityTypes()` to apply the appropriate `Names.complexTypeClassName()` / `Names.entityClassName()` / `Names.enumClassName()` sanitizer. Verified by `ReservedWordsCompilationTest` (full compilation with nav to `Object` entity).

68. **`RequestGenerator` must not generate entity request methods for complex-type navigation properties.** Complex types are inline data (deserialized from JSON via `@JsonProperty`), not navigable entity references — there is no URL to build a request against. `generateNavMethod` and the `$ref` method loops generated `OfficeAddressEntityRequest` for a nav pointing to a complex type `OfficeAddress`, which doesn't exist. Fix: added `isComplexTypeNav()` guard that checks `schema.complexTypes()` and skips complex-type nav targets in import generation, nav method generation, and `$ref` method generation. Verified by `MultiSchemaSamePropNameTest` (two schemas with same-name complex types `Address`).

69. **Cross-schema type resolution must search ALL schemas, not just the current one.** Every type-kind lookup (`schema.complexTypes()`, `schema.entityTypes()`, `schema.enumTypes()`) only searched the current schema's types. When a nav target or property type lives in a different namespace (e.g., `Shared.Address` used in `CompanyNS`), it falls through to the default (entity suffix), producing wrong imports like `com.shared.entity.Address` instead of `com.shared.complex.Address`. Fix: added `Names.TypeKind` enum + `resolveTypeKind()` / `resolvedClassName()` / `resolvedSuffix()` utilities that search across all schemas. Each generator now stores `allSchemas` (from `CsdlModel.schemas()`), initializes `effectiveSchemas` in `generate()` (falls back to `List.of(schema)` for backward-compatible constructors), and all helper methods use `effectiveSchemas` for type-kind resolution. Applied to `EntityGenerator`, `ComplexTypeGenerator`, and `RequestGenerator`. Verified by `CrossSchemaImportComplexTypeInPropertyTest` (types from `Shared` schema used in `CompanyNS` and `PersonNS`).

70. **`with*` method parameter `value` shadows field named `value`.** `generateWithMethod` and `generateNavWithMethod` in both `EntityGenerator` and `ComplexTypeGenerator` hardcode the parameter name `value` but reference other fields by bare name (e.g., `value` for the changed property, `fieldName` for others). If a CSDL property maps to Java field name `value` (a valid identifier, not a reserved word), the bare reference `value` in the `new` constructor call resolves to the parameter, not the field — causing incorrect data or compilation errors when the parameter type differs. Fix: prefix all field references in the `with*` body with `this.` (e.g., `this.fieldName`). Builder setter methods already used `this.`; the issue was only in entity/complex-type `with*` methods. Verified by adding `value` property to `reserved-words-metadata.xml` (compilation succeeds) and updating `ComplexTypeGeneratorInheritanceTest` assertions.

71. **`ContextPath` only supports flat batch — changesets require recursive multipart encoding/decoding.** The original `MultipartHelper.decodeResponse` parsed parts in one pass with no nesting. Changesets emit `Content-Type: multipart/mixed; boundary=X` as a part header, with the changeset operations nested inside. `decodeParts()` recurses into these nested boundaries. `encodeBatchRequest()` wraps changeset operations in a nested boundary with `Content-ID` headers. Verified by 7 new tests.

72. **Copy-on-write `with*()` must defensively copy collections and `unmappedFields`.** Original `with*()` methods assigned fields directly: `e.trips = this.trips` and `e.unmappedFields = unmappedFields`. This shared mutable state between original and copy — a violation of copy-on-write. Fix: `with*()` methods now emit `List.copyOf(this.field)` for collection-types and `new HashMap<>(unmappedFields)` for the dynamic-property map. Changed fields still go through `EntityUtil.mergeChanged()` which creates a new `HashSet`. Verified by `WithMethodCopyOnWriteTest` (5 tests checking defensive copies).

74. **Nav property getter/with-method names must be sanitized with the same checks as property getters.** `EntityGenerator.navGetterName()` used raw `"get" + capitalize(nav.name())`, sidestepping `Names.getterMethod` which applies `sanitizeIdentifier` + `isObjectMethodName`. A nav named `class` produced `getClass()` — collision with `Object.getClass()` (final). Fix: added `Names.navGetterMethod(navName)` and `Names.navWithMethod(navName)` that apply the same checks. Both `EntityGenerator` and `ComplexTypeGenerator` use them. Verified by `NavReservedWordTest` (3 tests: `class` → `getClass_()`, `Class` → `getClass_()`, `withClass` is fine).

75. **Simplified deserialization removes the need for a wide-entity threshold.** After Decision 24, all entities and complex types use a public no-args constructor + `@JsonProperty` setters, so the JVM 255-parameter constructor limit and the `long`/`double` double-counting problem no longer apply. `LargeEntityCompilationTest` continues to verify that entities with hundreds of properties compile and deserialize correctly.

76. **`ConcurrentHashMap` key identity matters for large-object keys.** The `TYPE_KIND_CACHE` used `List<SchemaModel>` as a `ConcurrentHashMap` key. `List.hashCode()` traverses every element, and each element's `hashCode()` traverses its entire record tree (entity types `→` properties, navigation properties, keys). For a model with 2000 entities × 10 properties each, every cache lookup walked 20,000+ objects computing hashCode. Fix: identity-based `SchemaListKey` wrapper using `System.identityHashCode(list)` in `hashCode()` and `list == k.list` (reference equality) in `equals()`. Result: **26× speedup** for large metadata generation (14,526ms → 552ms for 2000 entities). Verified by `LargeMetadataPerformanceTest`.

77. **Map key types: favor simple types over records for lookups.** The `entityNamespace` and `complexTypeNamespace` maps were keyed on a record type wrapping the namespace String. Record-based keys add an indirection and a `hashCode()` call through the record's compiler-generated method, while `String` keys are the simplest possible map key (well-distributed hashCode, fast equality, no wrapper allocation). Changing these from record keys to plain `String` keys eliminated several intermediate allocations per lookup.

78. **`Files.createDirectories` per-file is expensive for bulk code generation.** The `Generator.writeCode()` method called `Files.createDirectories(dir)` for every generated file (60,004 calls for 20,000 entities producing 3 files each). Only ~8 unique output directories exist (entity, entity-request, collection-request, enums, complex, container, schema-info). Each call issues a `stat` syscall to check if the directory exists — 59,996 redundant syscalls. Fix: cache created directories in `Set<Path> createdDirectories` and call `Files.createDirectories(dir)` only when `createdDirectories.add(dir)` returns true. Result: ~11% wall-clock improvement at 20,000 entities, `mkdir0` syscall eliminated entirely from CPU profile.

79. **TLAB-backed small allocations are not worth optimizing for wall-clock time.** The allocation profile showed `StringBuilder` growth (`ensureCapacityInternal` → `Arrays.copyOf`), `String.substring` copies, and iterator objects as top allocation sites. Pre-sizing StringBuilders (8192 for entities, 4096 for requests) showed zero measurable wall-clock improvement — these are TLAB refill events, not individual allocations. The JVM's TLAB (Thread-Local Allocation Buffer) makes tiny objects essentially free. Always verify allocation "hotspots" with wall-clock profiling before optimizing; if they don't appear in the CPU profile, they're not costing real time.

80. **`fromNextLink` must parse query strings, not treat them as part of the base path.** Storing `https://s/People?$skiptoken=x` as `basePath` makes every subsequent `addQuery()` render a second `?` (`...People?$skiptoken=x?$filter=...`), and `addCountSegment()` appends `/$count` after the query string. Fix: split the nextLink at `?`, resolve the path part against the current base, and feed decoded `k=v` pairs through `addQuery()` so they live on the trailing query segment. Decoded values re-encode at render time — including the OData-safe restoration (`%27` → literal `'`), which is semantically equivalent but not byte-identical. Verified by 5 new `ContextPathTest` cases (TDD: double-`?` and misplaced `$count` reproduced before the fix).

81. **`executePostEntity` was dead code — creation must be emitted by the generator.** The runtime POST/PUT helpers existed but no generated class called them, so the only way to create an entity was hand-building `BatchOperation.post` with a raw URL and a self-serialized body. Fix: generated collection requests expose `create()` / `postToBatchOperation()`, and entity requests expose `put()` / `putWithETag()` / `putToBatchOperation()`. Also: some services return 204 (empty body) for POST/PUT — deserializing an empty payload throws, so `executePostEntity`/`executePutEntity` return `null` on empty bodies via a shared `deserializeOrNull()` guard (the caller already holds the entity). Verified by `EntityOperationsCreatePutTest` (5), `RequestGeneratorCreateTest` (5), and the live `TripPinGeneratedClientTest.createAndDeletePerson` now creating via `client.people().create(...)`.

82. **Multipart framing must never round-trip bodies through `String`.** The old `MultipartHelper` converted every body via UTF-8 `String`, silently corrupting binary payloads (media PUTs in batch) and stripping body bytes in the decoder. Fix: encode into `ByteArrayOutputStream` (ASCII framing + `writeBytes(op.body())`), decode by splitting on byte-level `--boundary` markers, trim exactly one trailing CRLF, and return body bytes untouched. Also: decoded response headers now live in a case-insensitive `TreeMap` so `BatchResult.getHeader("Retry-After")` matches `retry-after` (the batch path had the lesson-23 bug). Verified by 3 new `MultipartHelperTest` cases (TDD: binary round-trips failed before the fix).

83. **Interceptors buffering streams is an interface problem, not a chain problem.** The zero-interceptor fast path already returns the real transport, but any registered interceptor forced `stream()` through `intercept()`, buffering whole media downloads in memory. Fix: additive `HttpInterceptor.stream(request, delegate)` default method — default falls back to the old buffering (backwards compatible), interceptors that want true streaming override it and call `delegate.stream(request)`. Verified by 3 new `EntityOperationsInterceptorChainTest` cases.

84. **`toJavaFieldName` must reserve the generator's own member names.** Property/nav names like `etag`, `builder`, `contextPath`, `changedFields`, `unmappedFields` collide with the lifecycle fields and static `builder()` the generator emits — producing duplicate-field/method compile errors. Fix: a `RESERVED_MEMBER_NAMES` set in `Names` appends `_` (field `etag` → `etag_`), applied uniformly so getters/setters/`with*`/Builder all agree. Also: leading-digit identifiers (`2FA`) need a `_` prefix in `sanitizeIdentifier`/`sanitizeClassName` (and the prefix must not drop the digit), and `Builder`/`Filterable` join `JDK_CLASS_NAMES` so an entity named `Filterable` becomes `Filterable_` instead of shadowing its own inner class. Verified by `EntityGeneratorMemberNameTest` (6).

85. **`EnumGenerator` must sanitize hostile member names without renaming valid ones.** A member named `class` or `2FA` breaks compilation, but blindly converting all members to UPPER_SNAKE would rename `PersonGender.Male` → `MALE`, breaking the existing generated API. Fix: keep the member verbatim when it is a valid non-keyword Java identifier; otherwise fall back to the sanitized `toConstantName` form. `IsFlags` enums additionally get `fromFlags(long)` returning the `Set` of members whose bits are fully set. Verified by `EnumGeneratorTest` (3) and the unchanged TripPin live tests.

86. **CSDL enum members without `Value` default to previous member + 1, not the member count.** `parseEnumType` used `members.size()`, which is only correct when every value is implicit. For legal metadata like `None=0, Low=10, Medium`, the spec says `Medium = 11`; the parser yielded `2` and `EnumGenerator` baked the wrong value into the enum — silently wrong wire values. Fix: track `lastValue` while parsing; implicit = `lastValue + 1` (0 for the first member). Verified by `StaxCsdlParserEnumMemberValueTest` (2, TDD).

87. **One output package must yield ONE aggregate `SchemaInfo`.** Generating a per-schema registry under the fixed name `<basePackage>/schema/SchemaInfo.java` silently overwrites itself when several schemas share an output package — the Maven plugin's normal configuration (single `basePackage`). Multi-schema services kept only the last schema in the runtime type registry. Fix: `Generator.generate` groups schemas by resolved output package and emits one merged registry per package (`SchemaInfoGenerator.generate(List<SchemaModel>)`); distinct packages still get separate registries. Verified by `GeneratorSchemaInfoAggregationTest` (2, TDD).

88. **`@JsonProperty` setters must be generated on abstract types too.** Setters were emitted only for *own* properties and only for *concrete* types, so a concrete subtype of an abstract base had no setter anywhere for the base's properties — Jackson (with `FAIL_ON_UNKNOWN_PROPERTIES=false`) silently dropped them and base fields came back null. The no-args constructor, getters, and `setEtag` were already emitted on abstract types; only the property/nav setters were gated. Fix: emit setters for own props/navs unconditionally in both `EntityGenerator` and `ComplexTypeGenerator`. Compile-only tests can never catch this class of bug — the fix adds compile-**and-deserialize** tests (`AbstractHierarchyDeserializationTest`): generate, compile against `.m2` jars, load via `URLClassLoader` with the test classloader as parent, deserialize with Jackson, assert base properties via reflective getter invocation. Core gained a test-scoped dependency on `odata-codegen-runtime` to make this harness possible.

89. **Maven counts test-scoped dependencies in cycle detection, and dead main-scope deps hide cycles.** `odata-codegen-runtime` declared a main-scope dependency on `odata-codegen-core` that nothing in runtime ever imported (dead, and contradicting the documented architecture). It only surfaced when core legitimately gained a *test*-scoped runtime dependency (lesson 88) — the reactor refused with `ProjectCycleException`. Fix: delete the dead runtime→core dependency. Architecture note: the dependency direction is core's *tests* → runtime; runtime depends on nothing internal.

90. **PATCH commonly returns 204 — every response-deserializing path needs the empty-body guard.** POST/PUT used `deserializeOrNull` (null on empty body) but `executePatchEntity`, `executePatchEntityWithETag`, and `executeAndGetEntity` called `deserialize` directly. OData v4 §11.4.3 explicitly allows 204 on update, and GET can return 204 for gone entities (lesson 22) — Jackson on zero bytes throws `MismatchedInputException`, wrapped into a spurious `ODataException`. Fix: route all three through the existing `deserializeOrNull`. Verified by `EntityOperationsEmptyBodyTest` (3, TDD).

91. **Never decode nextLink query strings with `URLDecoder` — `+` becomes a space.** `URLDecoder` implements `application/x-www-form-urlencoded` decoding, but OData `@odata.nextLink` values are percent-encoded: servers emit `%20` for spaces and leave literal `+` inside tokens (Graph-style base64 `$skiptoken`s contain `+`). `abc+def` decoded to `abc def`, re-encoded as `abc%20def`, and the next-page request failed. Fix: percent-only `decodePercent` (`%HH` escapes only; malformed escapes left verbatim). Round-trip: literal `+` re-encodes as `%2B`; the OData-safe character restorals (lesson 12) are unchanged. Verified by 2 new `ContextPathTest` cases (TDD).

92. **`Edm.Guid` filter literals are unquoted — Guid needs its own property class.** Mapping `Edm.Guid` to `StringProperty` rendered `Id eq '0c5a...'` — a type error services reject — even though GUID *keys* were already handled correctly (lesson 55). Fix: runtime `GuidProperty<E>` (mirrors `StringProperty`'s shape: eq/ne, null checks, ordering, `getEdmName`) validates the 8-4-4-4-12 shape and emits the bare value, throwing on anything else (injection attempts fail fast); `AbstractTypeGenerator.getPropertyConstantType` maps `Edm.Guid` → `GuidProperty` *before* the String fallback. Because both static property constants and `Filterable` fields flow through that one method, one change covers entities and complex types; the generated `query.*` wildcard import needs no update. Verified by `GuidPropertyTest` (7) + `GeneratorGuidPropertyTest` (3, asserts TripPin `Trip.SHARE_ID` is `GuidProperty`, not `StringProperty`).

93. **Type-kind lookups must unwrap `Collection(...)` first — raw collection forms never match the type-kind map.** `RequestGenerator.isComplexTypeNav` passed `nav.type()` raw, so a collection nav to a complex type (`Type="Collection(NS.OfficeAddress)"`) resolved as `UNKNOWN`, fell through the skip, and the generator emitted imports/nav methods referencing `OfficeAddressCollectionRequest` — a class only generated for entity types → uncompilable output. The single-valued case was fixed and tested; the collection form was never covered. Fix: `Names.resolveTypeKind(Names.unwrapCollectionType(nav.type()), ...)` — and treat this as a rule anywhere `resolveTypeKind` consumes a type attribute (collection wrappers are invisible to name-keyed maps). Verified by `RequestGeneratorCollectionComplexNavTest` (2, TDD) + `MultiSchemaSamePropNameTest` compiling the regenerated entity with a new `Collection(Complex)` nav.

94. **Docs drift is a correctness bug: verify every documented example against the generated API.** README + ~10 doc pages used `client.peopleByUserName(...)` — an API that never existed (real: `client.people().personByUserName(...)`, on the collection request, confirmed in generated `PersonCollectionRequest`) — and an "Async Execution" section around a nonexistent `getAsync()`. Adjacent errors found while fixing: `tripsByTripId(Long)` (actual `Integer`), collection `post()` (actual `create()`), and "built-in Apache/OkHttp transports" that don't exist. `docs/site/**.html` is a committed MkDocs **build artifact** — fix the markdown sources and rebuild the site; hand-editing HTML is wasted work.

95. **Live-service tests must be tagged and excluded by default; destructive tests must clean up in `finally`.** All 7 live classes are `@Tag("live-service")`; root-pom surefire excludes the tag (plain `mvn test` = 396 offline tests, hermetic) and a `live-tests` profile includes everything (518). Related smells fixed in the same pass: (a) an assertion inside `if (result != null)` passes green on regression — use `assertNotNull` and assert unconditionally; (b) a silent `return` on HTTP 500 turns server errors into *passes* — use `assumeTrue` so it reports as skipped; (c) fixed `Thread.sleep` waits are flake generators — poll with retries; (d) tests that create entities on a shared public service must delete in `finally` so failures don't leak garbage.

96. **An Apache-2.0 claim needs the license text and POM metadata.** README said "Apache License 2.0" while no `LICENSE` file existed and the POM had no `licenses`/`developers`/`scm`/`url` — the grant had no legal effect and Maven Central validation would fail. Fix: canonical Apache-2.0 `LICENSE` (from apache.org) + `NOTICE` at repo root, and the four metadata elements in the root POM. `distributionManagement` deliberately deferred until a publishing registry is chosen.

97. **Changeset responses carry `Content-ID` in the PART headers — propagate it or lose correlation.** The batch spec puts changeset `Content-ID` on the response part (not the embedded HTTP response), and a failed changeset collapses N operations into one error part, silently shifting every index. Fix: `BatchResult.contentId` component (also merged into the case-insensitive header map) + `BatchResponse.getByContentId(String)`. When scanning part headers, don't `break` at `Content-Type` — `Content-ID` usually comes after it. Request-side numbering must be batch-wide: one counter threaded through changesets, not per-changeset restarts.

98. **Multipart decoding must fail loudly AND anchor delimiters to line starts.** Undecodable parts, missing closing boundaries, and separator-less parts throw `ODataException` instead of returning empty/partial results. `indexOfBoundary` only accepts a delimiter at a line start followed by `--`, CRLF/LF, or padding — boundary bytes inside a binary body can no longer split parts. The old terminator check (`startsWith(endDelimiter)` after the delimiter) never matched at that position; detect the closing delimiter at the match site instead. Lesson for count validation: a failed changeset legitimately collapses to one part, so "decoded count == submitted count" is wrong — Content-ID correlation is the correct mechanism.

99. **User-supplied URLs/header values go into multipart framing verbatim — reject CR/LF/NUL at construction.** `BatchOperation`'s compact constructor validates all three; a `
` in a URL would forge headers or embed a whole request. Also skip the implicit `Content-Type: application/json` when the caller supplied one (case-insensitive).

100. **Boundary parameters can be quoted and case-varying.** `boundary="abc"` (RFC 2046 — quotes are not part of the value) and `BOUNDARY=` both occur in real responses; the boundary regex takes quoted-or-bare, and parameter matching is case-insensitive. Missing either yields zero parts — now a loud failure (lesson 98) instead of an empty response.

101. **Joining filter predicates requires parenthesization; `div` vs `divby` is type-dependent.** Multiple `filter()` calls ANDed in `NavQuery` wrap each predicate in `(...)` — `and` binds tighter than `or`, so unparenthesized joins silently change semantics. `NumberExpression.divide` emits `div` (truncating, integers only) vs `divby` (Double/Decimal/Single) by operand type.

102. **Date/time filter literals must be validated, and typed overloads need care to keep `equalTo(null)` unambiguous.** `DateTimeProperty` validates String literals against the OData ABNF (bare Date/DateTimeOffset/TimeOfDay, `duration'...'`) — raw concatenation allowed `$filter` predicate injection. Typed values (LocalDate/OffsetDateTime/LocalTime/Duration) format per the ABNF, but implementing them as one overload per type makes `equalTo(null)` ambiguous between unrelated types and breaks the documented null-routing pattern — use a single `Object`-parameter operator that formats by runtime type. `LocalTime.toString` omits zero seconds (invalid OData) — always format `HH:mm:ss`. `EnumProperty` without a fully-qualified type name now throws `IllegalStateException` instead of emitting the invalid `Color'Red'` form; flags enums get `has(V)`.

103. **Query parameters render once, after all URL segments.** `ContextPath.appendSegments` defers all segment queries to a single trailing `?...` — emitting them per-segment produced `?` mid-URL whenever a segment was appended later (`addQuery(...).addSegment("$ref")`). The `addCountSegment` query-migration workaround collapsed to a plain `addSegment("$count")`. Collection reads honor a pluggable `Serializer` (default `JacksonSerializer` keeps the profiled `convertValue` fast path; custom serializers receive element bytes). `JdkHttpTransport` caches `HttpClient`s per connect-timeout — the connect timeout is per-client, and `request.connectTimeout()` was previously dead API. The default interceptor `stream()` checks the status before wrapping (a 404 must throw, not stream the error body). `Retry-After` parses HTTP-dates (RFC 1123) and exposes `hasServerRetryAfter()` to distinguish server-specified from the fabricated default.

104. **Alias-qualified type references resolve at parse time; required attributes fail with context.** `Schema#Alias` is only usable within its declaring schema, so `resolveTypeRef` normalizes `self.Address` → real namespace (preserving `Collection(...)` wrappers) at every type-bearing attribute. `requireAttr` validates `Namespace`/`Name`/`Type`/`EntityType` with the offending element in the message; enum `Value` parse errors name the enum and member.

105. **Detect member-name collisions instead of generating uncompilable code; auto-dedup is a bigger refactor than it looks.** Both type generators run `checkMemberNameCollisions` (fields AND constants — closing detection of the `value`/`VALUE` → `VALUE` constant case-collision) and throw naming both CSDL members. Two constraints discovered while implementing: same-name inheritance redeclaration must be tolerated (the generators ignore the inherited duplicate; the perf test's synthetic metadata relies on this), and threading per-type name maps through all 44 `toJavaFieldName`/`toConstantName` call sites is the real cost of automatic suffix-disambiguation — still future work. `Generator.writeCode` also fails when two types map to one output file (previously silent overwrite), and `toPackageName` lowercases with `Locale.ROOT`.

106. **Entity requests must resolve the base chain; `countValue()` must clear every inapplicable option.** Request classes don't extend each other, so inherited navs, `$ref` methods, named streams, and `HasStream` (which CSDL applies to all derived types) are re-emitted on subtype requests. `/$count` accepts only `$filter`/`$search`/`$apply` — `countValue()` clears `selects`/`expands`/`orderings` in addition to `$top`/`$skip` (`copy()` builds fresh lists, so the source request is untouched).

107. **Polymorphic `@odata.type` deserialization: wire the SchemaInfo registry through the request layer.** `EntityOperations` gained SchemaInfo-aware overloads that strip the `#` prefix, resolve the type, and deserialize to the subtype when assignable (per element for collections); generated entity/collection requests pass `SchemaInfo.INSTANCE`. Generated enums map JSON numerics by CSDL **value** via `@JsonCreator fromJson(Object)` (Jackson's default is ordinal — wrong for non-contiguous values) while strings keep mapping by name; `@JsonValue` was deliberately NOT added because it would change POST/PATCH bodies from the OData name-string form to numbers.

108. **Marker files for incremental generation are keyed by metadata SOURCE identity — not execution id, not content hash.** `${mojo.execution.id}` did not resolve at runtime, and keying by the config/metadata hash breaks when the metadata changes (the stale-file manifest is looked up under the new name and never found). The URL/file-path identity is stable across content changes and distinct per execution, so shared-output-dir executions keep independent markers (the test module's three clients now generate once per build). Markers record a file manifest for stale deletion (path-traversal-guarded, `.java` only, legacy hash-only markers delete nothing). Mojo failures (`MojoFailureException`) are rethrown as-is instead of being re-wrapped by `catch (Exception)`, and `metadataHeaders` (Properties) carries auth to private metadata endpoints.

109. **Guard the seam between "property-like" expressions and where property paths are legal.** Transformation methods (`toLower()`, `substring()`, `date()`, ...) return full property types so they compose in `$filter`/`$orderby` — but `$select` accepts structural property paths only. Validating `select()` (both the runtime `NavProperty`/`NavQuery` and the generated collection-request `select()`) by rejecting names containing `(` converts an HTTP 400 at runtime into a local `IllegalArgumentException`. When emitting this check via string generation, close each generated string literal per line — a raw newline inside a generated literal is an "unclosed string literal" compile error in the OUTPUT, caught only by compilation tests.

110. **Lambda operators must agree with the filterable's property prefix, and fail clearly without a factory.** `any(x: x/Name eq 'a')` only works when the element's properties use the `x/` prefix — the alias is now derived from `FilterableElement.prefix()` (default `x`, matching generated `Filterable` constants) and validated as a simple identifier; a missing filterable factory throws `IllegalStateException` explaining the constructor, not a bare NPE. Nested `any`-in-`any` still shadows the outer alias — unique aliases need threading through the factory (documented limitation). `ApplyBuilder` validates `top/skip >= 0` and renders from a snapshot while documenting itself as non-thread-safe.

111. **`continue-on-error` is a preference, not an Accept parameter.** OData 4.01 requests partial batch processing via `Prefer: continue-on-error=true` (the 4.0 `odata.continue-on-error` Accept-parameter form is legacy); `BatchRequest.continueOnError()` adds it in both sync and async paths. JSON batch (`application/json` + `atomicity-group`) remains a future second encoder alongside `MultipartHelper`.

112. **Normalize hostile input at the parser boundary; validate structural references at generation time.** Type attributes are trimmed inside and out (rebuild `Collection(...)` wrappers with a trimmed inner name — an outer-only `trim()` leaves `Collection( Edm.String )` intact) and malformed/nested collections throw. `EntityContainer Extends` resolves in a post-parse pass (the base may live in a not-yet-parsed schema): index containers by qualified name, merge base-first with own-overrides, fail on unknown/circular refs. Key `PropertyRef`s are validated against the resolved (own+inherited) property list in `EntityGenerator` — a bogus ref previously produced an `Object`-typed key accessor that failed only at URL-build time. `CsdlModel` records defensively copy their list components; a parsed model can no longer be corrupted after the fact.

113. **Cache cross-schema lookups by qualified name, and map Java types from the RESOLVED primitive.** The TypeDefinition cache is keyed `namespace.name` with a simple-name fallback for unqualified refs — otherwise a `Length` typedef in schema A shadows a different `Length` in schema B. Adjacent gap found while fixing: `NumberProperty` type parameters were derived from the RAW edm type, so typedef-backed number properties rendered as `Object` even when the cache resolved correctly — generate type parameters from `resolveTypeDefinition(...)`. Related polish rules: generated class names must not shadow the `runtime.query.*` classes imported on demand (add them to the sanitize list); non-nullable primitive getters stay boxed (fields are boxed, lenient services can deliver null, and a primitive getter NPEs on unboxing); containment navs (`ContainsTarget`) get no `$ref` methods; enum filter literals must be fully qualified (qualify unqualified refs with the owning schema's namespace — aliases already resolved at parse time).

114. **Compile with `release`, not `source/target` — and expect the switch to catch real bugs.** `source/target` compiles against the running JDK's own platform APIs, so newer-JDK methods slip through and only fail at runtime on the target version. Switching this repo to `<release>17</release>` immediately flagged `List.getFirst()` (a Java 21 API) in `BatchResult` and a live test — code that had been green all along purely because builds ran on a newer JDK. Related build hygiene applied in the same pass: version-manage repeated dependency versions, pin `maven-plugin-plugin` with an explicit `goalPrefix`, add `project.build.outputTimestamp` for reproducible builds, key test metadata per module (no `${project.parent.basedir}` reach-ins), and assert `threadSafe`/descriptor properties rather than CLASS-retention plugin annotations.

115. **Key literals must be type-driven, not value-shape-driven.** Generated key accessors pass the key's resolved Edm type to `ContextPath.addKey(name, value, edmType)`: `Edm.String` is always quoted (killing the GUID-heuristic hazard where UUID-shaped string keys went out bare), Guid/Date/DateTimeOffset render bare ISO, TimeOfDay always `HH:mm:ss` (toString drops zero seconds), Duration `duration'...'`, and qualified enum types `NS.Enum'Member'`. The untyped two-arg `addKey` keeps legacy heuristics for direct callers. Related carry-forwards closed in the same pass: `JavaNetHttpTransport` deleted (dead duplicate, divergent error semantics); property-constant names auto-deduped per type (`VALUE`/`VALUE_2`) across constants, nav constants, and Filterable fields — field-level name folding still fails loudly; v4 nested referential constraints parse (`<Principal>`/`<Dependent>` PropertyRefs paired by position, mismatched counts throw); the interceptor chain is cached per Context (WeakHashMap — records compare by value, so equal configurations share a chain, and the zero-interceptor fast path returns the real transport untouched).

116. **`changedFields` finally pays for itself: PATCH filters to tracked fields, with a safe fallback.** `Serializer` gained a default `serialize(value, type, includeFields)`; the Jackson implementation serializes through a tree and strips untracked properties (honoring the wire mapper's inclusion rules exactly). `executePatchEntity[WithETag]` uses it only when the entity is an `ODataEntityType` with a non-empty `changedFields` — Builder and `with*` flows get true partial updates, while GET-deserialized entities mutated via setters (which deliberately do NOT track, or deserialization would mark everything changed) keep full-body merge semantics. One trap found in testing: a changed-but-null field serializes to nothing under `NON_ABSENT` — the filter operates on the serialized tree, not the field list. Also in this pass: `BatchRequest` sync/async share one `submitBatch` assembly (the copies had drifted) and throw typed exceptions with parsed `ODataError` for non-2xx; blank `baseUrl` fails at `Context.build()`; skipped schema elements populate `warnings`; `ContainerGenerator` logs per-import warnings for the still-ungenerated function/action imports.

117. **Auto-fix generated names only where the name is internal API.** The rename-vs-error split: property CONSTANTS deduplicate silently (`VALUE`/`VALUE_2`) because they are internal handles no server contract depends on, but FIELD/getter folding (`Name` vs `name` → `name`) must abort generation — a renamed field breaks `@JsonProperty` mapping and silently drops data. Same principle decided `@JsonValue` (would change the wire format → rejected) and the typed-key design (type-driven literals instead of heuristics). When a generator can produce two kinds of output — one merely cosmetic, one contract-breaking — only the cosmetic one gets automatic repair.

118. **A chronically skipped live test is a failing test in disguise — and a documented "known service limitation" is a hypothesis, not a fact.** `addAndRemoveFriend` had skipped in every run since its `assumeTrue` was introduced; the skip encoded lesson 21's conclusion ("TripPin `$ref` POST returns 500"), which three review rounds never re-verified — the round-3 review even listed it under "not re-litigated." Deep-dive proved the 500s were OUR relative `@odata.id`/`$id` plus a test user (`keithcombs`) that doesn't exist in the seed data. Rules: (a) any live test that skips N consecutive runs gets an investigation, not accommodation; (b) every review round re-tests at least one "known limitation" by replaying the exact client payload with curl and READING the error body — the server's error text is the ground truth that static review cannot see; (c) blame the service only after your replayed, spec-checked request succeeds nowhere.

119. **Tests written to current behavior lock bugs in; write them against the contract.** `addRefLeavesPlainUrlUnchanged` asserted the relative-URI passthrough *exactly* — a unit test authored after the bug became a change-detector that had to be (and was) updated when the bug was fixed. When adding tests for existing behavior: cite the spec/service contract the behavior implements (here: OData's absolute-URI requirement for `@odata.id`), and validate test fixtures against reality (do these usernames actually exist in the seed data?). The same principle applies to doc audits: the round-3 docs sweep was grep-driven against a known-stale pattern list, so `ref.md`'s entirely fictional API (`friends().addRef(Person)`, `setRef`) survived — verify every page's API calls against the generated code, not just the patterns you already know are stale.

120. **Every path that turns a CSDL name into a Java identifier must go through the same sanitization — and the tests must compile the output.** Round 4 found five distinct unsanitized/unvalidated name paths (constants, enum members, Filterable nav fields, container accessors, key-accessor method names) that fields/getters had been protected against rounds earlier — each fix was one line, but each path had to be *found by executing*: generation "succeeded" on all of them, and two produced output that still compiled while being wrong. Two rules that paid off: (a) when sanitizing a wire-facing name (enum member), preserve the round-trip — the generated `BY_NAME` map keeps JSON using the original CSDL member name while the Java constant is sanitized; (b) assert generated code with `javac`, not `contains()` — string assertions passed on `FIRST_NAME` while a *different* unsanitized path (`tByFirst-Name`) still broke compilation, and only the compile check caught it.

121. **Parity matters: audit `Function` vs `Action` (and any mirrored readers) together.** `parseAction` missed `resolveTypeRef` on `ReturnType` while `parseFunction` had it — a single missing call. The bug hid because TripPin has no alias-qualified Action return types. The fix is one line; the lesson is to diff mirrored parsers when touching one. Verified by `StaxCsdlParserActionAliasTest` (expects `NS.Person`, previously `self.Person`).
122. **An alias is a document-scoped map, not a per-schema singleton.** Storing only `currentAlias` made `Schema B`’s `Type="a.Foo"` (where `a` is defined in `Schema A`) stay `a.Foo` when `A` was parsed earlier and stay unresolved when `B` was parsed first. Fix: `globalAliasMap: alias→namespace` + `fixupCrossSchemaAliases` post-pass over every model component (including `Collection(...)` wrappers). Order-independent. Verified by `StaxCsdlParserCrossSchemaAliasTest`.
123. **Unqualified `BaseType="Base"` needs a global simple-name lookup, not just string splitting.** `extendedBasesForSchema` derived `baseNs` from `bt`’s literal namespace via `namespaceFromFullName(bt)`; for unqualified `Base` it used `s.namespace()` (the *referencing* schema), so a cross-schema unqualified inheritance was lost and the base stayed `final`. `findBase` likewise only checked `entityTypeByQualifiedName` then same-schema `entityTypeMap`. Fix: `findBaseGlobal` scans `effectiveSchemas` for the simple-name match and `extendedBasesForSchema` resolves the base’s real namespace via that lookup. Verified by `CrossSchemaInheritanceFinalTest` (unqualified now `public class Base`, was `public final class Base`).
124. **Don’t restore `%3D` (`=`) in query values — it’s the `name=value` separator.** `URLEncoder.encode("a=b")` → `a%3Db`; restoring to `a=b` makes `?q=a=b` parse as `q=a` + stray `b`. `appendSegments` already adds the single `name + "=" + encode(value)` separator. Restoring `$ ' ( ) , / : @` is correct for OData, but `=` must stay `%3D`. Fix: drop `case "3D"` in `ContextPath.encodeQueryParam`. Updated `ContextPathTest` expectation (`a%2Bb%3Dc` not `a%2Bb=c`). Verified by `ContextPathEncodeQueryParamTest`.
125. **Key literals need `%2F` and `%2B` — `/` and `+` are legal inside string keys.** `People('a/b')` with a literal `/` splits the URL path; `a+b` is ambiguous (`+` decoded as space on some stacks). Both are legal in `Edm.String` keys (file paths, tokens). Fix: `ContextPath.encodeKeyValue` adds `'/'=>%2F`, `'+'=>%2B`. Verified by `ContextPathEncodeKeyValueSlashPlusTest`.
126. **`BatchRequest.resolveOperationUrl` must avoid `//` and be case-insensitive on the scheme.** `baseUrl + "/" + url` with `url="/People('scott')"` (hand-written) produced `https://svc//People`. Also `startsWith("http")` missed `HTTP://`. Fix: `baseUrl + (url.startsWith("/")?"":"/") + url` and `regionMatches(true,0,"http",0,4)`. `ContextPath.toRelativeUrl` already returns no leading `/` for generated batch ops, but the hardening is needed for manual `BatchOperation` callers. Verified by `BatchRequestDoubleSlashTest`.
127. **Path traversal needs both package-name validation and path normalization.** `Generator.writeCode` did `outputDir.resolve(packageDir)` with no checks. Hostile `basePackage=../../evil` or `schemaPackages` with `/` writes outside `target`. Fix: `validatePackage` rejects `/`, `\`, `:`, `..`, empty segments, and non-identifier chars; `writeCode` additionally normalizes and asserts `target.startsWith(outputDir)`. The two layers give a clear early error plus defense-in-depth if `toPackageName` ever produces a bad package. Verified by `GeneratorPathTraversalTest`.
128. **Marker hashes must fold header values, not just names.** `GenerateMojo.computeMarkerHash` appended `header=<name>` only, so rotating `Authorization: Bearer <token>` left the hash unchanged and reuse stale sources. Fix: append `header=<name>=<value>`. No secret leakage — value is folded into the digest, not logged. Verified by `GenerateMojoMarkerValueTest`.
129. **Only 301/302/303/307/308 are redirects; 304 is not.** `downloadMetadata` used `code>=300 && <400` as redirect test. `304 Not Modified` has no `Location` and was thrown as `Redirect without Location: HTTP 304`, masking the real `Failed to download metadata: HTTP 304`. Fix: explicit set `301,302,303,307,308`. Verified by `GenerateMojoRedirectTest`.
130. **`toPackageName` must produce a valid Java package, not just lowercased namespace.** `3D.Model` → `3d_model` is illegal (starts with digit). Fix: map `.`/`-`/other non-identifier chars to `_` and prefix `_` when the result doesn't start with a valid identifier start. `3D.Model` → `_3d_model`, `Com.Example.Model` still → `com_example_model`. Verified by `NamesToPackageNameTest`.
131. **Simple-name caches must be order-independent; ambiguous collisions require qualified lookup.** `Names.buildTypeKindMap` with `putIfAbsent(e.name(), kind)` made `Address` as ENTITY in one schema and COMPLEX in another first-wins. `AbstractTypeGenerator` typedef cache did the same for `Length` → `Int32` vs `Double`. Fix: collect qualified entries first, only promote a simple name when it maps to a single kind/value across schemas; colliding names stay absent and require `NS.Name`. Verified by `TypeKindCacheFirstWinsTest`.
132. **Atomic interceptor-chain construction is a synchronized compound, not per-method.** `Collections.synchronizedMap(WeakHashMap)` only synchronizes per-method, so `get` then `put` can race and build duplicate chains. Fix: `synchronized (CHAIN_CACHE)` around the compound `get`→`build`→`put`. Zero-interceptor fast path still returns the real transport without touching the cache. Verified by `EntityOperationsChainCacheTest`.
133. **Require, don't allow null, for ReferentialConstraint PropertyRefs.** `parseReferentialConstraint` used `getAttr("Name")` for `<PropertyRef>` inside `<Principal>`/`<Dependent>`, letting `null` slip into the model and surface as NPE later. Fix: `requireAttr` with context `"PropertyRef in ReferentialConstraint"` — fails fast with the offending element in the message. Verified by `StaxCsdlParserMediumTest.m1_propertyRefMissingNameThrows`.
134. **Unqualified container Extends must be unambiguous or fail.** `mergeContainer` scanned `byQualifiedName.values()` (HashMap) and broke on first simple-name match — nondeterministic when two containers share `SharedContainer`. Fix: count matches; 0 → unknown, 1 → use it, >1 → throw "Ambiguous unqualified Extends ... use qualified name". Verified by `StaxCsdlParserMediumTest.m2_unqualifiedExtendsAmbiguousThrows` (now throws) and qualified case still passes.
135. **Sanitizing class names must map hostile chars, not drop them.** `sanitizeClassName` mapped only `'.'`/`'/'` to `'_'` and dropped `'-'` and others → `"A-B"` and `"AB"` both became `"AB.java"` → collision. Fix: any non-`isJavaIdentifierPart` maps to `'_'`, so `"A-B"` → `"A_B"` distinct from `"AB"`. Verified by `NamesMediumTest`.
136. **Typedefs must be resolved before deciding complex vs entity.** `isComplexTypeNav` unwrapped `Collection(...)` but passed the raw typedef name `MyAddr` to `resolveTypeKind` → `UNKNOWN` → treated as entity and emitted a non-existent `MyAddrEntityRequest`. Fix: `resolveTypeDefinition(unwrapped)` first, then `resolveTypeKind`. Verified by `RequestGeneratorMediumTest` (typedef `MyAddr → Address` complex, now skipped).
137. **Enum member de-duplication needs deterministic suffixes.** Two members `A-B` (→ `A_B`) and verbatim `A_B` collided to `enum E { A_B(0), A_B(1) }` → compile error. Fix: track `usedNames`/`usedCount`; colliding base `A_B` becomes `A_B_2`, `A_B_3`. `BY_NAME` map uses deduped constants so JSON wire names round-trip. Verified by `EnumGeneratorMediumTest`.
138. **Nano formatting must be zero-padded to 9 digits.** `formatTime` did `"." + nano` → `1` → `".1"` (should be `".000000001"`), `1_000_000` → `".1000000"` (should be `".001000000"`). Fix: `String.format("%09d", nano)` (full 9, e.g. `001000000`). Verified by `DateTimePropertyMediumTest` (4 checks).
139. **Floating vs integer division needs property type, not just value type.** `NumberExpression.divide` chose `div`/`divby` by `value instanceof Integer` → `Double` property `/ Integer 2` used truncating `div`. Fix: `NumberProperty` stores `Edm.Double/Single/Decimal` and overrides `divide` to use `divby` for floating properties (or floating values). Generators now emit `new NumberProperty<>("Price", Person.class, "Edm.Double")`. Verified by `NumberExpressionMediumTest`.
140. **Nested any/all need unique aliases per depth, not just validation.** `CollectionProperty.lambda` validated alias but always used `"x"` → `any(x: x/Name and x/Names/any(x: x/Value))` shadows outer `x`. Fix: `ThreadLocal<Integer> LAMBDA_DEPTH` — depth 0 → `"x"`/`"d"`, depth 1 → `"x1"`/`"d1"`; remap `baseAlias/` in predicate result to `alias/`. Respects `FilterableElement` custom prefix (`d` → `d`/`d1`). Verified by `CollectionPropertyMediumTest` and existing `l13LambdaAliasFollowsFilterableElementPrefix`.
141. **Encoded CRLF must be rejected via percent-decoding.** `BatchOperation.rejectLineBreaks` checked raw `\r\n\0` only → `People%0D%0A` passed and decoded to CRLF on server. Fix: scan `%HH` where decoded byte is `\r`/`\n`/`\0` (case-insensitive) and throw. Verified by `BatchOperationMediumTest` (5 checks).
142. **Dedupe OData headers case-insensitively.** `JdkHttpTransport.buildJdkRequest` did `builder.header("OData-MaxVersion", "4.01")` unconditionally → `4.01, 4.0` duplicate when caller supplied `4.0`. Fix: check `hasMaxVersion`/`hasVersion` via `equalsIgnoreCase` before adding defaults. Verified by `JdkHttpTransportMediumTest`.
143. **Patch must include empty collections when explicitly requested.** `JacksonSerializer` used `NON_EMPTY` for all `Collection`/`List`/`Set` → `tags: []` omitted even when user did `withTags(List.of())` and `changedFields` contained `tags`. Fix: add `MAPPER_FOR_PATCH` without `NON_EMPTY` and use it in `serialize(value, type, includeFields)` (the `includeFields` tree already filtered). Verified by `JacksonSerializerMediumTest`.
144. **Temp files need explicit cleanup, not just deleteOnExit.** `GenerateMojo.downloadMetadata` did `createTempFile` + `deleteOnExit` + `Files.copy` with no try-catch cleanup and `execute` never deleted after `parseMetadata` → `/tmp` accumulation in daemons. Fix: `Files.copy` wrapped in try-catch with `deleteIfExists` on failure, and `execute` deletes temp file after `parseMetadata` when `metadataUrl` was used. Verified by `GenerateMojoMediumTest`.
145. **Stale generated files must be cleaned across Generator calls.** `Generator.generate` did `written.clear()` at start, so `Foo.java` renamed to `Bar.java` left old `Foo.java` on disk → stale class on classpath. Fix: snapshot `previousFiles = new HashSet<>(written.keySet())` before clear, then after generation delete any `old ∉ written`. `GenerateMojo` already handles cross-process via marker manifest; `Generator` now handles in-process. Verified by `GeneratorMediumTest`.
146. **Case-insensitive absolute URL check for @odata.id / $id.** `EntityOperations.addRef`/`removeRef` did `startsWith("http")` → `"HTTP://"` treated as relative → `https://service/HTTP://...`. Fix: `regionMatches(true,0,"http",0,4)` via `isAbsoluteHttpUrl`. Verified by `EntityOperationsMediumTest` (4 checks).
147. **Contextual keywords appear after the language version you target.** `isReservedWord` covered `transitive` (Java 14) but omitted `when` (Java 19 pattern matching) and `non-sealed` (Java 17 sealed). A CSDL enum member named `when` stayed verbatim → invalid Java. Fix: add `when`/`non-sealed` to the switch; `toConstantName` already maps `-`→`_` so `non-sealed`→`NON_SEALED`. Verified by `NamesLowTest`.
148. **Acronym boundaries need lookahead, not just lookbehind.** `toConstantName` split only on `lower→Upper` ( `a→B` ), so `XMLHttp` stayed `XMLHTTP`. Correct is `XML_HTTP`: insert `'_'` when `Upper` follows `Upper` and next is `lower` (`L→H→t`). Fix: check `prev is lower || (prev is upper && next is lower)`. Verified by `NamesLowTest` (`XMLHttp`→`XML_HTTP`).
149. **Malformed percent-escapes should be tolerated, not rejected, but must not be double-encoded.** `ContextPath.decodePercent` correctly leaves `%ZZ` verbatim; re-encoding a literal `'%'` as `%25` gives `%25ZZ` — the single correct encoding of a literal `%`. No code change beyond ensuring incomplete trailing `%`/`%2` is handled without `StringIndexOutOfBounds`. Low-severity, verified by `LowIssuesTest.l3` (no throw, round-trips).
150. **Quoting depends on the Edm type, not just the Java type.** `ContextPath.formatTypedValue` returned `String.valueOf` for unknown types, so `Edm.UnknownType` string `test` rendered `test` not `'test'`. Fix: `default` now checks `String s` → `'`+`encodeKeyValue`+`'` before `Enum`/`String.valueOf`. Quoted strings are required even for unknown String-typed keys. Verified by `LowIssuesTest.l4`.
151. **RFC 2046 allows whitespace around `boundary=`** `MultipartHelper.BOUNDARY_PATTERN` was `boundary=` (no spaces). Real `Content-Type: multipart/mixed; boundary = "abc"` (spaces) was missed → 0 parts decoded. Fix: `boundary\\s*=\\s*`. Verified by `LowIssuesTest.l5` (spaced boundary now parses).
152. **A batch that ends at the delimiter without `--` is truncated, not complete.** `MultipartHelper.decodeParts` returned partial results when the body ended at `--boundary` (no trailing `--`). Fix: track `foundClosing`, throw `missing closing boundary '--'` if loop exits without having seen `--`. Verified by `LowIssuesTest.l6`.
153. **Defensive copies matter even for `unmodifiableList`.** `CollectionPage` did `unmodifiableList(currentPage)` without copy; caller mutating the original list after construction was visible through `currentPage()`. Fix: `unmodifiableList(new ArrayList<>(currentPage))`. Verified by `LowIssuesTest.l7`.
154. **Null-safe equality for nullable content IDs.** `BatchResponse.getByContentId` did `contentId.equals(result.contentId())` → NPE when `contentId==null` (standalone parts have `null`). Fix: `Objects.equals`. Verified by `LowIssuesTest.l8`.
155. **One `ObjectMapper` with identical config is enough.** `DynamicPropertyConverter` created `new ObjectMapper()` with same `Jdk8Module`/`JavaTimeModule` as `JacksonSerializer`; two instances, same modules, wasted memory and risk of drift. Fix: `DynamicPropertyConverter.MAPPER = JacksonSerializer.sharedMapper()` (package-private accessor). Verified by `LowIssuesTest.l9` (`assertSame`).
156. **A fix that only covers its own test's path isn't done — audit every exit path and every consumer of the changed helper.** M12 deleted the downloaded temp file after `parseMetadata`, but `execute()` returns early on the up-to-date marker path BEFORE parsing — the leak survived precisely in the incremental build the fix targeted; only a behavioral test that runs `execute()` twice against a local server caught it. Same round: the blind `.replace()` alias rebinding worked on every test input but corrupted quoted literals and longer identifiers (`Max/`) under adversarial data; first-wins `findBaseGlobal` contradicted the ambiguity policy established one finding earlier (H11/M2); typedef-of-complex was fixed while typedef-of-entity navs still emitted references to a nonexistent class. Rules: (a) when a method has multiple exits, cleanup belongs in `finally`; (b) string surgery on expressions must be quote-aware and word-boundary-bounded; (c) pick ONE policy per problem class (ambiguity: throw) and apply it everywhere; (d) when fixing a resolution gap for one kind of target (typedef→complex), check the mirrored kind (typedef→entity) in the same pass.
157. **When you fix a bug class, grep for its siblings in the same pass.** Lesson 101 parenthesized chained predicates in `NavQuery.toODataExpand()` — but the generated collection request's `buildContext` joined `filters` with a bare `String.join(" and ", filters)`, so `filter(A.or(B)).filter(C)` rendered `A or B and C` (= `A or (B and C)`) at the top level. The round-5 review found it only because NavQuery's fix was documented as a lesson; an undocumented twin would have survived five review rounds. Fix: emitted join parenthesizes each predicate except a lone one, mirroring NavQuery exactly (`RequestGenerator.java`). Verified by `RequestGeneratorFilterParenTest`.
158. **A convenience constructor that nulls a field is a silent data-loss seam.** `BatchResponse.get(index, type)`/`getAll(type)` rebuilt results through the 4-arg `BatchResult` constructor — `contentId` became null, destroying the changeset correlation (lesson 97) the moment a caller took a typed view. Rule: every record-copy path must forward ALL components; assert with a raw→typed round-trip test (`BatchResponseTypedViewTest.correlatedLookupThenTypedViewRoundTrip`).
159. **Percent-decoding must collect bytes and decode once as UTF-8.** `ContextPath.decodePercent` appended each decoded `%HH` as a bare char, so `%C3%A9` became `Ã©` (mojibake) on every nextLink round-trip carrying non-ASCII values. Fix: write decoded bytes into a `ByteArrayOutputStream`, verbatim ASCII chars alongside, then one `new String(bytes, UTF_8)`. Malformed escapes stay verbatim. Verified by `ContextPathNextLinkDecodingTest`.
160. **`CompletableFuture.join()` is NOT interruptible** — unlike `FutureTask.get()`. Setting the calling thread's interrupt flag before `join()` causes an internal busy-park loop, not an exception (this hung a test run for 3 minutes). Interruption reaches sync-over-async wrappers only when the async TASK fails with `InterruptedException` (executor shutdown-now, cancelled I/O). Those wrappers must restore the flag on the CALLING thread and keep the cause typed (`EntityOperations.executeSync`/`streamMedia`, `BatchRequest.execute`). Tests model this deterministically with `failedFuture(new InterruptedException(...))` — never "interrupt self then join" (`SyncAsyncErrorContractTest`).
161. **One error channel per async API.** `HttpInterceptor.stream()`'s default threw synchronously while `submit()` delivered failures through the future — callers composing with `exceptionally()` saw exceptions escape at call time instead. Fix: default returns `CompletableFuture.failedFuture(...)`. Existing status-check semantics preserved (`m14InterceptorStreamDefaultThrowsOnErrorStatus` still passes because the sync wrapper unwraps).
162. **Type the statuses services actually return, not just RFC-famous ones.** TripPin answers PATCH/DELETE-without-If-Match with **428 Precondition Required** (lesson 20) — it fell into the generic bucket where callers couldn't distinguish "you forgot the etag" from 412 conflict. Added `PreconditionRequiredException` (428), `RequestTimeoutException` (408), `ResourceGoneException` (410), each carrying parsed `ODataError`. Verified by `TypedStatusExceptionTest`.
163. **Header-only ETag services need a capture hook, not raw HTTP.** GET discarded response headers entirely; entities got their etag only from the `@odata.etag` body annotation, so services sending only an `ETag` header left `patchWithETag` unusable after a plain GET. Fix: default no-op `ODataEntityType.applyETagFromResponse(String)` + runtime wiring in `executeAndGetEntity` (only when the entity carries NO etag — body annotation wins) + generator emits the override on root entities (the field lives there). Verified behaviorally by `EntityOperationsEtagHeaderTest` (capture, precedence, If-Match flow) and content-wise by `EntityGeneratorEtagHeaderOverrideTest`.
164. **Builders must not be consumed by execution.** `BatchRequest.execute()` mutated `entries` in place (relative → absolute URLs). Idempotent, but execution-as-mutation is a statefulness trap. Fix: resolve into a local copy in `submitBatch`. No behavioral defect ⇒ pure refactor, no red-green theater (lesson 119 applies to refactors too).
165. **Per-instance resource caches over static.** `JdkHttpTransport.CLIENTS_BY_CONNECT_TIMEOUT` was static — clients shared across transport instances, harmless today but a footgun the moment per-instance proxy/TLS config lands. Fix: instance field; same per-duration caching within an instance. Verified by `m10ClientCacheIsIsolatedPerTransportInstance`.
166. **Multipart embedded requests: absolute-form targets make `Host` unnecessary** (RFC 9112 §3.2.2 — an origin server MUST ignore Host for absolute-form). The missing-header concern was a false positive; documented at `encodeOperation` instead of "fixed". Real fixes nearby: status-line regex now accepts `HTTP/2 200` (no minor version) via `HTTP/\d(?:\.\d)?`; dead `next > endPos` condition removed (`indexOfBoundary` already bounds `next < endPos`). Verified by `MultipartHttp2StatusLineTest`.
167. **Validation parity across sibling APIs, centralized where one choke point exists.** `ApplyBuilder` validated `top/skip >= 0` but `NavProperty`/`NavQuery.top()/skip()` silently rendered `$top=-5` — fixed with a shared `requireNonNegative`. `$search` control-char rejection lives in `ContextPath.addQuery` (one place, zero generator churn) rather than in every generated class. `addRef` rejects null/blank target URLs with a message naming the parameter instead of `Map.of`'s NPE. Canonical OData functions `ceiling/floor/round` added to `NumberExpression`; `CollectionPage.spliterator()` reports SIZED.
168. **Write generator APIs against their calling context, not in isolation.** Three rework cycles came from inventing `OperationGenerator` helpers (`outputPackage`, accessor embeddables, owner-lookup) before knowing who calls them — `ContainerGenerator` needs full method SOURCE (signature+body), `Generator` needs file packages, and both need import lines. Define each public helper's caller and return shape BEFORE creating the file; when the owner schema must survive resolution, return a pair record (`Owned<T>`) directly instead of an IdentityHashMap side-channel (records use value equality — identical-valued models from different schemas would collide as map keys).
169. **Test fixture wiring must mirror production wiring, or assert against derived fallbacks knowingly.** The container-import package assertion failed only because the test built a single-arg generator (no defaultBasePackage) while real `Generator` passes one — so imports resolved through namespace-derived fallback packaging, correctly. When output depends on fallback chains, either construct with the same arguments production uses or compute expectations through the same chain.
170. **Function-parameter legality is metadata knowledge: validate at generation time.** Only Edm primitives and enums can be embedded as invocation-path literals; structured/collection-typed function parameters must fail generation with the import and parameter named — otherwise users get runtime HTTP 400. Actions accept ANY parameter type because parameters serialize into a JSON body instead of the URL. **Amended twice:** collection parameters of primitive/enum ELEMENTS are supported via parameter aliases (amendment 3), and structured parameters — singles and collection elements — are supported via JSON parameter aliases (amendment 4); the lesson's true core is "validate what can be a literal at the level you know it": the element kind is what matters; the collection wrapper and the complex value each have a spec-defined transport (inline element literals in brackets; JSON alias values).
171. **A result-shape distinction the runtime can't infer must be baked in by the generator.** Per the OData v4 JSON format, entities arrive at the JSON root but complexes/enums/primitives arrive value-wrapped (`{"@odata.context":...,"value":x}`). A single `invokeSync` cannot serve both — an entity may legitimately have a property named `value`, so runtime-side sniffing is ambiguous. The generator knows the result kind at generation time; it routes complexes/enums to `invokeComplex*` (which unwraps) and entities to `invokeSync` (which doesn't). Same principle as decision 52: don't discard metadata the runtime needs.
172. **Spec-conformant fixtures, not hand-rolled ones.** Every operation-response mock in the original suite omitted `@odata.context`, so `deserializePrimitive`'s `size()==1` unwrap looked correct — real services always include the annotation and every primitive-valued invocation failed. Test fixtures must mirror the SPEC's wire shape (control annotations included), not the minimal shape the implementation happens to handle.
173. **Compile the shapes your metadata doesn't have.** TripPin's imports are `GetNearestAirport(double, double)` and a parameterless action — the safest possible shapes. Missing parameter-type imports, a garbage `String_` type, and the unimported `CollectionPage` all generated silently because no compilation test covered enum/structured/collection parameters. `OperationImportsHostileParamsCompilationTest` generates a synthetic client with every hostile shape and compiles it — the referee content tests can't replace.
174. **Wire names vs Java identifiers is a two-way contract.** The generator sanitizes `class`/`First-Name`/`A-B` for Java but MUST emit the CSDL name on the wire — function URL pairs, action JSON keys (already correct), and enum literals (new `ODataEnumValue.wireName()` with a `name()` fallback for older enums). Any new emission path that stringifies a metadata name must ask: is this a Java identifier or a wire token?
175. **Per-module test runs resolve inter-module deps from ~/.m2, which can be stale.** Generated-code compile/load harnesses must prepend the sibling `odata-codegen-runtime/target/classes` (reactor output) to their classpath — a stale installed snapshot silently lacks new runtime types (`ODataEnumValue`) and turns a green suite red for harness reasons, not product reasons.
176. **Same-name operations are overloads, not ambiguity — enumerate what the protocol can still disambiguate by before failing loudly.** The "one ambiguity policy" treated ANY same-name function match as a generation error, but OData identifies an unbound function overload by its parameter names — that is precisely how the server differentiates `IsSiteAdmin(username=…)` from `IsSiteAdmin(userId=…)` — and bound/unbound same-name pairs coexist legally. Loud failure is correct only for genuinely indistinguishable references: cross-namespace simple names and overloads identical in parameter names AND types (ODATA-500 — the ordered parameter TYPES participate in overload identity too, and binding types for bound ops; initially over-rejected, fixed by lesson 179's amendment). Found by a user against real metadata; every synthetic corpus (TripPin/Northwind/OData Demo) lacked overloads, so no test could catch it.
177. **"Cannot be embedded inline" is not "cannot be passed" — check the protocol's escape hatches before rejecting.** Collection function parameters were rejected because a collection literal cannot sit in the invocation path — but OData defines parameter aliases for exactly that shape (`Name(param=@p)?@p=[…]`), and the runtime already had every ingredient (query options + per-type literal formatting). The rejection survived multiple review rounds because the error message was confident and a test asserted the throw — a pinned limitation looks identical to a correct behavior from the outside. Before failing generation on a wire-format limitation, enumerate the spec's alternative transports for that value shape (aliases for collections, `$value` for raw media, `Prefer` headers for control).
178. **Per-entity resolution over large metadata must be O(1) per call, or cache it.** Bound-operation resolution runs once per entity type; walking the base chain via a linear scan of ALL entity types per ancestor made 10-schema × 1000-entity generation O(entities²) and blew the perf budget. Fix: index bound candidates by binding-qualified name ONCE per generator instance, cache ancestor chains and entity lookups — and hoist the per-entity generator construction to the request-generator instance so caches persist across entities. Large-metadata perf tests are the only guard for this class of regression; keep them wired into the suite.
179. **When a loud failure's justification is a memorized spec claim, verify the claim against the spec before trusting the pinned test.** Lesson 176 fixed overload ambiguity by parameter names and pinned the conclusion in a test; the user's next bug was that same check rejecting LEGAL metadata — ODATA-500 (accepted into CSDL) makes the ordered parameter TYPES part of overload identity, and ODATA-425 makes the BINDING TYPE part for bound operations (one bound action per binding type). Three rules: (a) every "these are indistinguishable" claim must enumerate the axes the protocol actually resolves over — for operations that is binding type + ordered qualified parameter types + parameter names; (b) when a lesson encodes a spec-derived policy, cite the ISSUE/spec clause, not the reasoning — ODATA-500's text immediately falsified the names-only identity; (c) audit the mirrored path in the same pass (the unbound-import identity had the same over-broad check and the same pinned test). Typedefs resolve to underlying types in the identity — stricter than spec, honest about what the URL renderer can express.
180. **An early return added to one branch of a shared-epilogue method must audit the sibling branches' fall-through.** The keyed-nav overload (decision 95) closed the collection method inline and `return`ed early — but the sibling paths (keyless-target collection navs, unresolvable targets) still fell through to the shared `sb.append("    }\n\n")` epilogue, emitting a stray `}` after the already-closed method. Every entity in TripPin/Northwind/OData Demo is keyed, so all compile-harness tests passed — keyless entity types existed in no corpus. Fix: the collection branch returns unconditionally after closing itself; the shared epilogue now serves only the single-nav branch. Verified by `RequestGeneratorKeyedNavTest.keylessCollectionNavEmitsNoStrayBrace`. Rule: when a generator method mixes per-branch completion with a shared epilogue, make each branch fully own its output (close AND return) — or drop the epilogue entirely.
181. **Import ambiguity is a per-file problem — resolve references before printing imports, and never let first-wins decide.** Same-named types from different output packages (per-schema `schemaPackages`, or namespace-derived fallback packages) double-import into every generated file that references both, and javac rejects the unqualified references with cascading errors that look like unrelated bugs (one report surfaced as "protected field access across packages" — it was cascade noise). Fix shape: collect ALL of a file's import-candidate FQNs first, resolve simple→reference with `TypeRefs.resolve` (a simple name claimed by more than one type sends EVERY claimant to fully-qualified references with no imports — deterministic, order-independent), then emit imports only for simple references. The traps: (a) content assertions like `import app.entity.A;` pass green while a DIFFERENT file (request, container) still double-imports — the compile harness with a container exposing BOTH same-named types is the referee; (b) record-boolean orderings in test fixtures silently turn concrete subtypes abstract, gutting the path under test (this round's fixture had `abstractType=true` by accident — the with*/navWith copy code never generated and the "coverage" was vacuous until the generated file was actually read).
182. **A shared escape hatch must compose with the fluent API, and a per-file resolver must reach EVERY emission path.** Round-7 review found three follow-ups to the contested-reference work (lesson 181): (a) `ComplexTypeGenerator` never `typeRefs`-populated, so complex files still double-imported same-named cross-schema nav/property types — the shared hooks read the map but it stayed identity; (b) `generateFilterableNavPropertyField` printed the element class via raw `resolvedClassName` instead of `refFor`, so the Filterable inner class disagreed with the rest of the file under contention; (c) `NavQuery.raw()` short-circuited `toODataExpand()` and silently discarded any chained `select`/`expand`/`filter`. Fixes: `typeRefs` populated in `ComplexTypeGenerator.generate()`; `addNavImports` made contested-aware; `collectPropertyTypeFqns` promoted to a shared protected helper; `generateFilterableNavPropertyField` routed through `refFor`; `raw()` now seeds the root path (the raw string becomes `edmName`) so options render. Rules: when you add a cross-file reference resolver, grep for the OLD raw-name emission (`resolvedClassName`) in the shared base, not just the generator you were editing; and a raw escape hatch that composes into a builder must compose forward, never swallow the fluent tail. Verified by `CrossSchemaSimpleNameCompilationTest` (+ complex `Info` navigating both same-named `A`s) and `NavPropertyExpandTest.navQueryRawComposesWithOptions`.
183. **Resolve names by the EXACT namespace, never a simple-name-keyed map — and a shared resolver must cover typedef chains and base types too.** Round-8 review found the TypeRefs work still leaked in three places: (a) nav-target candidates computed FQNs from the UNRESOLVED type, so a typedef nav target registered `<pkg>.entity.<Typedef>` (a class that is never generated) while the emitted field types referenced the resolved UNDERLYING class — candidates and emissions keyed on different names, so the contested check never fired; fix: one shared `navTargetFqn` (typedef chain resolved, primitives → null) used by candidates, imports, and the self-reference skip in BOTH entity and complex generators. (b) The `extends` base was imported/emitted by simple name outside the resolver — a base simple name contested by any other reference double-imported or left an ambiguous `extends`; worse, `extendedBasesForSchema` (final-vs-class) consulted `entityNamespace`, a map keyed by SIMPLE class name that holds ONE namespace when two schemas declare same-named bases — the base got registered under the wrong schema, emitted `final`, and javac said 'cannot inherit from final Base'. Fix: resolve the base's authoritative qualified name by the WRITTEN qualified form (aliases are parse-resolved) or an IDENTITY scan of effectiveSchemas, and route the base FQN through TypeRefs like every other reference. Rule: a map keyed by simple name is fine for same-name-equals-same-thing domains, but split-merge metadata is precisely the domain where it isn't — anything feeding package/suffix decisions must use the exact owning schema. (c) `raw()` seeding the root path still produced INVALID OData when the raw string already carried an option group (`A($expand=x)($top=1)`); fix: detect the trailing top-level paren group (backward scan — nested lambda parens don't confuse it) and MERGE chained options with `;`, preserving verbatim output when nothing is chained. Verified by `TypedefNavAndContestedBaseCompilationTest` 3 and `NavPropertyExpandTest` +3; reactor 784 offline / 908 live.
184. **Distinguish the two jobs a 'keyword list' does: validating user-supplied PACKAGE names needs hard keywords only, sanitizing generated IDENTIFIERS wants the broad set — and every keyword list must be complete against JLS 3.9.** The correctness batch rejected package segments via the identifier-sanitization set, which contains contextual keywords (record, var, to, open, module directives, when): `package com.record;` compiles, and `toPackageName` LOWERCASES schema namespaces, so legal metadata (namespace `Record`, `To`) suddenly aborted generation. Meanwhile `continue` — a hard keyword — was missing from the sanitization set entirely, so a CSDL property named `continue` emitted an illegal field name. Fixes: `Names.isHardJavaKeyword` (JLS 3.9 + literals) for `validatePackage`; `continue` added to `isReservedWord`; the two methods' javadocs state which job each serves. Relatedly, making something COMPILE is not the same as making it CORRECT: the same batch resolved SchemaInfo's cross-kind `Foo.class` ambiguity to FQN references while leaving three `classes.put("NS.Foo", ...)` with one key — the HashMap silently kept the last, misrouting polymorphic deserialization. A registry keyed by metadata names must detect duplicate keys and fail loudly naming the key. And a hostile IMPORT name (`A"B\C`, legal XML attribute) still emitted an unescaped invocation segment one file over from the fixed sites — grep every emission of a name class, not just the file you are editing. Verified by `GeneratorCorrectnessTest` +3, `SchemaInfoCrossKindTest` 2, `ContestedPropertyTypeTest` (referee), `OperationImportsHostileParamsCompilationTest` (hostile import names); reactor 795 offline / 919 live.
185. **The `_` keyword (Java 9) hides in EVERY name-mapping path, and renaming one site relocates the collision instead of fixing it.** Review caught `_` missing from both keyword sets: `validatePackage("com._")` passed while `package com._;` does not compile, and `toJavaFieldName("_")` short-circuited to the illegal lone underscore. Fixing it surfaced two follow-ons: (a) renaming the FIELD to `__` made the auto-allocated CONSTANT (`toConstantName("_")` → `__`) collide with it exactly — constants were deduped only against constants, never against field names, which is invisible for ordinary corpora because constants are upper-case and fields camel-case; fix: constant allocation seeds its used-set with the generated field names. (b) The new guards in `toPackageName`/`toConstantName` initially reused the BROAD reserved set, renaming the LEGAL derived package `record` to `record_` — the same hard-vs-contextual mistake as lesson 184, reintroduced one method over; package/constant derivation renames only HARD keywords. Verified by `NamesLowTest` +2, `GeneratorCorrectnessTest` +2 (incl. javac referee on a `_`-named property); reactor 799 offline / 923 live. The repeated javac harness (`compiles()` + `findClasspathJars()`, ~60 lines) was extracted into the shared test utility `CompilationHarness` — the next referee imports it instead of copying a fourth time. Two sharpenings from re-review: (1) the constant/field seeding fix is NOT an `_` corner case — digit-leading properties (`2FA` → field `_2FA` + constant `_2FA`) had the SAME exact duplicate in every generated file, pinned green by a content-only test that never compiled its output; the seeding is a live uncompilable-output bug fix, and the constant rename (`_2FA_2`) is safe precisely because the old output never compiled — no working code can depend on the old name. (2) The old assertion then passed VACUOUSLY post-fix: `contains("…_2FA")` is a substring of `_2FA_2`. When an identifier gets renamed, the pin must assert the FULL token including the assignment (`"…_2FA_2 ="`) plus a assertFalse on the old name — a substring assertion on a renamed identifier pins nothing. Also absorbed `HostileNamesCompilationTest` (the fourth harness copy, an older variant) onto `CompilationHarness`, which closes its `StandardJavaFileManager`, pins `CLASS_OUTPUT` under the generation root (javac's unpinned default is CWD-dependent — never let .class trees land in the repo), and caches `~/.m2` walks per JVM with a MISSING sentinel: `ConcurrentHashMap` rejects null values, so caching absent artifacts as null NPEs on exactly the fresh-checkout case (no runtime snapshot installed — lesson 175) the harness's sibling-classes fallback exists for.
186. **Base-chain walks need three coordinated fixes — cycle guards, own-wins merge with pairwise conflict checks, and exact namespace maps — and review claims about them must be execution-verified.** (a) Every recursive/looping base-chain walker (`openTypeResolved`/`rootOf`/`resolvedKeys`/`inheritedProperties`/`inheritedNavs` in both type generators, six walkers in `RequestGenerator`, `ancestorQualifiedNames` in `OperationGenerator` — the last loops appending forever, worse than SOE) now carries an identity-keyed visiting set (`IdentityHashMap`-backed: records have value equality, equal-valued distinct types must not false-positive) plus `IllegalStateException` naming the type; the string-keyed set in `ancestorQualifiedNames` is deliberate (that walk is linear over qualified-name strings). (b) `allProps = inherited + own` duplicated redeclared members into `Filterable`/`with*` — and the compile referee caught the same duplication one file over in request nav/`$ref`/keyed-overload methods (`resolvedNavs`/`resolvedStreamProps` merged too). Same-shape redeclares merge own-wins; incompatible ones (type, nullability, collection-ness) fail loudly — and the check runs pairwise root-down the whole chain, because the merged inherited list hides mid-chain pairs (`Base1`/`Base2` collapse rootmost-wins). Conservative by design: legal 4.01 narrowings also fail loudly until narrowing is supported. (c) Simple-name-keyed namespace maps (`entityNamespace`/`complexTypeNamespace`, last-wins) are now `IdentityHashMap<Model, String>` + `namespaceOf()` for the open-root lookup. Two review-hygiene rules paid off: message precision was settled by EXECUTION, not argument — the claim that nullability-only redeclares 'would compile' died to one javac run (`getLabel() ... cannot override ... Optional<String> is not compatible with String`), and the message now cites both the CSDL narrowing-only rule and the Java mechanism; and red-count honesty — every new test was observed failing pre-fix (the two conflict tests via temporary check disable), because a quoted-but-wrong count as TDD evidence is worse than none. Verified by `BaseChainCycleTest` 4 + `BoundAncestorCycleTest` 1 (timeout-bounded) + `RedeclaredMemberTest` 6 + `OpenRootNamespaceTest` 1 + `RequestGeneratorBaseParityTest` 1 (unqualified cross-schema `findBase` parity, same region); reactor 812 offline (357 core) / 936 live.
