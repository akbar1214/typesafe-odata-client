# Release Notes

## 0.1.0-SNAPSHOT (Development)

### Status: Full Pipeline Working

### Review Round 3 — Correctness & Hardening (all 79 findings resolved)

**Correctness fixes (highlights):**

- CSDL enum members without `Value` default to previous+1 per spec (were member-count → wrong wire values)
- `@JsonProperty` setters on abstract base types — subtype deserialization no longer drops base fields
- One aggregate `ServiceSchemaInfo` per output package (multi-schema registries no longer overwrite)
- Polymorphic `@odata.type` deserialization via the `SchemaInfo` registry, per element for collections
- Enums map JSON numerics by CSDL value (`@JsonCreator`), not ordinal; strings keep mapping by name
- PATCH/GET tolerate 204/empty bodies; `nextLink` decoding no longer corrupts `+` in continuation tokens
- Unquoted GUID filter literals (`GuidProperty`); type-driven key literals (`addKey(name, value, edmType)`)
- Query parameters render once after all segments (no `?` mid-URL); `div` vs `divby` by operand type;
  datetime literals validated against the OData ABNF with typed overloads
- Partial PATCH: `changedFields` tracked by `builder()`/`with*()` now produce partial update bodies
- Batch: part-level `Content-ID` correlation (`getByContentId`), batch-wide numbering, loud failures on
  malformed responses, RFC 2046 line-anchored delimiters, CRLF-injection rejection, quoted boundaries,
  `continue-on-error` preference, typed errors for non-2xx
- Parser: alias resolution, required-attribute validation, container `Extends` merging, v4 referential
  constraints, whitespace-tolerant type refs
- Generators: inherited navs/streams/`HasStream` on subtype requests, enum `@JsonCreator`, runtime-class
  name shadowing, constant auto-dedup (`VALUE_2`) with loud field-collision errors

**Build & ecosystem:**

- Apache-2.0 `LICENSE`/`NOTICE` + POM metadata; docs rewritten to the real API
- Live-service tests tagged and hermetic by default (`mvn test` offline; `-Plive-tests` for everything)
- `<release>17</release>` (caught a latent Java 21+ `List.getFirst()` usage), reproducible-build timestamp,
  Maven wrapper, per-execution incremental markers with stale-file cleanup, `metadataHeaders` auth support
- Dead `JavaNetHttpTransport` removed; `JdkHttpTransport` is the single transport
- Interceptor chain cached per `Context`; interceptor failures complete futures exceptionally;
  `Retry-After` HTTP-date parsing + `hasServerRetryAfter()`; `ODataError` maps `details[]`/`target`

### Keyed Accessor API — BREAKING (decision 95, option A)

- **Keyed container overloads**: every keyed entity set gains `client.people("russellwhyte")`,
  `client.orderDetails(orderId, productId)` returning the entity request directly. Keyless
  entity sets keep only the zero-arg collection accessor
- **Keyed nav overloads**: collection navigations to keyed entities gain `person.trips(2)`
  (renders `People('x')/Trips(2)`) on the entity request; single navs unchanged
- **The `byID`/`byKey` family on collection requests is removed** — `personByUserName(...)`,
  `tripByID(...)`, `advertisementByID(...)` and friends no longer exist. Migrate:
  `client.people().personByUserName("x")` → `client.people("x")`;
  `person.trips().tripByID(2)` → `person.trips(2)`. Key literals stay type-driven
  (decision 52); composite and inherited keys surface as multi-parameter overloads

### Function/Action Imports (request-object style)
- Unbound container imports generate final `<Name>FunctionRequest`/`<Name>ActionRequest` classes
  in `.operation`, with one typed container accessor per import; functions GET with typed URL
  literals (key-predicate formatting), actions POST a JSON body keyed by CSDL parameter names
- **Overloaded functions supported**: OData identifies an unbound overload by its parameter names,
  so each overload generates its own class/accessor (`isSiteAdminByUsername`/`isSiteAdminByUserId`);
  bound same-name siblings no longer read as ambiguity; identical parameter-name sets and same-name
  unbound actions fail generation per spec
- **Collection function parameters supported via parameter aliases**: `Collection(Edm.String)` maps
  to `List<String>` and renders `ByTags(tags=@p0)?@p0=['a','b']` (previously failed generation);
  structured elements still fail loudly (no URL literal form)


**Core Features:**

- **CSDL Parser** — StAX-based parser for OData v4 metadata (handles v3/v4 namespace variations)
- **Code Generator** — Entity, request, container, schema-info generators
- **Runtime Library** — Context, query builders, HTTP transport, serialization
- **Maven Plugin** — `odata-codegen:generate` goal in the `generate-sources` phase

**Type-Safe Queries:**

