# Release Notes

## 0.1.0-SNAPSHOT (Development)

### Milestones 1-5 Complete

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
- DELETE: Remove entities
- $ref: Add/remove navigation links

**Query Operations:**

- $filter: Type-safe filter expressions
- $select: Field projection
- $expand: Navigation property expansion
- $orderby: Sort results
- $top/$skip: Pagination
- $count: Result counting

**HTTP Transport:**

- JdkHttpTransport (default)
- ApacheHttpTransport
- Custom HttpTransport interface

**Serialization:**

- JacksonSerializer (default)
- GsonSerializer
- JakartaJsonBSerializer
- Custom Serializer interface

**Error Handling:**

- Typed exceptions: BadRequest, Unauthorized, Forbidden, NotFound, Conflict, PreconditionFailed, RateLimit, Server
- ETag support for optimistic concurrency
- Retry logic for rate limiting

**Testing:**

- 44 tests passing
- Parser tests: 27 (TripPin + Northwind)
- Generator tests: 2
- Runtime tests: 6
- Integration tests: 9 (live TripPin service)

### Known Limitations

- Complex type inheritance skipped for MVP
- No batch support yet
- No streaming support yet

### Future Milestones

- Milestone 6: Batch requests
- Milestone 7: Northwind integration tests
- Milestone 8: Publish to Maven Central
