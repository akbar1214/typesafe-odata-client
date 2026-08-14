# Code Review: modern-odata-client — Round 3

**Date:** 2026-08-14
**Scope:** Full review of `odata-codegen-core`, `odata-codegen-runtime`, `odata-codegen-maven-plugin`,
`odata-codegen-test`, POMs, docs, and repo hygiene. This round deliberately **excludes** findings already tracked in
`review.md` / `deepseek-findings.md` (they appear only in the carry-forward section at the end).
**Method:** Five parallel deep reviews (runtime HTTP/entity/serialization, runtime batch/query, parser+Names,
generators, plugin/tests/docs), followed by independent verification of every High-severity claim against the current
source. All file:line references below were confirmed in the working tree at commit `eaa31aa`.

---

## Summary

| Severity | Count | Highlights                                                                                                                                                                                                                                                                                        |
|----------|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| High     | 10    | Enum wire values wrong by spec; multi-schema `ServiceSchemaInfo` overwrite; abstract-base properties silently dropped on deserialize; PATCH-on-204 crash; `+`-corrupting nextLink decode; quoted GUID filter literals; uncompilable collection-of-complex navs; documented API that doesn't exist |
| Medium   | 24    | Batch Content-ID/boundary/injection issues; `div` vs `divby`; unparenthesized `$filter` joins; collections bypass pluggable `Serializer`; docs promise nonexistent features; live-test hygiene; Maven plugin gaps                                                                                 |
| Low      | 25    | Escaping/formatting gaps, dead API, immutability nits, hygiene                                                                                                                                                                                                                                    |

---

## High severity

### H1. ~~Enum member implicit value uses `members.size()` — violates the CSDL "previous + 1" rule~~ ✅ Resolved

**Resolution:** `StaxCsdlParser.parseEnumType` now tracks the previous member's value; implicit members default to previous+1 (0 for the first). Tests: `StaxCsdlParserEnumMemberValueTest` (2, TDD — failed before the fix).

`odata-codegen-core/.../parser/StaxCsdlParser.java:253`

```java
long value = valueStr != null ? Long.parseLong(valueStr) : members.size();
```

CSDL spec: a `<Member>` without `Value` gets **the previous member's value + 1** (or 0 if first). For legal metadata
like `<Member Name="None" Value="0"/><Member Name="Low" Value="10"/><Member Name="Medium"/>`, spec says `Medium = 11`;
this parser yields `2`. `EnumGenerator` bakes the value into the enum (`member.value()`), so `fromValue`, `fromFlags`,
and any wire serialization carry **silently wrong values**. None of the test metadata mixes implicit and explicit
values, so it's uncaught.

**Fix:** track `lastValue` while parsing members; default to `lastValue + 1` (0 for the first member).

### H2. ~~`ServiceSchemaInfo` is overwritten once per schema when one `basePackage` covers multiple schemas~~ ✅ Resolved