- Property types: `String`, `Number`, `Boolean`, `DateTimeOffset`, `Enum`, `Collection`
- Logical operators: `AND`, `OR`, `NOT`
- Lambda operators: `any`, `all` (on collection properties)
- Sort expressions: `asc`, `desc`
- String operations: `contains`, `startsWith`, `endsWith`, `equalTo`, `notEqualTo`
- Arithmetic operations: `add`, `subtract`, `multiply`, `divide`, `mod`
- **Generic `FilterExpression<E>`** — cross-entity filters are compile-time errors; base-type predicates type-check against subtypes
- **`PropertyExpression<E, T>`** — unifies `$select` and `$orderby` across all property types; entity-scoped `E` catches cross-entity `select`/`orderBy` at compile time
- **`NavQuery<S, T>`** — nested `$expand` options are type-checked against the source and target entity types

**Inheritance (Entity + Complex Type):**

- Entity types with a `BaseType` emit a real Java `extends` clause (e.g. `Flight → PublicTransportation → PlanItem`)
- Complex types with a `BaseType` also emit `extends` (e.g. `EventLocation → Location`, `AirportLocation → Location`)
- `getKey()`, getters, `with*()` methods, and property constants resolve the full base-chain
- `Builder` generated only for concrete top-level types; subtypes use `with*()` for copy-on-write

**Entity Operations:**

- GET: Single entity and collection queries
- POST: Create entities
- PATCH: Update entities (with ETag / `If-Match` support)
- DELETE: Remove entities (with ETag / `If-Match` support)
- `$ref`: Add/remove navigation links
- `$batch`: Batch multiple operations in a single request
- **Media streams** — `HasStream="true"` entities get `streamMedia()` / `setMedia(InputStream[, etag])` at `.../<EntitySet>(key)/$value`; `Edm.Stream` named properties get `stream<Prop>()` / `set<Prop>(InputStream[, etag])` at `.../<EntitySet>(key)/<PropertyName>`
- **OpenType dynamic properties** — `OpenType="true"` entities/complex types capture undeclared JSON fields into `unmappedFields` (exposed via `getUnmappedFields()` / `getDynamicProperty(String)`) and round-trip them on serialize; `@odata.*` control fields are filtered out

**Query Operations:**

- `$filter`: Type-safe filter expressions
- `$select`: Field projection (any property type)
- `$expand`: Navigation property expansion, **including nested options** via `NavQuery` (`$select`, `$filter`, `$top`, `$orderby`)
- `$orderby`: Sort results
- `$top`/`$skip`: Pagination
- `$count`: Result counting
- `$search`: Free-text search (`search(String)`)
- `$apply`: Server-side aggregation and transformations (incl. `$compute`) via a fluent `ApplyExpression` builder — `groupBy`, `aggregate`, `compute`, `filter`, `orderBy`, `top`, `skip`

**HTTP Transport:**

- `JdkHttpTransport` (Java 11+ `HttpClient`, native PATCH support, dedicated executor)
- `JavaNetHttpTransport`
- Custom `HttpTransport` interface (two methods: `submit`, `stream`)

**Serialization:**

- `JacksonSerializer` (default; uses `@JsonProperty` setters on generated entities)
- Pluggable `Serializer` interface for custom (de)serialization

**Batch Support:**

- `multipart/mixed` format
- GET, POST, PATCH, DELETE operations in batch
- Async batch execution
- Typed batch results

**Error Handling:**

- Typed exceptions: `BadRequestException` (400), `UnauthorizedException` (401), `ForbiddenException` (403), `NotFoundException` (404), `ConflictException` (409), `PreconditionFailedException` (412), `RateLimitException` (429, with `retryAfter`), `ServerException` (5xx)
- `ODataException` base with `fromResponse(HttpResponse)` factory
- ETag support for optimistic concurrency
- Middleware chain for `HttpInterceptor`s

**Testing:**

- **285 tests passing**
- Parser: 47 (TripPin + Northwind + OData Demo metadata)
- Generator: integration (1) + compilation against runtime (1) + composite-key/collection-getter unit (3) + complex-type inheritance unit (3) + abstract-entity unit (3) + media-stream unit (3) + `$apply` unit (3) + open-type unit (4)
- Runtime: 127 (live TripPin & Northwind integration, query expression, context path, batch, exceptions, transport, media `$value` stream/put via mock transport, `$apply` builder)
- Generated client: 90 (TripPin, Northwind, OData Demo — including inheritance hierarchies, live media-stream reads, and OpenType dynamic-property capture + typed `getDynamicProperty(String, Class)`)

### Known Limitations

- Cancellable streaming not yet implemented
- `Edm.GeographyPoint` still maps to `Object` (OData Demo `Supplier.Location`)
- OpenType entities expose dynamic properties only via `unmappedFields` (no typed accessor yet)
- `ConcurrencyMode="Fixed"` is parsed but not yet used to drive ETag behavior beyond the existing `If-Match` support

### Future Milestones

- Cancellable streaming support
- Typed OpenType dynamic-property accessors
- Publish to Maven Central
