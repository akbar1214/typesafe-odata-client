# Release Notes

## 0.1.0-SNAPSHOT (Development)

### Current branch status

The current branch is a development snapshot. The following is the API and behavior to use when reading the generated client:

- Generated model packages are `entity`, `complex`, and `enums`.
- Generated request packages are `entity.request` and `collection.request`; their source directories are `entity/request` and `collection/request`.
- Function and action request classes are in `operation`.
- The per-output-package registry is `schema/SchemaInfo.java`, exposed as `SchemaInfo.INSTANCE`.
- Keyed entity sets use container overloads such as `client.people("scottketchum")`; composite keys use one argument per key component. Keyed navigation overloads are available on entity requests, for example `client.people("scottketchum").trips(2)`.
- Collection requests expose `create(entity)`. Entity requests expose `get()`, `put(entity)`, `patch(entity)`, and `delete()` plus their ETag variants.
- `count()` returns a collection request that adds `$count=true`; `countValue()` is the terminal count-only operation and returns a `long`.
- Navigation constants are `NavQuery` and `NavCollectionProperty` values. Request `expand(...)` accepts `Expandable` values, including selector-lambda forms.
- Entity and collection read/CRUD methods are synchronous. Runtime transport and batch APIs are asynchronous, and generated single-result operation requests may also expose `executeAsync()`.
- The StAX parser accepts OData v4 CSDL only. OData v3 and unknown document roots fail loudly.
- `JdkHttpTransport` is the only bundled transport implementation; applications can provide a custom two-method `HttpTransport`.

### Current supported behavior

- CSDL parsing uses the JDK StAX API and produces immutable model records with parser warnings.
- Generated code supports entity and complex-type inheritance, keyed single and composite access, typed filters, scalar/enum/collection query properties, nested expand options, polymorphic expands, operations, changesets, ETag-aware writes, media streams, and open-type dynamic properties.
- `$filter`, `$select`, `$expand`, `$orderby`, `$top`, `$skip`, `$count`, `$search`, and `$apply` are available where the generated request shape supports them. `ApplyExpression` includes aggregation and `compute` transformations.
- The default `JacksonSerializer` writes OData temporal values as ISO 8601 strings, preserves service offsets, uses generated enum wire names, and omits lifecycle metadata and empty collections from ordinary write bodies.
- Batch requests use `multipart/mixed`, support atomic changesets, correlate response parts by Content-ID, preserve binary bodies, and expose typed response views.
- Typed HTTP exceptions include the structured `ODataError` parsed from an error response.

### Bug fixes in this release

- Circular `TypeDefinition` chains now fail generation with a clear error instead of overflowing the stack.
- Batch operation URLs carrying valid system query options (`$skiptoken`, `$compute`, `$deltatoken`, `$index`) are no longer mistaken for unresolved Content-ID references; references are resolved in the request path only.
- The Content-ID echo check canonicalizes both sides, so a service echoing `<1>` for `1` no longer fails the batch.
- Valid WKT `Polygon`/`MultiPolygon` geography literals are accepted again (the ring validator counted rings instead of positions).
- Symlinked `metadataFile` sources are hashed with links followed, so they no longer fail the build.
- A corrupt (for example backslash-containing) generation-manifest entry degrades to a clean regeneration instead of failing every subsequent build.
- Percent-encoded dot-segment traversal (`%2e%2e`) is rejected in request URLs.

### Current known limitations

- Cancellable streaming is not implemented. A stream returned by `HttpTransport.stream(...)` or a generated media read is caller-owned and must be closed.
- Generated media uploads buffer the complete input stream into memory; there is no streaming upload body publisher.
- Spatial and geography Edm types are represented as `Object`; typed geography/geometry models and spatial query constants are not implemented.
- Collection-bound operations are not emitted as typed request accessors.
- Copy-on-write `with*()` methods are optional and disabled by the Maven plugin default; builders and typed setters remain available.
- Generated entities expose public setters and no-argument construction for Jackson. Use builders or generated `with*()` methods when copy-on-write and tracked partial-PATCH behavior are desired.
- A custom serializer must honor the generated model annotations and OData JSON conventions. No Gson or JSON-B implementation is bundled.
- The parser does not support OData v3 metadata.

### Future milestones

- Cancellable streaming and streaming media uploads
- Typed spatial/geography model support
- Collection-bound typed operations
- Automatic page iteration and generated asynchronous CRUD variants
- Maven Central publication

### Testing status

Fixed test totals are intentionally not recorded in this development snapshot because the branch changes alter the suite. Use `mvn test` for the hermetic offline suite and `mvn test -Plive-tests` when the public-service tests are intentionally included.

## Historical release record (superseded where noted)

!!! warning "Historical entries"
    The sections below preserve earlier implementation and review snapshots. They are retained for context, not as current API documentation. Where an old snapshot conflicts with the current-branch section above, the current section wins.

