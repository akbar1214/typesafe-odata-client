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

**Entity Inheritance:**

- Entity types with a `BaseType` emit a real Java `extends` clause (e.g. `Flight → PublicTransportation → PlanItem`)
- `getKey()`, getters, `with*()` methods, and property constants resolve the full base-chain
- `Builder` generated only for concrete top-level entities

**Entity Operations:**

- GET: Single entity and collection queries
- POST: Create entities
- PATCH: Update entities (with ETag / `If-Match` support)
- DELETE: Remove entities (with ETag / `If-Match` support)
- `$ref`: Add/remove navigation links
- `$batch`: Batch multiple operations in a single request

**Query Operations:**

- `$filter`: Type-safe filter expressions
- `$select`: Field projection (any property type)
- `$expand`: Navigation property expansion, **including nested options** via `NavQuery` (`$select`, `$filter`, `$top`, `$orderby`)
- `$orderby`: Sort results
- `$top`/`$skip`: Pagination
- `$count`: Result counting

**HTTP Transport:**

- `JdkHttpTransport` (Java 11+ `HttpClient`, native PATCH support, dedicated executor)
- `ApacheHttpTransport`
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

- **236 tests passing**
- Parser: 47 (TripPin + Northwind + OData Demo metadata)
- Generator: integration (1) + compilation against runtime (1) + composite-key/collection-getter unit (3)
- Runtime: 116 (live TripPin & Northwind integration, query expression, context path, batch, exceptions, transport)
- Generated client: 68 (TripPin, Northwind, OData Demo — including inheritance hierarchies)

### Known Limitations

- Complex type inheritance skipped (entity inheritance is supported)
- Abstract base entity types: generator emits `abstract class` but still generates `with*` methods that instantiate it (latent; no test metadata uses abstract types)
- Cancellable streaming not yet implemented

### Future Milestones

- Cancellable streaming support
- Publish to Maven Central
- Complex type inheritance
