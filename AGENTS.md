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

- **Parser tests:** Parse TripPin + Northwind + OData Demo metadata XML, verify model correctness (47 tests)
- **Generator integration tests:** Generate TripPin client, verify file structure and code content (1 test)
- **Generator compilation tests:** Generate + compile TripPin client against runtime JARs (1 test)
- **Runtime tests:** Verify OData collection response parsing, Context URL construction, key formatting (6 tests)
- **Batch tests:** Multipart encode/decode, batch request construction, ContextPath relative URLs (16 tests)
- **Integration tests:** Live TripPin service: collection queries, entity get, navigation, filtering, ordering, select, count, airlines, airports, batch requests, CRUD operations (POST/PATCH/DELETE with ETag), $ref link/unlink (18 tests)
- **Northwind integration tests:** Live Northwind V4 service: categories, products, customers, orders, employees, suppliers, filtering, ordering, select, count, expand (16 tests)
- **Generated client tests:** Type-safe generated TripPin client: collection queries, entity by key, filter, orderBy, select, count, navigation, CRUD, ETag (14 tests)
- **Northwind generated client tests:** Type-safe generated Northwind client: collection queries, entity by key, filter, orderBy, select, count, suppliers, employees (17 tests)
- **OData Demo generated client tests:** Type-safe generated OData Demo client: inheritance, open types, complex types, geography, stream, Guid, Byte, Single, Int64, DateTime (22 tests)
- **Total: 158 tests passing**
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

27. **OData Demo service tests inheritance, open types, geography, stream gaps.** The parser correctly parses `BaseType`, `OpenType`, `HasStream`, and `ConcurrencyMode` attributes, but the generator ignores them. Generated classes for inherited types (e.g., `FeaturedProduct extends Product`) are standalone `final` classes with no inheritance. `Edm.Stream` and `Edm.GeographyPoint` map to `Object`. This is acceptable for MVP — the parser layer is complete, and code generation for these features is a clear future milestone.

28. **OData Demo service IDs start at 0, not 1.** The `Products` entity set has `ID=0` for "Bread". Assertions like `assertTrue(p.getID() > 0)` fail — use `assertNotNull()` or non-zero-specific checks instead.

29. **async-profiler TLAB sampling shows TLAB refill events, not individual small allocations.** With JDK 24, `event=alloc` captures TLAB boundary crossings (typically ~512KB each). Small per-request allocations (HashMap, ArrayList, String) don't individually trigger samples — they're swallowed by the TLAB. To see small allocations, use `event=alloc` with `forkCount=0` (tests in Maven JVM) and look for stacks involving your code at TLAB refill points.

30. **ObjectMapper per-request is the most expensive allocation hotspot.** `RequestHelper.executeAndGetCollection()` was creating a new `ObjectMapper` with module registration on every collection fetch. ObjectMapper initialization cascades into TypeFactory, SerializerProvider, DeserializerProvider, and serializer cache compilation. Fix: static final singleton, thread-safe for concurrent reads.

31. **`StringBuilder.toString().endsWith("/")` in a loop allocates a temp String per iteration.** `ContextPath.appendSegments()` called `sb.toString()` to check trailing slash — this allocates a full String copy on every segment. Fix: `sb.charAt(sb.length() - 1) != '/'` avoids the allocation entirely.

32. **Chained `String.replace()` calls create O(n) intermediate Strings.** `ContextPath.encodeQueryParam()` had 9 chained `.replace()` calls, each scanning the entire string and allocating a new one. Fix: single-pass `StringBuilder` with a `switch` on hex sequences — one allocation, one scan.

33. **`List.copyOf()` defensively copies an already-safe list.** `CollectionPage` constructor called `List.copyOf(currentPage)` which copies all element references into a new array. Since the Jackson-deserialized list is the only reference, `Collections.unmodifiableList()` wraps without copying — same immutability guarantee, zero allocation overhead.

