# Code Critique Findings — mimo-v2.6 (RESOLVED — retained for provenance)

> **Status: every Critical and High item below was re-verified against `main` on
> 2026-09-28 and found already fixed.** This file was previously committed as if it were
> an open findings list, which is worse than no document at all: it invites the next
> reviewer (or agent) to "fix" code that is already correct, and the pinned tests it
> describes can be mistaken for contracts still needing work. The verified outcomes are
> recorded below so the file cannot mislead a future reader again. See `AGENTS.md`
> decisions 99–104 and the review MRs for what this round actually changed.

Reviewed: runtime (65 files), codegen-core (~7k LOC), maven plugin, tests, and all docs.
Totals claimed at the time: **~9 Critical · ~8 High · ~36 Medium · ~50+ Low**.

---

## Verification outcome (2026-09-28)

| Claim | State on `main` at verification time |
|---|---|
| C1 collection/complex-bound ops abort the build | **Fixed** — `OperationGenerator.ensureBoundIndex` collects invalid bindings and logs "Skipping bound operation …" instead of throwing |
| C2 marker manifest clobbers other executions | **Fixed** — `GenerateMojo` folds `canonicalConfiguration()` into the marker identity, so executions no longer share it |
| C3 docs ship wrong Maven coordinates | **Never true on `main`** — docs use `io.github.akbarhusain`, matching the POM `groupId` |
| H1 transport swallows `InterruptedException` | **Fixed** — both `submit` and `stream` catch it first, restore the flag, keep the cause typed |
| H2 Guid/date keys allow path injection | **Fixed** — `ODataLiteral.format` validates every non-string Edm type; only `Edm.String` reaches percent-encoding |
| H3 `NavQuery.as()` drops `$top`/`$select` | **Fixed** — both `as()` overloads carry all eight components |
| Enum `valueOf` before `BY_NAME` | **Fixed** — `EnumGenerator` consults `BY_NAME` first, then falls back to `valueOf` |
| `addKey` on a nameless segment drops the key | **Fixed** — `ContextPath.addKey` rejects a blank name and a null value |
| `HttpResponse.body` not cloned | **Fixed** — cloned in the compact constructor and again in the accessor |
| Inverted `assumeTrue` in the live bound-op test | **Fixed** — the guard is gone; the test asserts directly |
| Cross-origin redirect re-sends `Authorization` | **Fixed** — `GenerateMojo` strips sensitive headers on origin change or HTTPS→HTTP downgrade |
| `SchemaMapping` missing `packageName` → `null.entity` | **Fixed** — `normalizedMappings()` throws naming the namespace |
| `OData-*` headers only in `JdkHttpTransport` | **Fixed** — `EntityOperations` adds them, so custom transports carry them too |

The Medium/Low items were not individually re-verified; treat them as unaudited rather
than as open defects.

## What this round actually found and fixed

A fresh review of the same code did surface real defects, none of which are in this file:

- **Batch correlation** — a single-operation failed change set threw, because the collapse
  branch was unreachable behind an equal-count branch; and a collapsed change-set failure
  was stolen from an unrelated failing standalone operation, silently returning another
  operation's status code. Fixed in `review/01-batch-correlation`.
- **Lambda guard** — `any()`/`all()` rejected legal OData (`x eq 'a'`, `$count` paths, and
  therefore every nested `contains(...)`), while the sibling `NavQuery.filter` accepted the
  same text. Fixed in `review/02-query-lambda-guard`.
- **Cross-schema packages** — a type declared in one schema resolved to the *generating*
  schema's package, so split-merge metadata produced uncompilable or silently-wrong
  clients. Fixed in `review/03-generator`.
- **Literal ABNF conformance** — six divergences from the v4.01 construction rules,
  including accepting `duration'PT1'`, rejecting the legal decimal exponent, and emitting
  a bare `String.valueOf` for an unknown Edm type. Fixed in `review/04-literal-abnf`.
- **Function parameter values** — `OperationPath.segment` validated parameter *names* and
  then copied values verbatim, allowing an argument-list breakout. Fixed in
  `review/05-operation-path`.

---

<details>
<summary>Original (unverified) findings as received — do not treat as open</summary>

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

</details>