### Review Round 6 — Spec Conformance (TDD) — historical

**Wire-format fixes recorded in that round:**

- Temporal values were changed to serialize as ISO 8601 strings for `Edm.DateTimeOffset`, `Edm.Date`, `Edm.TimeOfDay`, and `Edm.Duration`; offsets are preserved on read. Entity bodies, action parameter bodies, and structured parameter aliases use the OData mapper.
- Derived entity and complex types emit `@odata.type` values carrying the qualified subtype name; partial PATCH bodies retain `@`-prefixed control annotations.
- Enum members serialize using their CSDL wire names, matching the generated `fromJson(...)` read path.
- `Edm.Byte` maps to `Short` because it is unsigned.

**URL fixes recorded in that round:**

- Bound operations were changed to use namespace-qualified invocation names. The earlier unqualified request shape was identified as the cause of a misleading live-service error.
- `nextPage(...)` preserves nested expand options in continuation links.
- `CollectionProperty.contains(v)` renders a collection `any(...)` predicate and `length()` renders the collection count path.
- Numeric literals use OData numeric forms, including `INF`, `-INF`, and plain `BigDecimal` text.

**Generated-request fixes recorded in that round:**

- Entity `toBatchOperation()` includes the request's current `$select` and `$expand` options.
- `patchToBatchOperation(entity)` follows tracked partial-PATCH fields and has a conditional overload accepting an ETag.
- Collection `top(...)` and `skip(...)` reject negative values.

### Review Round 3 — Correctness & Hardening — historical

**Correctness fixes recorded in that round:**

- Enum members without an explicit numeric value use the preceding value plus one.
- `@JsonProperty` setters are emitted on abstract base types so concrete subtype deserialization retains base fields.
- One aggregate `SchemaInfo` is generated per output package instead of one registry per schema.
- Polymorphic `@odata.type` deserialization uses the generated registry for entities and collection elements.
- Generated enums map JSON numbers by CSDL value rather than Java ordinal; strings continue to map by name.
- PATCH and GET tolerate empty response bodies, and continuation-token decoding preserves literal `+` characters.
- GUID filter literals are unquoted, and generated key literals are formatted from the resolved Edm type.
- Query parameters render after all path segments; division uses operand type; temporal literals are validated against the OData grammar.
- Tracked `changedFields` from builders and copy-on-write methods drive partial PATCH bodies.
- Batch decoding propagates Content-ID correlation, rejects malformed multipart responses, anchors delimiters to line starts, rejects injected line breaks, accepts quoted boundaries, supports `continue-on-error`, and raises typed outer-response errors.
- The parser resolves aliases, validates required attributes, merges container inheritance, parses v4 referential constraints, and tolerates whitespace around type references.
- Request generators include inherited navigation members, inherited stream members, and inherited `HasStream` behavior on subtype requests; generated names avoid runtime-class collisions and constant collisions are detected or deterministically disambiguated.

**Build and ecosystem fixes recorded in that round:**

- Apache-2.0 license metadata and documentation were added.
- Live-service tests were tagged and excluded from the default hermetic test run.
- The build moved to Java 17 release compatibility, reproducible-build metadata, a Maven wrapper, per-execution incremental markers with stale-file cleanup, and metadata-header support.
- The former duplicate transport implementation was removed; the current bundled transport is `JdkHttpTransport`.
- Interceptor chains are cached per `Context`; interceptor failures complete futures exceptionally; retry information and structured error details are exposed.

### Generated `SchemaInfo` Registry — BREAKING (historical decision)

- The current registry class is `<basePackage>.schema.SchemaInfo` and exposes `SchemaInfo.INSTANCE`.
- Earlier snapshots used a different registry class name. Generated request classes already pass the current registry internally, so most users do not need to change application code.
- The registry maps fully qualified CSDL type names to generated classes and is used for polymorphic reads.

### Keyed Accessor API — BREAKING (historical decision)

- Keyed container overloads return entity requests directly: `client.people("russellwhyte")` and `client.order_Details(orderId, productId)`.
- Keyed collection-navigation overloads return entity requests directly: `client.people("russellwhyte").trips(2)`.
- The former collection-request keyed accessor family was removed. The migration is to use the keyed container overload for an entity set and the keyed navigation overload on its containing entity request.
- Key literals remain type-driven; composite and inherited keys use generated parameters.

### Bound Operations (historical decision)

- Operations bound to an entity type or an ancestor generate request classes and typed accessors on the entity request ecosystem.
- Ancestor-bound operations include a type-cast segment; binding parameters are excluded from invocation parameters.
- Overloads are distinguished by binding type and ordered resolved parameter types, with deterministic generated suffixes where needed.
- Return handling follows the same optional, list, typed-result, and polymorphic-read rules as operation imports.

