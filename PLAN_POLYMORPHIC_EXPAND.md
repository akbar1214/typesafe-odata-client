# Plan — Type-Safe Polymorphic Expands (cast segments in `$expand`)

Approved scope: **Tier 1 + Tier 2** (full type safety, zero strings at call sites).
Status: awaiting implementation kickoff.

## Problem

davidmoten-style raw query: `.containers(id).expand("Versions/ABC.Doc($expand=abc)")` — a
**polymorphic expand**: navigate `Versions` (collection of base type), **type-cast** to derived
`ABC.Doc` (narrowing to the subtype and unlocking subtype-only navigations), expand `abc`
(declared only on the derived type).

Our typed renderer emits `NavName(options)` only — no path segments, no casts, no raw expand
escape hatch. `abc` is unreachable today. Expanding via the base type is invalid (base doesn't
declare `abc`); top-level `$filter=isof(...)` filters containers, not the expanded collection;
expanded navs do **not** subtype-resolve on deserialization (decision 46 runs only at request
level), so `instanceof Doc` after a plain `expand(VERSIONS)` never matches.

## Tier 1 — Runtime (no generator change)

Files: `odata-codegen-runtime/.../query/NavProperty.java` (+ `NavQuery` record)

1. `NavQuery` gains a nullable `castSegment` record component.
   `toODataExpand()` renders `edmName + "/" + castSegment` before the options paren;
   option rendering unchanged.
2. `NavProperty<E,T>.as(String qualifiedCast, Class<S> subtype)` with bound `<S extends T>`
   → returns `NavQuery<E,S>`:
   - Nested `select`/`filter`/`expand`/`top`/`skip`/`count` then validate against **S** —
     subtype constants (`MyDoc.ABC` is `PropertyExpression<Doc,?>`) satisfy `? super S`,
     inherited base properties still accepted (supertypes of S)
   - `<S extends T>` makes casting to an unrelated type a **compile error** — no runtime
     validation machinery needed
   - Blank/null `qualifiedCast` → `IllegalArgumentException` naming the parameter
3. `NavQuery.raw(String odataExpand)` — verbatim escape hatch, house style
   (`FilterExpression.of`, `ApplyExpression.of`).

No request-layer change: collection requests and entity requests both accept
`NavQuery<? super E, ?>...` already. `/ ( ) ,` are OData-safe-restored by
`ContextPath.encodeQueryParam`, so cast segments survive URL encoding.

Call-site shape after Tier 1 (one string — the CSDL cast name):

```java
client.containers(id)
    .expand(MyContainer.VERSIONS.as("ABC.Doc", Doc.class).expand(MyDoc.ABC))
    .get();
// GET .../Containers(id)?$expand=Versions/ABC.Doc($expand=abc)
```

## Tier 2 — Generator (zero strings at call sites)

Files: `RequestGenerator`/`EntityGenerator` constant emission (subtype index), `Names` if needed.

4. Build a subtype index once per generator instance (lesson-178 discipline): entity qualified
   name → list of known subtypes (direct + transitive via `baseType` chains, cross-schema,
   aliases already resolved at parse).
5. On each OWNING entity, for each nav whose target has subtypes, emit cast constants:

   ```java
   public static final NavQuery<MyContainer, Doc> VERSIONS_AS_DOC =
       VERSIONS.as("ABC.Doc", Doc.class);
   ```

   - Qualified CSDL name taken from the resolved model (never hand-written)
   - Naming `<NAV>_AS_<TYPE>`; collisions dedupe `_2`/`_3` (decision-54 policy) and join the
     existing collision registry
   - No constants when the target has no subtypes (output byte-identical for those entities)
   - `<S extends T>` bound enforced at constant construction

Call-site shape after Tier 2:

```java
client.containers(id)
    .expand(MyContainer.VERSIONS_AS_DOC.expand(MyDoc.ABC))
    .get();
```

Note: TripPin's `Person.TRIPS` targets `Trip` (no subtypes) → no new constants there;
`PlanItem`-targeting navs (`Flight`/`PublicTransportation` subtypes) will gain them — pin
exact corpus expectations during implementation.

## Tests (TDD, red→green)

- Runtime (`NavPropertyExpandTest` + new cases):
  - `as()` renders bare cast `Versions/ABC.Doc`
  - cast + typed options: `Versions/ABC.Doc($select=Name)`, `($expand=Abc)`, chained options
  - blank cast throws with named message
  - `raw()` renders verbatim
- Core:
  - negative compile test: `VERSIONS.as("x", UnrelatedEntity.class)` fails to compile
    (`<S extends T>`)
  - constant emission: naming, qualified-name correctness, `_2` dedupe, cross-schema subtype,
    none-when-no-subtypes
  - referee compile test extended with a cast constant
  - request content pin: cast-bearing `NavQuery` flows through collection + entity request
    `expand()` untouched

## Docs / bookkeeping

- `how-to/expand.md`: new "Polymorphic Expands (Type Casts)" section
- `docs/content/release-notes.md`: entry
- `AGENTS.md`: decision 18 amendment + test counts

## Verification

Full offline reactor green before commit; then branch `feature/polymorphic-expand`,
commit, push, PR.
