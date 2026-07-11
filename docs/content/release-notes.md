# Release Notes

## 0.1.0-SNAPSHOT (Development)

### Status: Full Pipeline Working

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
- **`PropertyExpression<T>`** — unifies `$select` and `$orderby` across all property types

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

- `JacksonSerializer` (default; uses `@JsonCreator`/`@JsonProperty` on generated entities)
- Pluggable `Serializer` interface for custom (de)serialization

**Batch Support:**

- `multipart/mixed` format
- GET, POST, PATCH, DELETE operations in batch
- Async batch execution
- Typed batch results

**Error Handling:**

- Typed exceptions: `BadRequestException` (400), `UnauthorizedException` (401), `ForbiddenException` (403), `NotFoundException` (404), `ConflictException` (409), `RateLimitException` (429, with `retryAfter`)
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