**Resolution:** `Generator.generate` now groups schemas by output package and emits one aggregate `ServiceSchemaInfo` per package after the loop (`SchemaInfoGenerator.generate(List<SchemaModel>)` merges all schemas' registry entries). Single-schema output is unchanged. Tests: `GeneratorSchemaInfoAggregationTest` (2, TDD — aggregate assertion failed before the fix). Full reactor: 499 tests green.

`odata-codegen-core/.../generator/Generator.java:45-92`

The schema loop writes `schemaInfoGenerator.generate(schema)` to the fixed name
`<basePackage>/schema/ServiceSchemaInfo.java` on every iteration (line 91-92, confirmed inside the
`for (SchemaModel schema : model.schemas())` loop). `SchemaInfoGenerator` emits one registry per schema containing only
*that* schema's types. The Maven plugin passes a single `basePackage` for **all** schemas (`GenerateMojo.java:41-42`),
so for any multi-schema service the last schema's file wins and `getClassFromTypeWithNamespace` returns `null` for every
other schema's types. The same silent-overwrite applies to any two namespaces mapped to one package with same-named
types.

**Fix:** generate one aggregate `ServiceSchemaInfo` after the loop (merge all schemas' registry entries), and/or make
`writeCode` detect path collisions and fail instead of overwriting.

### H3. ~~Concrete subtypes of abstract base types silently drop all base properties on deserialization~~ ✅ Resolved

**Resolution:** Both `EntityGenerator` and `ComplexTypeGenerator` now emit `@JsonProperty` setters for own props/navs
unconditionally (abstract types included); each class in the chain owns its own properties' setters. Tests:
`AbstractHierarchyDeserializationTest` (2, TDD — failed with `UnrecognizedPropertyException` on the base props before
the fix; compiles generated abstract hierarchies and proves base properties round-trip through Jackson). Core gained a
test-scoped dependency on `odata-codegen-runtime`, and runtime's dead main-scope dependency on core was removed
(nothing in runtime imports core; it also created a reactor cycle against the new test dependency).

`odata-codegen-core/.../generator/EntityGenerator.java:176` (same pattern in `ComplexTypeGenerator.java:152`)

```java
if(!entityType.abstractType()){
        for(
PropertyModel prop :ownProps){  // setters emitted for OWN props only
```

`@JsonProperty` setters are emitted (a) only for **own** properties and (b) only for **concrete** types. A concrete
subtype of an abstract base therefore has no setter anywhere for the base's properties: the abstract base skips the
block, the subtype emits only its own. With `FAIL_ON_UNKNOWN_PROPERTIES=false`, every property declared on an abstract
base is **silently discarded** — a GET returns subtype entities whose base fields are all null. The gating is provably
unintentional: `setEtag` and `setDynamicProperty` *are* generated on abstract roots; only property/nav setters are
gated. `EntityGeneratorAbstractTest` only compiles the pair, never deserializes; TripPin/OData Demo test metadata
declares no `Abstract="true"` entity types (verified), so live tests never exercise the path.

**Fix:** emit `@JsonProperty` setters for own props unconditionally (abstract classes already get a no-args constructor
and getters; setters are harmless). Add a deserialization test for a concrete-subtype-of-abstract-base pair.

### H4. ~~PATCH (and single-entity GET) crash on 204/empty response bodies~~ ✅ Resolved

**Resolution:** `executePatchEntity`, `executePatchEntityWithETag`, and `executeAndGetEntity` now route through the
existing `deserializeOrNull` guard (returns `null` on 204/empty). Tests: `EntityOperationsEmptyBodyTest` (3, TDD —
all three threw `ODataException` before the fix). Full reactor: 504 tests green.

`odata-codegen-runtime/.../client/EntityOperations.java:43, 93, 107`

```java
return context.serializer().

deserialize(response.body(),responseType);   // lines 43, 93, 107
```

POST and PUT paths use `deserializeOrNull` (lines 52, 61, 75 — returns `null` on empty body). `executePatchEntity`,
`executePatchEntityWithETag`, and `executeAndGetEntity` call `deserialize` directly. PATCH is precisely the operation
that most often returns `204 No Content` (OData v4 §11.4.3 explicitly allows it); Jackson on zero bytes throws
`MismatchedInputException`, wrapped into a spurious `ODataException`. Same gap for GET on entities where the service
returns 204 (documented TripPin behavior, lesson 22).

**Fix:** route lines 43, 93, 107 through the existing `deserializeOrNull`.

### H5. ~~`fromNextLink` uses `URLDecoder`, converting `+` to space — corrupting continuation tokens~~ ✅ Resolved

**Resolution:** `ContextPath.fromNextLink` now decodes query pairs with a percent-only `decodePercent` helper (`%HH`
escapes only; malformed escapes left verbatim) instead of `URLDecoder`, so literal `+` in `$skiptoken` values
round-trips as `%2B`. Tests: 2 new `ContextPathTest` cases (literal `+` preserved as `%2B`; `%2B`/`%3D` round-trip),
plus all 23 pre-existing nextLink/encoding tests unchanged.

`odata-codegen-runtime/.../entity/ContextPath.java:97-98`

```java
result =result.

addQuery(URLDecoder.decode(name, StandardCharsets.UTF_8),
        URLDecoder.

decode(value, StandardCharsets.UTF_8));
```

`URLDecoder` implements `application/x-www-form-urlencoded` decoding, which maps `+` → space. Query strings in
`@odata.nextLink` are percent-encoded, not form-encoded: servers emit `%20` for spaces and leave literal `+` in tokens (
Microsoft Graph and many services use base64-ish `$skiptoken`s containing `+`). `abc+def` decodes to `abc def`,
re-encodes as `abc%20def`, and the next-page request fails against the service.

**Fix:** decode only `%HH` escapes (small custom decoder or parse via `URI.getRawQuery()`); never treat `+` as space.

### H6. ~~`Edm.Guid` filter literals are rendered quoted — invalid OData~~ ✅ Resolved

**Resolution:** new runtime class `GuidProperty<E>` (mirrors `StringProperty`'s shape: eq/ne, null checks, ordering,
`getEdmName`) that validates the 8-4-4-4-12 shape and emits **unquoted** literals (`ShareId eq 0c5a...`), throwing on
anything else. `AbstractTypeGenerator.getPropertyConstantType` maps `Edm.Guid` → `GuidProperty` before the String
fallback, covering both static property constants and `Filterable` fields for entities and complex types; the generated
`query.*` wildcard import picks the new class up with no import changes. Tests: `GuidPropertyTest` (7, runtime
rendering/validation) + `GeneratorGuidPropertyTest` (3, generator content — TripPin `Trip.SHARE_ID` is now
`GuidProperty<Trip>`, not `StringProperty<Trip>`). Full reactor: 516 tests green including live TripPin regeneration.

`odata-codegen-core/.../generator/Names.java:166,185`; emitted via `AbstractTypeGenerator.java` constant construction

```java
case"Edm.String","Edm.Guid"->"String";      // line 166
        return"Edm.String".equals(edmType) ||"Edm.Guid".

equals(edmType);  // line 185 (isStringType)
```

Guid properties map to `StringProperty`, whose `equalTo()` renders `Id eq '0d67...'`. In OData v4, `Edm.Guid` is a
primitive distinct from `Edm.String`; the literal must be the bare 8-4-4-4-12 value. The codebase already knows this for
**keys** — `ContextPath.formatValue` has an explicit GUID_PATTERN branch (lesson 55) — so key lookup works but `$filter`
on the same property is broken/inconsistent.

**Fix:** add a `GuidProperty<E>` that emits the value unquoted after validating the GUID shape (reuse `GUID_PATTERN`);
stop mapping `Edm.Guid` into `isStringType`.

### H7. ~~Collection-of-complex navigation properties are not skipped — generated request classes don't compile~~ ✅ Resolved

**Resolution:** `RequestGenerator.isComplexTypeNav` now unwraps `Collection(...)` before the type-kind lookup, so
collection navs to complex types are skipped in import/nav-method/`$ref` generation like the single-valued case. A
`Collection(Dup.Main.HomeAddress)` nav was added to the multi-schema test metadata; tests:
`RequestGeneratorCollectionComplexNavTest` (2, TDD — both failed before the fix) and `MultiSchemaSamePropNameTest`
still compiles the regenerated entity.

`odata-codegen-core/.../generator/RequestGenerator.java:534-536`

```java
private boolean isComplexTypeNav(NavigationPropertyModel nav, SchemaModel schema) {
    return Names.resolveTypeKind(nav.type(), effectiveSchemas) == Names.TypeKind.COMPLEX;
}
```

`nav.type()` is passed raw. For a collection nav it is `Collection(TestNS.OfficeAddress)`, which matches no key in
`Names.buildTypeKindMap` (which indexes only `NS.Type` and bare `Type`), so `resolveTypeKind` returns `UNKNOWN` and the
check fails. The generator then emits an import and `new OfficeAddressCollectionRequest(...)` for a class that is only
ever generated for **entity** types (`Generator.java:80`). The single-valued case is fixed and tested (
`MultiSchemaSamePropNameTest`); the collection form was never covered.

**Fix:** unwrap first —
`Names.resolveTypeKind(Names.unwrapCollectionType(nav.type()), effectiveSchemas) == TypeKind.COMPLEX`. Add a
`Collection(Complex)` nav to the test metadata.

### H8. ~~README/docs build on an API that does not exist (`peopleByUserName`, `getAsync`)~~ ✅ Resolved

**Resolution:** all ~40 `client.peopleByUserName(...)` occurrences across README.md and `docs/content/**` replaced with
the real API `client.people().personByUserName(...)`; `generated-code.md`'s container reference now documents key
accessors on the collection request (also fixed `tripsByTripId(Long)` → `tripByTripId(Integer)` and collection
`post(...)` → `create(...)` in the same reference). The README "Async Execution" section using the nonexistent
`getAsync()` was removed; "Async-first" claims reworded to the truthful "async transport layer, synchronous request
methods"; the false "Apache implementations" transport bullets were corrected in passing (part of L31). `executeAsync()`
batch docs kept — that API is real. **Note:** `docs/site/**.html` is a committed MkDocs build artifact still containing
the old examples — needs a docs rebuild.

`README.md:93,133,150,154,164,189-201`; `docs/content/index.md`, `docs/content/tutorial/first-query.md`,
`docs/content/how-to/crud.md` (7 uses), `how-to/ref.md`, `how-to/etag.md`, `how-to/error-handling.md`,
`how-to/open-type.md`, `how-to/expand.md`

- Every CRUD/ref example uses `client.peopleByUserName("scottketchum")`. The generated `DefaultContainer` exposes only
  `photos()`, `people()`, `airlines()`, `airports()`, `me()`; the real API is `client.people().personByUserName(...)` (
  confirmed in generated `PersonCollectionRequest.java:172` and the project's own tests).
- The "Async Execution" section and the "Async-first" feature claim use `.getAsync()`, which exists nowhere in the
  runtime or generated code (only low-level `HttpTransport.submit()` returns a future).

Every one of these examples fails to compile as written — the docs are the first thing users try.

**Fix:** global replace to `client.people().personByUserName(...)`; either remove the async section or generate
`getAsync()` (the transport layer already supports it).

### H9. ~~120 live-network tests run untagged/unguarded under plain surefire, including destructive writes to the shared public TripPin service~~ ✅ Resolved

**Resolution:** all 7 live classes (5 in `odata-codegen-test`, 2 runtime integration suites) are tagged
`@Tag("live-service")`; the offline classes (`OpenTypeDynamicPropertyTest`, `ODataParsingTest`) are untagged. Root-pom
surefire now excludes the tag by default, so plain `mvn test` is hermetic (396 offline tests, verified); a `live-tests`
profile runs everything: `mvn test -Plive-tests` (518 tests, verified). Test smells fixed: `createAndDeletePerson` now
`assertNotNull`s the create result and deletes in `finally` so failures don't leak entities on the shared service;
`deletePerson` polls (up to 10×500ms) instead of a fixed `Thread.sleep(1000)`; the silent `return` on TripPin's known
`$ref` 500 became `assumeTrue` (reported as skipped — visible as the 1 skipped test in the live run).
**Workflow change:** live tests no longer run under plain `mvn test` — use `mvn test -Plive-tests`.
**Not done:** moving destructive tests to WireMock (larger refactor); failsafe `*IT` rename (tag-based split achieves
the same hermetic default with less churn).

`odata-codegen-test/src/test/java/.../{TripPinGeneratedClientTest,NorthwindGeneratedClientTest,ODataDemoGeneratedClientTest,ODataDemoMediaTest,TripPinInheritanceTest}.java`;
runtime `TripPinIntegrationTest`, `NorthwindIntegrationTest`

These hit `services.odata.org` on every `mvn test` with no `@Tag`, no `assumeTrue` connectivity guard, and no failsafe
split — the build is non-hermetic and fails offline/behind proxies (services.odata.org is known to throttle). Worse,
some tests **create/delete entities and `$ref` links on the shared public service** (`createAndDeletePerson`,
`addAndRemoveFriend`): concurrent CI runs collide, and a failure mid-test leaves garbage behind. Two adjacent test
smells in the same files:

- `TripPinGeneratedClientTest.java:206-208` — `if (createdResponse != null) { assertEquals(...) }`: passes green even if
  `create()` regresses to returning null.
- `TripPinIntegrationTest.java:511,544-547` — `Thread.sleep(1000)` eventual-consistency wait, and a silent `return` on
  HTTP 500 that reports *success* whenever the server errors.

**Fix:** tag live tests (`@Tag("live-service")`) + failsafe/`*IT` split; replace the null-guard with `assertNotNull`;
replace the sleep with a poll loop; use `assumeTrue` for known server limitations; move destructive tests to WireMock (
already a runtime-test pattern).

### H10. ~~No LICENSE file or POM license/scm/developers metadata despite claiming Apache 2.0~~ ✅ Resolved

**Resolution:** canonical Apache-2.0 `LICENSE` (from apache.org) and a `NOTICE` committed at repo root; root POM now
declares `<url>`, `<licenses>` (Apache-2.0), `<developers>`, and `<scm>` pointing at
`github.com/akbar1214/typesafe-odata-client`. **Not added:** `distributionManagement` (publishing target not yet
decided — add when a registry is chosen).

Root `pom.xml` (104 lines — no `<licenses>`, `<scm>`, `<developers>`, `<url>`, `distributionManagement`); no `LICENSE`/
`NOTICE` tracked anywhere; yet `README.md:255-257` says "Apache License 2.0" and `docs/content/contributing.md` requires
Apache-2.0 contribution terms.

(a) The grant has no legal effect without the license text; (b) any Maven Central publish will fail validation.

**Fix:** commit LICENSE/NOTICE; add `licenses`/`developers`/`scm`/`url` to the root POM.

---

## Medium severity

### Batch / multipart (`odata-codegen-runtime/.../internal/MultipartHelper.java`, `batch/*`)

**M1. ~~Part-level `Content-ID` headers are parsed then discarded — changeset responses can't be correlated.~~ ✅ Resolved**
`decodePartOrNested` now extracts the part-level `Content-ID` (scanning past `Content-Type`, which appears earlier in
part headers) and propagates it into a new `BatchResult.contentId` component (also merged into the case-insensitive
header map); `BatchResponse.getByContentId(String)` exposes the correlation. The 4-arg `BatchResult` constructor
remains as a delegating overload. Tests: `m1ChangesetContentIdsPropagateToResults`,
`m1BatchResponseGetByContentIdFindsResult` (TDD — failed before the fix).

**M2. ~~Changeset Content-ID numbering restarts at 1 per changeset — duplicate IDs across a batch.~~ ✅ Resolved**
`encodeBatchRequest` threads one batch-wide counter; `encodeChangeset` gained a `startContentId` overload (the 2-arg
form still numbers from 1 for direct callers). Two 2-op changesets now emit Content-IDs 1-4. Test:
`m2MultipleChangesetsGetUniqueContentIds` (TDD).

**M3. ~~Malformed responses silently yield empty/partial results.~~ ✅ Resolved**
`decodeSinglePart` throws a diagnostic `ODataException` on unparseable status lines instead of returning null; missing
opening/closing boundaries and parts without a header/body separator throw instead of silently truncating. **Not
done:** count validation of decoded results vs. submitted operations — deliberately, because a failed changeset
legitimately collapses N operations into one error part, making a naive count check wrong; Content-ID correlation
(M1) is the correct mechanism. Tests: `m3UndecodablePartThrowsInsteadOfSilentDrop`,
`m3MissingClosingBoundaryThrowsInsteadOfTruncating` (TDD).

**M4. ~~Delimiter matching isn't anchored to line start.~~ ✅ Resolved**
New `indexOfBoundary` only accepts a delimiter at the start of a line (position 0 or preceded by `\n`) followed by
`--`, a line break, or RFC 2046 transport padding; boundary bytes inside (binary) bodies no longer split parts. The
old terminator logic turned out to never actually match (it silently exited via the failed next-delimiter search) —
the closing delimiter is now detected at the match site. Test: `m4BoundaryBytesInsideBodyDoNotSplitParts` (TDD).

**M5. ~~CRLF injection via user-supplied URLs/headers.~~ ✅ Resolved**
`BatchOperation`'s compact constructor rejects CR/LF/NUL in URLs, header names, and header values
(`IllegalArgumentException` — fails at construction, covering every factory and direct use); `encodeOperation` skips
the implicit `Content-Type: application/json` when the caller supplied one (case-insensitive check). Tests:
`m5UrlWithLineBreakIsRejected`, `m5UserContentTypeNotDuplicated` (TDD).

**M6. ~~Quoted boundary values not handled.~~ ✅ Resolved**
`BOUNDARY_PATTERN` accepts quoted values (`boundary="abc"` — quotes are not part of the boundary) for nested
changeset decoding; `BatchRequest.extractBoundary` matches `boundary=` case-insensitively and strips quotes. Tests:
`m6QuotedNestedBoundaryIsHandled`, `BatchRequestBoundaryTest` (2, stub-transport `execute()` path, TDD).

Verification: 11 new tests (`MultipartHelperHardeningTest` 9 + `BatchRequestBoundaryTest` 2), all pre-existing batch
tests unchanged, full reactor hermetic (407) and live (530) green — live TripPin batch round-trips pass over the new
encoder/decoder.

### Query expressions (`odata-codegen-runtime/.../query/*`)

**M7. `NavQuery` joins multiple `$filter` predicates with unparenthesized `and`.** `NavProperty.java:118`:
`String.join(" and ", filters)`. A predicate containing `or` combined with a second `filter(...)` renders
`A eq 1 or B eq 2 and Y eq true`, which parses as `A or (B and Y)` — silently wrong semantics.
`RawFilterExpression.and/or` parenthesizes correctly; this join site doesn't. Fix: wrap each entry in `(...)` before
joining.

**M8. `NumberExpression.divide` always emits `div`.** `NumberExpression.java:76-79`. OData v4 `div` is truncating
integer division; `divby` is required for Double/Decimal/Single. `Price div 2.0` is rejected by strict services or
truncates on lenient ones. Fix: choose `div`/`divby` by operand type.

**M9. `DateTimeProperty` concatenates raw user strings with no validation or escaping.** `DateTimeProperty.java:61-75` —
`edmName + " gt " + value`. Unlike `StringProperty`, nothing validates the format, so `"2024-01-01' or '1' eq '1"`
injects arbitrary predicates; `Edm.Duration` values are passed through without the `duration'...'`/`P...` form. Fix:
validate against ISO/OData ABNF patterns and fail fast; add typed overloads (`equalTo(OffsetDateTime)` etc.).

**M10. `EnumProperty` manual-construction fallback uses the simple name.** `EnumProperty.java:65-68` —
`enumType.getSimpleName()` produces `Color'Red'`; OData requires the fully qualified `NS.Color'Red'`. (Generator-driven
use passes the qualified name; only manual construction bites.) Also no `has(...)` operator for flags enums.

### URL building / entity layer

**M11. Queries render mid-URL when a segment follows a query-bearing segment.** `ContextPath.appendSegments` (
`:153-162`) emits `?a=b` immediately after the owning segment; a later `addSegment()` (public API —
`EntityOperations.addRef/removeRef` do exactly `addSegment("$ref")`) yields `...?$skiptoken=x/$ref`. `addCountSegment`
already contains a manual workaround for this structural defect. Fix: defer all query rendering until after the segment
loop.

**M12. Collection reads bypass the pluggable `Serializer`.** `EntityOperations.executeAndGetCollection` (`:159,179`)
parses with a private static `COLLECTION_MAPPER`, while single-entity and write paths go through `context.serializer()`.
A custom serializer's modules/naming/date config silently doesn't apply to collections. Fix: delegate element
deserialization to `context.serializer()` (e.g. re-serialize `value` node to bytes and deserialize), or document the
limitation.

**M13. `HttpRequest.connectTimeout` is dead API.** Set by callers (`EntityOperations.java:236,312`) but never read by
any transport — `JdkHttpTransport` uses only its static client's fixed 30s connect timeout. Fix: cache `HttpClient`s
keyed by connect timeout and select by `request.connectTimeout()`, or delete the field.

**M14. Default interceptor `stream()` swallows HTTP error status.** `HttpInterceptor.java:16-27` buffers
`intercept(...)` output without checking `statusCode()`; with zero interceptors a 404 media GET throws, with one
default-intercepting interceptor the same 404 silently yields a stream of the error body. Fix: check `isSuccessful()`
and throw `ODataException.fromResponse` in the default method.

**M15. `Retry-After` HTTP-date form is never parsed; a default is fabricated.** `RateLimitException.java:30-46` —
`Instant.parse` (ISO-8601 only) then `Long.parseLong`; RFC 9110 HTTP-dates (`Wed, 21 Oct 2015 07:28:00 GMT`) fall
through both and are silently replaced by `now+60s`, and callers can't distinguish server-specified from invented
values. Fix: try `DateTimeFormatter.RFC_1123_DATE_TIME` first; return null/`Optional` when the header is absent.

**M16. `DynamicPropertyConverter` mapper doesn't disable `FAIL_ON_UNKNOWN_PROPERTIES`.**
`DynamicPropertyConverter.java:18-20` — every other mapper in the codebase does; dynamic properties converted to user
POJOs are exactly the case most likely to hit unknown keys.

### Parser / generator (core)

**M17. Schema `Alias` is parsed but never resolved — alias-qualified type references misresolve.**
`StaxCsdlParser.java:93` stores it; nothing consults it. `Type="self.Address"` resolves as UNKNOWN → falls back to
ENTITY suffix → wrong package/import or uncompilable output. Fix: build an alias→namespace map before generation and
normalize type refs.

**M18. `toPackageName` is locale-sensitive and non-injective.** `Names.java:16-18` — `toLowerCase()` without
`Locale.ROOT` (Turkish locale turns `I` into `ı` → different packages per build machine), and `.`/case folding collapses
`A.B`/`a.b`/`a_b` onto one package (feeds the H2 overwrite problem). Fix: `Locale.ROOT` + collision detection across
schemas.

**M19. Required CSDL attributes unvalidated → bare NPEs far from the cause.** Missing `Namespace`/`Name`/`Type` surface
as NPEs deep inside `Names`/`Generator` (e.g. `Names.java:17,150,215`); malformed enum `Value="0x10"` throws raw
`NumberFormatException` with no element context. Fix: validate at parse time with element name/location in the message.

**M20. First-letter case folding creates undetected duplicate members.** Properties `Name` and `name` are distinct legal
CSDL names but both map to field `name` (`Names.toJavaFieldName`) → duplicate fields/getters/setters, uncompilable
output. Same family: a real property literally named `etag_` collides with the sanitized `etag` → `etag_`. No per-type
emitted-identifier tracking exists. (Related to but distinct from the known `BUDGET` constant case-collision — this is
*field/getter* level.) Fix: track emitted identifiers per generated type and disambiguate.

**M21. Entity requests ignore inherited navigation properties, named streams, and inherited `HasStream`.**
`RequestGenerator.java:56,82,88,157` iterate `entityType.navigationProperties()`/`properties()` and check
`entityType.hasStream()` directly; request classes don't extend each other, so `FeaturedProductEntityRequest` has no
`category()` nav and a subtype of a media entity gets no `streamMedia()`. Fix: resolve the full base chain like
`resolvedKeys` does.

**M22. `countValue()` still sends `$select`/`$expand`/`$orderby` to `/$count`.** `RequestGenerator.java:386-392` clears
`$top`/`$skip` only, but `copy()` preserves selects/expands/orderings and `buildContext()` appends them; `/$count`
accepts only `$filter`/`$search`/`$apply`. Fix: clear those three too.

**M23. Polymorphic responses are flattened to the declared base class; `@odata.type` ignored.** Subtype-only properties
hit `FAIL_ON_UNKNOWN_PROPERTIES=false` and vanish; the `SchemaInfo` registry generated by `SchemaInfoGenerator` is never
consulted by the runtime (verified by grep) — the infrastructure for the fix exists but is unwired. Fix: read
`@odata.type` in `EntityOperations` and resolve the target class via `SchemaInfo` (or generate `@JsonTypeInfo`/
`@JsonSubTypes` on base entities).

**M24. Generated enums have no Jackson mapping — numeric payloads map by ordinal; flags values unresolvable.**
`EnumGenerator.java:26,33-48` emits `long value` + `fromValue(long)` with neither `@JsonValue` nor `@JsonCreator`.
Jackson therefore maps numeric enum payloads by **ordinal**, which is wrong whenever values aren't exactly `0..n-1` in
declaration order (common: `1,2,4,8`). `fromValue(3)` throws for flags enums though combined values are the norm. Fix:
emit `@JsonValue` on `getValue()` and `@JsonCreator` on `fromValue`, accepting combined flag values.

### Maven plugin / build

**M25. `catch (Exception)` re-wraps `MojoFailureException`.** `GenerateMojo.java:105-107` swallows every deliberate
`MojoFailureException` ("Metadata file not found", "HTTP 401", "Too many redirects") into a generic
`"Failed to generate OData client: ..."` wrapper; `e.getMessage()` may also be null. Fix: rethrow
`MojoExecutionException | MojoFailureException` as-is first.

**M26. No auth support for private `metadataUrl`.** The download request (`GenerateMojo.java:138-142`) carries only
`Accept`; any bearer/API-key-protected metadata endpoint fails with an opaque HTTP 401. Fix: add a
`Map<String,String> httpHeaders` (or token) parameter, included in the marker hash.

**M27. Shared marker file defeats incremental generation when multiple executions share an output directory — which this
repo's own test module does (4 executions).** `GenerateMojo.java:62,82,238-241` keys one `.odata-generation-marker` per
`outputDirectory`; `odata-codegen-test/pom.xml` runs 3-4 executions against the same default output dir, so after any
build the marker holds only the *last* execution's hash and **all clients fully regenerate on every build**. Marker
writes are also non-atomic. Fix: key the marker per execution/config fingerprint; write via temp file + `ATOMIC_MOVE`.

**M28. Stale generated files are never removed.** Neither `Generator.writeCode` nor `GenerateMojo` cleans the output
directory, so renamed/removed types or package remaps leave phantom `.java` files on the compile source root (
duplicate-class errors after remaps). Fix: record a manifest in the marker and delete files absent from the new manifest
before regenerating.

---

## Low severity / polish

### Runtime

- **L1.** `addRef` sends 4.01-style `"@odata.id"` while the transport pins `OData-Version: 4.0` (
  `EntityOperations.java:129` vs `JdkHttpTransport.java:67-68,121-122`); strict 4.0 services expect `"odata.id"`. Pick
  one protocol level.
- **L2.** `ODataError` maps only `error.innererror` into details; the canonical `error.details[]` and `error.target` are
  discarded (`ODataError.java:41-48`).
- **L3.** `fromNextLink` ignores `#fragment`s (left embedded in the path) and `;` as a query separator (
  `ContextPath.java:75,92`).
- **L4.** `executeCount` NPEs on a null body (`EntityOperations.java:197`) — use `response.getText()`.
- **L5.** `CollectionPage.spliterator` declares `NONNULL` unconditionally (`CollectionPage.java:55-58`); JSON `value`
  arrays can contain null.
- **L6.** `JdkHttpTransport.stream()` is a mis-indented near-copy of `execute()` with a case-sensitive `Accept` check (
  `:56-114` vs `:116-171`) — extract a shared builder; lowercase `accept` headers get duplicated.
- **L7.** `HttpRequest.Builder.header(name, null)` fails at `build()` (`Map.copyOf`) far from the cause (
  `HttpRequest.java:29-46`); `Map.copyOf` also drops ordering — use `Objects.requireNonNull` +
  `Collections.unmodifiableMap(new LinkedHashMap<>(...))`.
- **L8.** Interceptor exceptions escape `executeAsync` synchronously (`EntityOperations.java:263-264`) instead of
  completing the future exceptionally — breaks `.exceptionally(...)` composition.
- **L9.** `MultipartHelper` binary bodies: records `BatchOperation`/`BatchResult` have array-identity `equals`/
  `hashCode` and expose the internal `byte[]`; `BatchOperation.get(url, headers)` wraps the caller's map in a mere
  *view* (`:19`). Copy defensively, implement `Arrays.hashCode` equality.
- **L10.** `NavQuery` doesn't defensively copy its five list components and can't express `$count`, `$level`, `$ref`, or
  `skip` (`NavProperty.java:61-68`).
- **L11.** `ApplyBuilder` is mutable and implements `ApplyExpression` — a shared instance races; `top(-5)` renders
  invalid OData (`ApplyBuilder.java:22-24,67-75`).
- **L12.** Transformation methods (`toLower()`, `substring()`, `date()`, ...) return full `PropertyExpression`s, so
  `select(prop.toUpper())` renders the invalid `$select=tolower(Name)` (`StringProperty.java:75-93`). Return a
  non-selectable expression type or reject `(` in `select()`.
- **L13.** `CollectionProperty.any/all` hardcode the lambda alias `x:` while `FilterableElement` supports custom
  prefixes; nested `any` shadows the outer alias; NPE if constructed without a factory (
  `CollectionProperty.java:27-37`).
- **L14.** Batch is legacy-multipart only; no `continue-on-error` preference, no JSON batch / atomicity-group support (
  `BatchRequest.java:55-58`).

### Core (parser/Names/generators)

- **L15.** `isReservedWord` includes module keywords `module/open/requires/exports/opens/to/with` but omits
  `uses/provides/transitive` (`Names.java:280-294`).
- **L16.** `EntityContainer Extends` attribute dropped — inherited entity sets silently absent from the generated
  container (`StaxCsdlParser.java:337-339`).
- **L17.** `CsdlModel` records hold live `ArrayList`s from the parser (only `warnings` — the always-empty list — is
  copied); post-parse mutation corrupts the model.
- **L18.** No whitespace tolerance in type refs: `Type="Collection( Edm.String )"` yields garbage element type
  `" Edm.String "`; malformed `"Collection(Edm.String"` (no `)`) silently yields `"Edm.Strin"` (`Names.java:149-158`).
- **L19.** `KeyModel` drops `PropertyRef/Alias`; key refs naming nonexistent properties fall through to `Object`-typed
  key accessors that fail only at URL-build time (`RequestGenerator.java:479`).
- **L20.** `Names.packageName(String,String)` is dead code (zero callers).
- **L21.** Generated type names can shadow runtime query classes imported via wildcard: a CSDL type named
  `StringProperty`/`NavProperty`/etc. survives sanitization (only JDK names + `Builder`/`Filterable` are guarded) and
  shadows `io.github...query.*` in the same file (`Names.java:239-245`). Add the runtime class names to the shadow list.
- **L22.** Non-nullable primitive getters unbox a boxed field Jackson may have set to null → NPE on first access (
  `EntityGenerator.java:147` boxed field vs `:513` primitive getter).
- **L23.** `$ref` add/remove methods are generated for containment (`ContainsTarget="true"`) navs, where `$ref`
  operations aren't defined; the parsed `containsTarget` and `partner` attributes are never read by any generator (
  `RequestGenerator.java:87-99`, `CsdlModel.java:56-58`).
- **L24.** Enum property filter constants use the raw CSDL `Type` string as `EnumProperty` typeName;
  unqualified/alias-qualified references render invalid literals (`Status'Active'` instead of `NS.Status'Active'`) (
  `EntityGenerator.java:489`, `AbstractTypeGenerator.java:290`).
- **L25.** Cross-schema `TypeDefinition` cache is keyed by simple name across all schemas — a `TypeDefinition` named
  `Foo` in schema A shadows an enum/complex `Foo` in schema B, producing wrong Java types (
  `AbstractTypeGenerator.java:120-152`); also `underlyingType() == null` NPEs. Key by qualified name.

### Plugin / build / docs / hygiene

- **L26.** Mojo not marked `threadSafe = true` although it only touches per-module paths (`GenerateMojo.java:29`;
  descriptor confirms `false`) — warnings/skips under `mvn -T`.
- **L27.** `Accept: application/xml, application/json` on metadata download invites a JSON response the StAX parser
  can't read, failing cryptically (`GenerateMojo.java:141`).
- **L28.** When both `metadataUrl` and `metadataFile` are set, the file silently wins (`GenerateMojo.java:110-117`) — at
  least log a warning.
- **L29.** POMs use `source`/`target` 17 instead of `release` 17 (and set it twice); `slf4j-simple` version duplicated
  in two module POMs instead of `dependencyManagement`; no `maven-plugin-plugin` version/`goalPrefix` declaration; no
  `outputTimestamp`/source/javadoc plugin config for releases.
- **L30.** `odata-codegen-test/pom.xml:57` reaches into another module's test resources via
  `${project.parent.basedir}` — breaks standalone builds; move `trippin-metadata.xml` into the test module.
- **L31.** Docs claim Apache HttpClient/OkHttp transports that don't exist; the "Apache HttpClient" example actually
  imports `JavaNetHttpTransport`, and the OkHttp snippet's `stream()` override has no `return` (won't compile) (
  `docs/content/how-to/custom-transport.md:13-45`, `README.md:11`, `docs/content/index.md:16`).
- **L32.** `run-bench.sh:10` points at `com/modernodata/runtime/bench/...`, a path that doesn't exist — the script
  always fails; also builds its classpath without `-am`, and writes results to `/tmp`.
- **L33.** `.idea/*.xml` and `modern-odata-client.iml` are tracked in git; `.DS_Store` isn't gitignored (files already
  on disk); no Maven wrapper (`mvnw`) for reproducible builds.