34. **Double JSON parse is a hidden allocation multiplier.** `executeAndGetCollection()` parsed JSON into a `JsonNode` tree via `readTree()`, then called `convertValue()` which re-serializes the JsonNode back to bytes and re-parses into the target type. This is two full JSON parse passes — each allocating parsing buffers, intermediate trees, and string objects. Fix: `readValue(Map.class)` + `convertValue()` on the raw list. The Map tree is cheaper than JsonNode, and `convertValue()` on a raw `List<Map>` avoids the re-serialization step. Result: `executeAndGetCollection` completely disappeared from allocation stacks in post-fix profiling.

35. **`toMultiMap()` creates unnecessary intermediate allocations per request.** `executeAsync()` called `toMultiMap(authHeaders)` which allocated a new `HashMap` + `List.of()` wrappers, then `putAll()` copied into another `HashMap`. Fix: inline the auth header iteration directly into the request headers map with pre-sized capacity. Eliminated one `HashMap` allocation per request.

36. **String concatenation for small JSON bodies wastes an intermediate String.** `addRef()` used `("{\"@odata.id\":\"" + url + "\"}")` which creates an intermediate String then copies to byte[]. Fix: pre-sized `StringBuilder` with known capacity — one allocation, no intermediates.

37. **Remaining allocations after optimization are third-party library internals.** After all fixes, profiler data (5 runs) showed: `RequestHelper.executeSync/executeAsync` appeared only 1 time each; `executeAndGetCollection` was absent entirely. The remaining stacks were: `JacksonSerializer.createMapper` (one-time static init per JVM), `JdkHttpTransport.execute` (JDK HttpClient connection pool internals), and Jackson serialization buffers. These are all unavoidable — Jackson needs buffers for JSON parsing, JDK HttpClient needs connection pools. Our code's allocation footprint is now dominated by libraries, not our own code.

38. **Profiling methodology: use `forkCount=0` + `asprof` attachment for accurate results.** Maven's default `forkCount=1` forks a child JVM for tests, making it hard to attach the profiler. Using `forkCount=0` runs tests in the Maven JVM directly, allowing `asprof -e alloc -i 1000 -d 60 <pid>` to attach cleanly. For JFR-based profiling, use `.mvn/jvm.config` with `-agentpath:...=start,event=alloc,file=output.jfr` which is the most reliable approach. Always run 5+ iterations to get stable data — single runs show too much JVM startup noise.

39. **Profile BEFORE and AFTER to confirm fixes work.** Don't just fix based on code reading — the profiler reveals which allocations actually matter at the TLAB level. For example, `RawFilterExpression.and()` is O(n²) in string concatenation theory, but with typical 1-5 filter clauses it never appears in profiler stacks. Meanwhile, the `ObjectMapper` per-request was invisible in code review but dominant in profiler output.

40. **`EntityGenerator.generateGetter` silently drops collection-typed property getters.** The collection branch (`EntityGenerator.java:230-235`) calls `sb()` which returns a throwaway `new StringBuilder()`, writes the getter to it, then returns `""`. The caller does `sb.append(generateGetter(...))` which appends nothing. Collection properties get a field and a builder setter but no getter. Fix: hoist `StringBuilder sb = new StringBuilder()` to the top of the method and use it for both branches.
    - **FIXED in v0.1.1.** `generateGetter` now hoists `StringBuilder` and emits the getter for collections. Per the "Truly Immutable Entities" decision, collection fields are stored via `List.copyOf(fn)` (null-safe) in both constructors, and the getter returns `Collections.unmodifiableList(fn)`. Verified by `EntityGeneratorCollectionGetterTest` (TDD: failing test first, then fix).

