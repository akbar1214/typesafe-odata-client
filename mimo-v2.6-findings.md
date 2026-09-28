# Code Critique Findings — mimo-v2.6

Reviewed: runtime (65 files), codegen-core (~7k LOC), maven plugin, tests, and all docs.
Totals: **~9 Critical · ~8 High · ~36 Medium · ~50+ Low**.

---

## Critical (must fix — wrong output, fatal generation, or broken examples)

1. **Collection/complex-bound ops abort the entire client build** — `OperationGenerator.java:456-460`. A single legal `Collection(NS.Foo)` binding throws `IllegalStateException` on the *next* `boundOperationsFor()` call. Decision 96 says "deferred"; the code is fatal. Fix: skip non-entity bindings with a warning.
2. **Marker manifest clobber (plugin)** — `GenerateMojo.java:80-85,131`. Two executions, same metadata source + same output dir, different `basePackage` → shared marker → execution B's stale-delete **erases A's generated files** every build. Fix: multi-block marker keyed by (source, config), or throw on the conflict.
3. **Docs ship the wrong Maven coordinates everywhere** — README:29, getting-started:19/43, maven-plugin:11 use `io.github.akbarhusain.odata`; POM is `io.github.akbarhusain`. First snippet every user copies fails resolution. Plus ~25 compile-breaking doc examples (`.filter().and()`, removed `byID` accessors, `.post()` vs `.create()`, fictional `Apply`/`CsdlParsingException` classes, `NavProperty` signatures — class deleted).

## High (correctness / security)

### Runtime

- **H1** `JdkHttpTransport.java:61-92` wraps `InterruptedException` in `ODataException` — breaks lesson 160's interrupt contract; tests green because they mock past this path.
- **H2** `ContextPath.formatTypedValue:338-356` embeds Guid/date/numeric key values **raw** — path injection via `x/../../admin`; no shape validation (the query side has ABNF validators, the URL side doesn't).
- **H3** `NavQuery.as():94-107` resets all option state — `.top(5).select(...).as(...)` silently drops `$top`/`$select`. Test only calls `.as()` first, so it's invisible.

### Core

- Uncompilable output from: getter names not reserving lifecycle members (`contextPath` → collides with interface method); nav methods colliding with `get`/`select`/`expand`/`Object` methods; property+nav same-name passing collision check; unresolvable types guessing an entity package. **Unknown `BaseType` silently drops inheritance** (contradicts the loud-failure policy everywhere else).
- **Enum wire-collision deserializes the wrong member** (`valueOf` before `BY_NAME`, `EnumGenerator:112-120`).

### Plugin

- **Cross-origin redirects re-send `Authorization`** — bearer token leaked to third-party `Location` hosts, including HTTPS→HTTP downgrades.
- **Header values in marker hash** → token rotation forces full regeneration every CI build (defeats incremental generation; the H8 fix bought nothing).

### Tests

- `TripPinOperationImportTest:56-66` — **inverted `assumeTrue`**: the known service fault *passes* the test and every *unexpected* server error **skips green**. False-green in the only bound-op live coverage.
- `ODataDemoMediaTest:41,54` — silent `return` on empty pages (lesson 95's banned smell).

## Medium highlights

- **Silent wrongness:** cross-schema unqualified `BaseType` loses ancestor bound-ops; inherited-nav cast constants missing on subtypes; typedef-of-Collection properties emit garbage types; `addKey` on a name-less segment **drops the key** (test asserts the bug); POST/PUT/PATCH discard header-only ETags; `removeRef` missing `$id` resolution parity.
- **Leaks/hazards:** `stream()` error body never closed; plugin download streams never closed (all paths); unbounded `newCachedThreadPool`; `OData-Version` defaults live only in `JdkHttpTransport` (custom transports lose protocol headers); `HttpResponse.body` not cloned.
- **Perf:** `baseQualifiedNameOf` is O(n²) per schema (lesson 187's pattern still live); bound index built twice; function imports re-resolved ~4×; `Names` sanitizer caching still pending.
- **Plugin:** `SchemaMapping` missing `packageName` → silent `null.entity` packages; marker written *before* stale-delete (failure never retried); URL metadata re-downloaded before every up-to-date check (offline CI fails despite valid markers).
- **Coverage gap:** only 13 offline tests in `odata-codegen-test` — all generated-client CRUD/ETag/expand behavior is live-only.

## Systemic patterns

1. **Tests pin accidents, not contracts** (lesson 119 recurring): inverted `assumeTrue`, `.as()`-first-only fixtures, mock-transport tests that bypass the real interrupt/boundary/stream code paths, `contains()` assertions on renamed identifiers.
2. **0-match paths stay silent while >1-match throws** — unknown base, unknown type, unknown binding all fail soft; the "one ambiguity policy — throw" was only applied to ambiguity, not absence.
3. **Docs drift is widespread again** (lesson 94): wrong coordinates, deleted APIs, wrong packages (`request/` vs `entity.request/`+`operation/`), stale test counts (285 vs 888), wrong repo URL, broken `{{ odata_client_version }}` macro, "285 tests"/"seven live classes" (actually 8).
4. **Duplication without a shared seam:** base-chain walking ×3, `with*` ×2, constant emission ×3, boundary parsers ×2, `rethrowCause` ×6, `trimTrailingSlash` ×2 — each pair already drifted.

## Recommended fix order

1. **C1 bound-op abort** + **H2 key injection** + **M1 stream leak** (small, high impact).
2. **H3 `as()` state loss** + **H1 interrupt contract** + shared `rethrowCause` utility.
3. **Plugin H1 marker clobber** + **H3 cross-origin auth strip** + remove header-hash.
4. **Test honesty pass**: fix inverted `assumeTrue`, silent returns, and add the four missing referee tests (real-transport interrupt, `.as()` after chaining, spaced batch boundary, stream error close).
5. **Generator loud-failure batch**: unknown base/type throws, reserved nav/getter method sets, kind-aware collision map, enum `BY_NAME`-first.
6. **Docs sweep**: coordinates, compile-breaking examples, package listings, version macro — plus a grep-driven docs-vs-POM test so coordinates can't drift again.
7. **Perf + hygiene**: O(n²) base-name index, shared `OperationGenerator`, dup extractions; remaining Mediums/Lows in cleanup PRs.

Every fix should land red-first with the `CompilationHarness` javac referee for anything claiming "compiles/doesn't compile" (lesson 120/173).