---

## Carry-forward: previously reported and still open (from `review.md`)

Not re-litigated here; still open at commit `eaa31aa`:

- **H7** — duplicate dead `JavaNetHttpTransport` (divergent error semantics).
- **H6 (partial)** — case-colliding property constants (`budget` vs `Budget` → `BUDGET`).
- **M3** — `BatchRequest.execute()`/`executeAsync()` duplication.
- **M4** — `changedFields` tracked but never consumed.
- **M7** — v4 referential constraints parsed as nulls.
- **M9 (extended by M21 above)** — GUID-shaped *string* keys sent unquoted; new findings add datetime/enum key literals
  also rendering invalid, and the generator discarding key Edm-type info it already has.
- **M10 (partial)** — interceptor chain rebuilt per request when interceptors exist.
- Low: `warnings` field dead, `baseUrl` validation, `BatchResult<T>` unused `T`, function/action imports + singletons
  parsed but not generated (upgraded to Medium-worthy here as M-gap: `ContainerGenerator` ignores them entirely with no
  warning).

---

## Suggested priority order

1. **H3** (abstract-base properties silently dropped — data loss on every read of subtype entities) + **H1** (wrong enum
   wire values) — silent-corruption bugs, worst class of defect.
2. **H2** (multi-schema `ServiceSchemaInfo` overwrite) — breaks the stated multi-schema feature whenever the plugin's
   `basePackage` is used.
