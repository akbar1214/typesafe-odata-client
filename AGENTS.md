# AGENTS.md — Modern OData Client

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
- `StringProperty` has `contains()`, `startsWith()` — not `greaterThan()`
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

### 6b. Entity Navigation Methods Throw UnsupportedOperationException

**Decision:** Entity nav accessor methods (e.g., `person.trips()`) throw `UnsupportedOperationException` instead of returning request objects.

**Reason:** Request classes require `Context` (HTTP transport, serializer, auth) to execute. Entities are pure data models deserialized from JSON — they don't hold `Context`. Throwing an exception with a clear message guides users to the correct API path:
- Use `client.peopleByUserName("scott").trips().get()` (entity request nav)
- Not `person.trips()` (entity nav — no context available)

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

**Decision:** Generated entities have no Jackson/Gson annotations.

**Reason:** The reference's entities are annotated with `@JsonProperty`, `@JsonAnySetter`, `@JacksonInject`, etc. This:
- Forces Jackson on the classpath
- Couples model to serialization library
- Makes it impossible to use Gson or Jakarta JSON-B

**Our approach:** Model is clean POJOs. `Serializer` interface is pluggable:
```java
public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);
    <T> T deserialize(byte[] data, Class<T> type);
}
```

### 9. Maven Plugin (Not CLI Tool)

**Decision:** Distribute as Maven plugin for build-time generation.

**Reason:** Integrates naturally into Java build lifecycle:
- Code generated during `generate-sources` phase
- `build-helper-maven-plugin` adds generated sources automatically
- No separate CLI installation needed
- Version-locked with the project

**Implementation:** `GenerateMojo` accepts `metadataUrl` or `metadataFile`, `basePackage`, and optional `schemaPackages` map. Downloads metadata via `HttpClient`, follows redirects (TripPin requires this), parses with StAX, generates via `Generator`.

### 10. Context-Centric Request Execution

**Decision:** Generated request classes accept `Context` in their constructors and use `RequestHelper` for HTTP execution.

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

---

## Architecture

```
modern-odata-client/
├── odata-client-core/        # Parser + Generator (no runtime deps)
│   ├── model/                # CsdlModel records
│   ├── parser/               # StaxCsdlParser
│   └── generator/            # Names, Generator, EntityGenerator, etc.
├── odata-client-runtime/     # Runtime types (generated code depends on this)
│   ├── entity/               # ODataEntityType, ContextPath, SchemaInfo
│   ├── query/                # Expression hierarchy (StringProperty, etc.)
│   ├── http/                 # HttpTransport, HttpRequest, HttpResponse
│   ├── auth/                 # AuthProvider
│   ├── serialization/        # Serializer interface
│   ├── paging/               # CollectionPage
│   └── batch/                # BatchOperation, BatchRequest, BatchResponse
├── odata-client-maven-plugin/ # Maven plugin wrapper
└── odata-client-test/        # Integration tests
```

---

## Testing Strategy

- **Parser tests:** Parse TripPin + Northwind metadata XML, verify model correctness (27 tests)
- **Generator integration tests:** Generate TripPin client, verify file structure and code content (1 test)
- **Generator compilation tests:** Generate + compile TripPin client against runtime JARs (1 test)
- **Runtime tests:** Verify OData collection response parsing, Context URL construction, key formatting (6 tests)
- **Batch tests:** Multipart encode/decode, batch request construction, ContextPath relative URLs (16 tests)
- **Integration tests:** Live TripPin service: collection queries, entity get, navigation, filtering, ordering, select, count, airlines, airports, batch requests, CRUD operations (POST/PATCH/DELETE with ETag), $ref link/unlink (18 tests)
- **Northwind integration tests:** Live Northwind V4 service: categories, products, customers, orders, employees, suppliers, filtering, ordering, select, count, expand (16 tests)
- **Generated client tests:** Type-safe generated TripPin client: collection queries, entity by key, filter, orderBy, select, count, navigation, CRUD, ETag (14 tests)
- **Northwind generated client tests:** Type-safe generated Northwind client: collection queries, entity by key, filter, orderBy, select, count, suppliers, employees (16 tests)
- **Total: 116 tests passing**
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

10. **Complex type inheritance is hard to generate correctly.** Subclasses need `super()` calls for inherited properties, constructors must chain properly, and builders can't hide parent methods. Skipped for MVP — TODO for future milestone.

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
