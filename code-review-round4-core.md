# Code Review Round 4 — `odata-codegen-core` only (zero assumptions)

> **Status: all 7 confirmed findings (H1, H2, M1-M5) RESOLVED** on branch `fix/review-round4-core` — every fix is TDD-verified by 8 tests redistributed into subject-named classes (`HostileNamesCompilationTest`, `EnumJsonDeserializationTest`, `GeneratorPolishTest`, `StaxCsdlParserPolishTest`: hostile names generate *and compile*, enum wire names round-trip reflectively, collisions fail loudly). The fix pass also surfaced and closed one adjacent gap the review had missed: key-accessor method names (`tBy + capitalize(rawKeyProp)`) were built from unsanitized CSDL names.

**Date:** 2026-08-16
**Scope:** Every main-source file in `odata-codegen-core` (parser, model, all 7 generators, `Names`), re-verified from source — prior reviews, lessons, and documented decisions were treated as *hypotheses to re-test*, not facts.
**Method:** Full source read **plus executed experiments** — hostile-but-legal CSDL was generated through the real parser+generator and the output was **compiled with `javac`** against the runtime jars. Nothing was accepted from documentation alone; suspected bugs were confirmed or refuted by running them.

---

## How this review was performed differently

Per lesson 118: every "known limitation" and every prior conclusion was re-litigated. A scratch probe suite drove the parser/generator with hostile metadata and compiled the results. **Two suspected bugs were refuted by experiment** (recorded below) — exactly the failures a purely static review would have shipped as findings.

## Verified-correct (re-tested, no assumptions)

- Aggregate `ServiceSchemaInfo` per output package; `writeCode` duplicate-content detection
- `GuidProperty` routing before the String fallback; typed key literals (incl. enum-key `NS.Enum'Member'` formatting path and composite `addKey(..., edmType)` emission)
- `@JsonProperty` setters on abstract types; loud field-collision errors (`budget`+`Budget` correctly fails generation with both names in the message); constant auto-dedup (`BUDGET_2` verified in probe output)
- Container `Extends` merge (qualified/unqualified/circular/unknown); v3 namespace rejection; XXE/DTD disabled; `Collection(...)` unwrap validation; enum previous+1 values; alias resolution per schema
- **Refuted suspect 1 — parser alias state leaks across `parse()` calls:** `currentAlias` is reassigned (to `null` when absent) on every `<Schema>`, so reuse of one parser instance cannot leak an alias. Probe confirmed fresh and reused parsers behave identically.
- **Refuted suspect 2 — `skipElement` mishandles self-closing tags:** StAX emits END_ELEMENT for empty elements; depth accounting is correct (also pre-verified by the existing annotation-bearing metadata tests).

---

## High — uncompilable or wrong output on legal CSDL

### H1. ~~`Names.toConstantName` emits non-identifier constants~~ ✅ Resolved for names containing `-` (and other non-identifier chars)
`Names.java:92-106` — every character is appended verbatim (`Character.toUpperCase('-') == '-'`); only the *first* character is validated. A property named `First-Name` (legal CSDL — XML `NCName` allows `-`) produces:

```java
public static final StringProperty<T> FIRST-NAME = new StringProperty<>("First-Name", T.class);
```

**Probe evidence:** full generation succeeded, `javac` **FAILED to compile** the output. Same root cause breaks enum members via `enumConstantName`'s fallback (`toConstantName("a-b")` → `A-B(0L)` — invalid constant). Note the *field/getter* path is safe (`toJavaFieldName` sanitizes via `sanitizeIdentifier`); only constants and enum members bypass it.
**Fix:** route `toConstantName` through the same character-sanitizing loop as `sanitizeIdentifier` (map invalid chars to `_`). **Companion fix:** `fromJson` currently maps JSON strings by `valueOf(<constant>)` — after sanitizing, the wire name `a-b` would no longer match the constant `A_B`, so the enum needs a name→constant map (or `@JsonProperty`-style annotation on constants) to keep the round-trip.

### H2. ~~`Filterable` nav fields bypass constant-name allocation~~ ✅ Resolved → duplicate fields
`AbstractTypeGenerator.java:387` — `generateFilterableNavPropertyField` uses `Names.toConstantName(nav.name())` directly while every other emission site uses `constantNameFor(...)`. For a property `BUDGET` + navigation `budget` (fields `bUDGET`/`budget` differ, so the field-collision guard does not fire), the `Filterable` class gets **two `BUDGET` fields**:

```java
public final NumberProperty<T, Double> BUDGET = new NumberProperty<>("x/BUDGET", T.class);
public final CollectionProperty<T, Other, Other.Filterable> BUDGET = new CollectionProperty<>("x/budget", ...);
```

**Probe evidence:** printed verbatim from generated output (static constants correctly dedup to `BUDGET`/`BUDGET_2`; only the Filterable nav path collides → uncompilable).
**Fix:** one line — `constantNameFor(nav.name())` at `AbstractTypeGenerator.java:387`.

---

## Medium

### M1. ~~Container members have no collision check~~ ✅ Resolved → uncompilable container
`ContainerGenerator` emits accessors named `toJavaFieldName(member.name())` for entity sets and singletons without checking collisions. An `EntitySet` and a `Singleton` both named `People` (legal CSDL; differing return types do not overload in Java) produce two `people()` methods. **Probe evidence:** `javac` **FAILED to compile**. Two same-cased sets (`People`/`people`) collide the same way.
**Fix:** a `checkMemberNameCollisions`-style pass over sets+singletons+imports in `ContainerGenerator`.