3. **H4** (PATCH/GET on 204 crashes) + **H5** (`+`-corrupting nextLink decode) — runtime failures on mainstream service
   behavior.
4. **H6** (quoted GUID filters) + **M8** (`div` vs `divby`) + **M7** (unparenthesized filter joins) — query-correctness
   against real services.
5. **H7** (uncompilable collection-of-complex navs) — generation failure on legal CSDL.
6. **H8–H10, M25–M28** — docs that don't compile, licensing, plugin robustness.
7. **H9** — hermetic tests; will keep paying off on every CI run.
8. Batch hardening (**M1–M6**) before any consumer relies on changesets.

---

## Overall assessment

The architecture and test discipline remain the strong points, and the previous review rounds genuinely closed their
critical findings. This round's findings cluster in three places the earlier reviews didn't reach: **silent data
corruption** (enum values, abstract-base deserialization, polymorphic flattening, ordinal enum mapping), **generator
blind spots for legal-but-unusual CSDL** (abstract bases, collection-of-complex navs, aliases, multi-schema
`ServiceSchemaInfo`), and **the gap between docs and code** (README examples that can't compile, nonexistent
async/transports, missing license). None of the High findings require design changes — all have localized fixes
consistent with existing patterns (e.g. `deserializeOrNull`, base-chain resolution in `RequestGenerator`, per-type
identifier tracking).