41. **`INDENT_OUTPUT` on wire serialization wastes bandwidth.** `JacksonSerializer.createMapper()` enables `SerializationFeature.INDENT_OUTPUT`, which pretty-prints every POST/PATCH body sent over the wire. This adds ~30% to body size and CPU cost with no benefit. Disable for the wire mapper; keep a separate mapper for debug `toJson()`.
    - **FIXED in v0.1.1.** `INDENT_OUTPUT` removed from the wire mappers (`MAPPER`, `MAPPER_INCLUDE_NULLS`). A separate `MAPPER_PRETTY` (with `INDENT_OUTPUT`) is used only by `toJson()`/`serializeToString()` for human-readable debug output. Verified by `JacksonSerializerTest`.

42. **`CompletableFuture.supplyAsync` on `ForkJoinPool.commonPool()` blocks shared pool.** `JdkHttpTransport.submit()` and `stream()` submit blocking I/O to the common pool with no executor. Under concurrent load this starves other common-pool users. Use a dedicated `ExecutorService` (e.g., cached, IO-bounded).
    - **FIXED in v0.1.1.** `JdkHttpTransport` takes an injected `Executor`; the default constructor uses a dedicated daemon cached pool with named threads (`odata-http-N`). Both `submit()` and `stream()` pass the executor to `supplyAsync(...)`. Verified by `JdkHttpTransportTest` (asserts the request runs on the injected executor, not the common pool).

43. **Auth header + extra header collision throws `UnsupportedOperationException`.** `RequestHelper.executeAsync()` stores auth headers as `List.of(...)` (immutable), then `extraHeaders` calls `computeIfAbsent(...).add(...)`. If any extra header key collides with an auth header key, `.add()` on the immutable list throws. Low probability but a latent bug.
    - **FIXED in v0.1.1.** Auth headers are now stored as mutable `new ArrayList<>(List.of(value))`, so colliding extra headers merge via `computeIfAbsent(...).add(...)` without throwing. Verified by `RequestHelperHeaderTest` (TDD: threw `UnsupportedOperationException` before the fix).

44. **Interceptors are "first wins" — multiple interceptors silently don't chain.** `RequestHelper.executeAsync()` returns the first interceptor's result inside the loop (`return` at line 71). If 3 interceptors are registered, only the first runs. The interface signature `intercept(request, delegate)` allows calling `delegate.submit()` internally, but this design should be documented clearly — or reworked into a proper middleware chain.
    - **FIXED in v0.1.1.** Replaced the early-return `for` loop with a middleware chain built by `RequestHelper.buildTransportChain()`. Interceptors are iterated in reverse order; each wraps the next as an `HttpTransport` delegate. The first interceptor in registration order becomes the outermost wrapper, so all interceptors run. No public interface change to `HttpInterceptor`. Same fix applied to `BatchRequest.executeSync()` and `BatchRequest.executeAsync()`. Verified by `InterceptorChainTest` (TDD: threw `AssertionError` before the fix).

45. **`CollectionProperty.any()` has a no-op `.replace("x", "x").** `CollectionProperty.java:26` calls `.replace("x", "x")` on the filter expression — this does literally nothing. It works only because `FilterableElement.prefix` is hardcoded to `"x"` matching the `any(x: ...)` variable. If the prefix were ever changed, this would silently break.
    - **FIXED in v0.1.1.** The `.replace("x", "x")` no-op was removed. `FilterableElement.prefix` remains `"x"` (matches `any(x: ...)`/`all(x: ...)`). Verified by `CollectionPropertyTest` (locks `Emails/any(x: x/Value eq 'a')` and `Emails/all(x: x/Value eq 'a')`).

46. **Dead `toMultiMap` method after inlining.** `RequestHelper.toMultiMap` (lines 209-215) was inlined per lesson 35 but the method body was never deleted. Dead code — delete it.
    - **FIXED in v0.1.1.** `RequestHelper.toMultiMap` was deleted (it was unreferenced; the `BatchRequest.toMultiMap` is a separate method and untouched). The stale comment in `executeAsync` was also corrected.
