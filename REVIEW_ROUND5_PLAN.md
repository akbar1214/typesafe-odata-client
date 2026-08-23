# Review Round 5 — Fix Plan

Critique of 2026-08-23 (runtime + generators + plugin + generated output).
Scope: **Phase 1 & 2 only** in this pass. Phase 3 (API evolution) is deferred and listed
at the bottom for a future round. Every fix follows TDD: failing test first, then the fix.

**Status: Phase 1 & 2 COMPLETE.** Full reactor green: 641 offline tests
(238 core + 370 runtime + 25 maven + 8 test module). Lessons 157–167 appended to
AGENTS.md; docs updated: `error-handling.md` (new exceptions + 428 section),
`etag.md` (header capture), `filter.md` (chaining semantics), `batch.md`
(typed views keep contentId), `media.md` (close contract + buffering limitation).

---

## Phase 1 — Correctness

| ID | Finding | Location | Status |
|----|---------|----------|--------|
| H1 | Top-level `filter()` chaining joins with `" and "` **without** parenthesizing each predicate → `A or B and C` mis-parses. NavQuery fixed this in lesson 101; the generated collection request was missed. | `RequestGenerator.java:342` (emitted `buildContext`) | ✅ |
| H2 | `BatchResponse.get(index, type)` / `getAll(type)` rebuild results via the 4-arg `BatchResult` constructor → **contentId dropped** from typed views. | `BatchResponse.java:41-56` | ✅ |
| M1 | `ContextPath.decodePercent` decodes `%HH` byte-by-byte into chars → multi-byte UTF-8 (`%C3%A9`) becomes mojibake (`Ã©`). Affects nextLink round-trips. | `ContextPath.java:133-152` | ✅ |
| M2 | `fromNextLink` scheme check is case-sensitive (`startsWith("http://")`) — inconsistent with `regionMatches(true,…)` policy used everywhere else (lessons 83/126). | `ContextPath.java:104` | ✅ |
| M3 | `NavProperty.top()/skip()/count()` (+ `NavQuery` variants) don't validate negatives; `ApplyBuilder` validates ≥ 0 (lesson 110) — parity gap. Options also stored pre-rendered. | `NavProperty.java:56-67` | ✅ |
| M4 | Sync-over-async wrappers lose thread interruption: `.join()` unwrap turns `InterruptedException` into a generic `ODataException` without restoring the interrupt flag (`EntityOperations.executeSync/streamMedia`, `BatchRequest.execute`). Separately, `HttpInterceptor.stream()` default throws **synchronously** out of the future chain while `submit()` wraps into `failedFuture`. | `EntityOperations.java:365,453`, `BatchRequest.java:61`, `HttpInterceptor.java` | ✅ |
| M5 | Missing typed exceptions for statuses services actually return: 428 Precondition Required (TripPin's missing-If-Match signature, lesson 20), 408 Request Timeout, 410 Gone. | `ODataException.fromResponse` | ✅ |

## Phase 2 — Runtime hygiene

| ID | Finding | Location | Status |
|----|---------|----------|--------|
| M7 | GET discards response headers; entities get etag only from the `@odata.etag` body annotation. Header-only ETag services leave users unable to call `patchWithETag` after a plain GET. Fix: capture `ETag` header onto the entity via a new default method on `ODataEntityType`, overridden by generated root entity classes. | `EntityOperations.executeAndGetEntity`, `ODataEntityType`, `EntityGenerator` | ✅ |
| M8 | `BatchRequest.resolveEntryUrls()` mutates `entries` in place — builder consumed by execution (hygiene; idempotent but surprising). Build a resolved copy instead. | `BatchRequest.java:107-125` | ✅ |
| M9 | Generated entity-request classes are not `final` while collection requests are (decision 36 parity). | `RequestGenerator.java:76` vs `:236` | ✅ |
| M10 | Transport hygiene: per-connect-timeout client cache is static/shared across instances → make instance-level; document stream() close contract on `HttpTransport`/`JdkHttpTransport`. Thread-per-request model and redirect/proxy config are deferred to Phase 3 (needs API decisions). | `JdkHttpTransport.java`, `HttpTransport.java` | ✅ |
| M11 | Multipart nits: status-line regex rejects `HTTP/2 200`; dead `next > endPos` condition; `write()` comment says ASCII but encodes UTF-8. Host-header note: absolute-form request targets make `Host` unnecessary per RFC 9112 §3.2.2 — no change needed, document why. | `MultipartHelper.java` | ✅ |
| L1 | `addRef` builds `Map.of("@odata.id", absolute)` — NPEs opaquely on null target URL. Add requireNonNull with message. | `EntityOperations.java:202` | ✅ |
| L2 | `$search` term unvalidated → reject control characters at `ContextPath.addQuery("$search", …)`. | `ContextPath.java` | ✅ |
| L3 | OData canonical functions `ceiling/floor/round` missing from `NumberExpression`. | `NumberExpression.java` | ✅ |
| L4 | `CollectionPage.spliterator()` lacks SIZED characteristic though size is known. | `CollectionPage.java:55-59` | ✅ |
| L5 | Dead branch `headers.isEmpty() ? null : headers` in `putMedia` (Content-Type always added). | `EntityOperations.java:401` | ✅ |
| L6 | `DateTimeProperty` implements `PropertyExpression<E,String>` but accepts typed values — javadoc clarification only (changing generics is breaking). | `DateTimeProperty.java` | ✅ |

## Deferred (with rationale)

- **M6 streaming media upload**: true end-to-end upload streaming requires an
  HttpRequest body-publisher abstraction flowing through the interceptor chain —
  API surface work that belongs with Phase 3. Documented as known limitation.
- **Phase 3 API evolution**: Context.Builder timeout/policy config; async variants on
  generated requests; filter varargs/composition redesign; auto-paging `pages()` stream;
  typed `$ref` overloads; context-bound BatchResponse; retry hook. Not started this round.

## Protocol

1. Failing test per item (repo convention: content assertions for generator changes,
   mock-transport behavioral tests for runtime).
2. Minimal fix; match existing style; no comments unless explaining non-obvious contract.
3. After runtime changes, `mvn -pl odata-codegen-runtime install` before core compilation
   tests (lesson 56 — GeneratorCompilationTest resolves runtime from `.m2`).
4. Full reactor `mvn test` (offline; live tests excluded by default per decision 45).
5. Update AGENTS.md lessons (round-5 entries) + test counts; refresh affected doc pages:
   `filter.md` (chaining semantics), `etag.md` (header capture), `batch.md`
   (typed views keep contentId), `error-handling.md` (new exceptions), `media.md`
   (stream close contract + buffering note).
