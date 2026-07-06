# Release Notes

## 0.1.0-SNAPSHOT (Development)

### Milestones 1-6 Complete

**Core Features:**

- **CSDL Parser** — StAX-based parser for OData v4 metadata
- **Code Generator** — 7 generators producing 34+ Java files
- **Runtime Library** — Context, query builders, HTTP transport, serialization
- **Maven Plugin** — `odata-client:generate` goal

**Type-Safe Queries:**

- Property types: String, Number, Boolean, Date, Collection
- Logical operators: AND, OR, NOT
- Lambda operators: any, all
- Sort expressions: asc, desc
- String operations: contains, startsWith, endsWith, etc.
- Arithmetic operations: add, subtract, multiply, divide, mod

**Entity Operations:**

- GET: Single entity and collection queries
- POST: Create entities
- PATCH: Update entities (with ETag support)
- DELETE: Remove entities (with ETag support)
- $ref: Add/remove navigation links
- $batch: Batch multiple operations in a single request

**Query Operations:**

- $filter: Type-safe filter expressions
- $select: Field projection
- $expand: Navigation property expansion
- $orderby: Sort results
- $top/$skip: Pagination
- $count: Result counting

**HTTP Transport:**

- JdkHttpTransport (Java 11+ HttpClient, native PATCH support)
- ApacheHttpTransport
- Custom HttpTransport interface

**Serialization:**

- JacksonSerializer (default)
- GsonSerializer
- JakartaJsonBSerializer
- Custom Serializer interface

**Batch Support:**

- multipart/mixed format
- GET, POST, PATCH, DELETE operations in batch
- Async batch execution
- Typed batch results

**Error Handling:**

- Typed exceptions: BadRequest, Unauthorized, Forbidden, NotFound, Conflict, PreconditionFailed, RateLimit, Server
- ETag support for optimistic concurrency
- Retry logic for rate limiting

**Testing:**

- 85 tests passing
- Parser tests: 27 (TripPin + Northwind)
- Generator tests: 2
- Runtime tests: 6
- Batch tests: 16
- Integration tests: 18 (live TripPin service)
- Northwind tests: 16 (live Northwind V4 service)

### Known Limitations

- Complex type inheritance skipped for MVP
- Cancellable streaming not yet implemented

### Future Milestones

- Cancellable streaming support
- Publish to Maven Central