### Function/Action Imports (request-object style) — historical

- Unbound container imports generate final request classes in the `operation` package, with one typed container accessor per import.
- Functions use GET with typed URL literals; actions use POST with a JSON parameter body keyed by original CSDL parameter names.
- Same-name unbound functions are represented as overload sets when their parameter names and ordered types differ. Overloads that cannot be distinguished fail generation.
- Collection and structured function parameters use OData parameter aliases. Primitive collections render bracketed alias values, while structured values render JSON aliases.
- Structured single parameters and structured collection elements were initially rejected, then added to the generator through the same JSON-alias mechanism.
- Type-safe polymorphic expands use generated cast constants. Raw navigation expressions remain available for advanced cases and compose with chained options.
- Keyed entity requests expose `select(...)` and `expand(...)`; collection-only options remain on collection requests.

### Core Features — historical

- The CSDL parser is StAX-based and targets OData v4. An earlier snapshot described broader namespace tolerance; the current parser rejects v3 and unknown roots.
- The code generator emits entity, complex-type, enum, request, container, operation, and schema-info sources.
- The runtime provides context, typed query expressions, HTTP transport, serialization, paging, batch, and typed errors.
- The Maven plugin exposes the `generate` goal for the `generate-sources` phase.

### Type-Safe Queries — historical

- Scalar property constants cover strings, numbers, booleans, temporal values, GUIDs, and enums; collection-valued properties use collection builders.
- Logical composition supports `and`, `or`, and `not`.
- Collection `any` and `all` use generated target `Filterable` views.
- Every property expression supports `asc()` and `desc()`.
- `FilterExpression<E>` rejects unrelated entity properties while allowing base-type predicates on subtype collections.
- `PropertyExpression<E, T>` is shared by select and order expressions.
- `NavQuery` and collection navigation constants provide nested expand options.

### Inheritance (Entity and Complex Type) — historical

- Entity and complex `BaseType` declarations become Java `extends` clauses.
- Keys, properties, getters, navigation members, and property constants resolve through the full base chain.
- Builders are generated for concrete top-level types; concrete subtypes use copy-on-write methods where enabled.

### Entity Operations — historical

- Entity and collection HTTP GET/POST/PATCH/PUT/DELETE operations are generated.
- `$ref` add/remove methods are generated for eligible navigation properties.
- Media entities and named streams receive request-layer stream and upload methods.
- Open types capture undeclared JSON properties in dynamic-property storage and expose typed conversion helpers.

### Query Operations — historical

- `$filter` uses typed filter expressions.
- `$select` accepts scalar and enum property expressions.
- `$expand` supports nested `NavQuery` options.
- `$orderby`, `$top`, `$skip`, `$search`, and `$apply` are generated where the request shape supports them.
- `$count=true` is represented by `count()` and the count-only endpoint by `countValue()`.

### HTTP Transport — historical

- The runtime transport contract has two asynchronous methods: `submit(HttpRequest)` and `stream(HttpRequest)`.
- `JdkHttpTransport` is the current bundled implementation and uses the JDK `HttpClient` with native PATCH support.
- Custom `HttpTransport` implementations remain supported through the same interface.
- A former duplicate transport was removed; it is not a current option.

### Serialization — historical

- `JacksonSerializer` is the default implementation used by generated model annotations.
- The `Serializer` interface supports serialization, deserialization, and optional field-filtered serialization for partial PATCH.
- Generated entities use `@JsonProperty` setters; replacement serializers must honor those annotations and the OData wire conventions.

### Batch Support — historical

- The runtime supports `multipart/mixed` batch requests and changesets.
- Standalone and changeset operations cover GET, POST, PATCH, PUT, and DELETE.
- Batch execution has synchronous and asynchronous runtime entry points.
- Responses expose ordered results, Content-ID lookup, related IDs, and typed views.

### Error Handling — historical

- Typed exceptions cover common 4xx statuses, 428, 429, and 5xx responses.
- `ODataException.fromResponse(HttpResponse)` carries the parsed `ODataError`.
- ETag support and the interceptor chain are part of the runtime request path.

### Testing — historical

Earlier snapshots included fixed test totals and per-module counts. Those numbers are retired because the active branch changes the suite. The current verification commands are:

```bash
mvn test
mvn test -Plive-tests
```

### Earlier known limitations — historical

Earlier snapshots recorded the following limitations. The current list above supersedes this record:

- Cancellable streaming and streaming media uploads were unavailable.
- Spatial values were not mapped to typed geography/geometry models.
- Typed open-type accessors were not available.
- Fixed concurrency metadata was not used to drive ETag behavior automatically.

## Future milestones — historical

- Cancellable streaming support
- Typed open-type and spatial accessors
- Collection-bound typed operations
- Automatic page iteration and generated asynchronous CRUD variants
- Maven Central publication