### M2. ~~Enum filter literal uses the TypeDefinition name~~ ✅ Resolved, not the resolved enum
`EntityGenerator.java:494` / `AbstractTypeGenerator.java:380` pass the **raw** `edmType` to `qualifiedEdmName` for `EnumProperty`. For `TypeDefinition Tint UnderlyingType="Ns.Color"` + property `Type="Ns.Tint"`:
```java
public static final EnumProperty<T, Color> SHADE = new EnumProperty<>("Shade", T.class, Color.class, "Ns.Tint");
```
The Java class routing is right (`Color.class`), but the runtime renders `$filter` literals as `Ns.Tint'Red'` — invalid; the enum's qualified name is `Ns.Color`. **Probe evidence:** verbatim above.
**Fix:** pass the `resolveTypeDefinition(...)`-resolved type into `qualifiedEdmName` at both emission sites.

### M3. ~~Multiple `<Key>` elements accepted~~ ✅ Resolved → per-key single-key accessors for a multi-key entity
The parser accumulates every `<Key>` (`StaxCsdlParser.java:162`); CSDL allows at most one. With two keys, the collection request emits `tByA(String)` *and* `tByB(String)` — each builds a single-key predicate (`T('a')`) for an entity whose key is `(A,B)` composite → invalid URLs at runtime. `getKey()` likewise reads `keys.get(0)` only. **Probe evidence:** both accessors generated; output compiles (which makes it worse — the failure moves to runtime 400s).
**Fix:** reject multiple `<Key>` elements at parse time (consistent with `requireAttr` philosophy), or merge property refs into one key.

### M4. ~~Builder nav setters don't record changes~~ ✅ Resolved → partial PATCH silently drops them
`EntityGenerator.java:618-625` — property setters add to `changed` (`changed.add(...)` at :613) and `with*` nav methods merge (`EntityUtil.mergeChanged` at :573), but **Builder nav setters do neither**. `Person.builder()....photo(...)...` then `patch()` omits the nav from the partial body. Verified by source read (the probe's filter was mis-written; the generator source is unambiguous).
**Fix:** `changed.add(nav.name())` in the Builder nav setter loop.

### M5. ~~`<PropertyRef/>` without `Name`~~ ✅ Resolved → bare NullPointerException at parse
`StaxCsdlParser.parseKey` (:210) uses `getAttr` (nullable) while every other required attribute uses `requireAttr`. The null reaches `KeyModel`'s compact constructor, where the `List.copyOf` defensive copy throws `NullPointerException` with no element context. **Probe evidence:** stack — `CsdlModel.copy ← KeyModel.<init> ← parseKey`.
**Fix:** `requireAttr(event.asStartElement(), "Name", ...)` with the entity name in the message.

---

## Low / polish

- **L1.** `RequestGenerator.generateEntityRequest` emits `public class` (:74) while collection requests are `public final class` — decision 36 inconsistency (no varargs on entity requests, so cosmetic).
- **L2.** `Names.resolvedClassName/resolvedSuffix` fall back UNKNOWN→ENTITY silently — a metadata typo (`NS.Thinng`) surfaces as a confusing unresolved-import compile error instead of a generation-time "unknown type" diagnostic naming the property.
- **L3.** `ContainerGenerator` doesn't validate that an EntitySet/Singleton type resolves to an ENTITY kind — a complex-typed set emits imports to a nonexistent `CollectionRequest`.
- **L4.** Dead code: `Names.unqualifyType` has zero callers (verified by grep). `KeyModel.aliases` is captured but unconsumed (known/intentional per the L19 resolution — recorded here for completeness).
- **L5.** `parseEntityType`/`parseComplexType`/`parseEntityContainer` match children by localName only (no EDM-namespace check) — a vendor-namespace child literally named `Property` would be parsed as one. The root/`Schema` level *is* namespace-strict; children aren't.
- **L6.** `mergeContainerInheritance`'s `namespaceOf` map is keyed by record value-equality — two structurally identical containers in different namespaces collapse to one entry (harmless edge today).
- **L7.** `FunctionImport@Function` uses `getAttr` (not required) — null can enter the model; inconsistent with `requireAttr` everywhere else (moot until imports are generated, but the parse contract should be uniform).

---

## Suggested priority

1. **H2** (one-line fix, silent uncompilable output), then **H1** (constants + enum members + round-trip map).
2. **M1** (container collision check), **M5** (`requireAttr`), **M4** (builder `changed.add`) — all small.
3. **M2** (resolved enum literal), **M3** (reject duplicate `<Key>`).
4. Lows opportunistically; L2 would noticeably improve metadata-typo diagnostics.

## Overall assessment

The core held up well under adversarial re-verification — the hardening from rounds 1–3 (typed keys, collision detection, alias resolution, Extends merge, dedup) all re-verified correct, and two of my own static-review suspects were refuted by execution. The genuine gaps cluster in one theme: **member-name handling at the edges** — constants were never given the sanitization that fields/getters got (H1/H2), and containers/keys/`PropertyRef` lack the collision-and-validation discipline that entity members now have (M1/M3/M5). Notably, three of the five confirmed defects produce *output that compiles or generation that "succeeds"* while being wrong — a reminder that compile-only tests can't catch them; the round-3 lesson about contract-driven tests applies here too.
