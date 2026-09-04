# Plan: Lambda Query API on Option (d) — `Expandable` + `NavProperty` Removal

**Status:** Implemented on `feature/lambda-query-api` (decision 97 in AGENTS.md; test
inventory in the decision's verification line — a few counts drifted from §7 during
implementation, e.g. `NavQueryExpandTest` 30, `CollectionPropertyTypedLambdaTest` 19).
**Parent proposal:** `LAMBDA_QUERY_API_PLAN.md` (selector lambdas, full operation set).
**Scope:** Selector lambdas for orderBy / expand / filter at **unlimited nesting depth**,
built on the collapse of `NavProperty` into `NavQuery` + a new sealed `Expandable`
interface. A method-level generic cannot type a selector lambda (inference flows from the
target type, never the reverse), so the selector type rides as a class-level parameter:
`NavQuery<S, T, Sel>`, `CollectionProperty<E, T, F, Sel>`. Complex types get no `Selector`
(same boundary as the parent plan).
**Full operation set** — select / orderBy / expand / filter all carry lambda overloads.
Decision revisited: an earlier draft excluded `select` because `select(p -> p.A, p -> p.B)`
is strictly longer than `select(Person.A, Person.B)` and allocates a `Selector` per call —
both points stand, but one consistent mental model across every read-shaping operation was
judged worth that trade. Constants remain the short spelling; lambdas remain opt-in.
**Design deltas vs parent plan:** §2.2's `expand`/`expandQuery` split is deleted (one
constant-form + one lambda-form — different erasures, no clash); §2.3's "factory component
on the `NavQuery` record" is replaced by the D1 rule (§3).

---

## 1. Step-0 finding (validates this design)

The parent plan's two lambda `expand` overloads do not compile — hard error, not ambiguity:

```
error: name clash: expand(Function<...NavQuery...>)
  and expand(Function<...NavProperty...>) have the same erasure
```

`Function<S, NavProperty<…>>` vs `Function<S, NavQuery<…>>` differ only in type arguments,
which erase. Verified with a scratch probe (`javac --release 17` against the runtime classes):
after renaming the twin to `expandQuery`, all four lambda shapes (bare nav, collection nav,
chained options, block body) compile and route correctly. This plan removes the need for the
rename entirely: with only `NavQuery` in the world there is exactly one lambda `expand`.

---

## 2. New API examples (user-facing)

All existing constant call sites keep compiling (constants change type, not name).
Lambdas are additive and opt-in.

### 2.1 `select` — lambda mirrors constant one-to-one

```java
// before (unchanged)
client.people().select(Person.FIRST_NAME, Person.LAST_NAME).get();

// after
client.people().select(p -> p.FIRST_NAME, p -> p.LAST_NAME).get();
```

The lambda spelling is intentionally longer than the constant spelling — `select` lambdas
exist for one consistent mental model across every read-shaping operation, not for
ergonomics (there is no nesting at the request level to make fluency pay off). Type safety
is identical: `p` is a `Person.Selector`, so a foreign property is not a member. The
overload is pure sugar — it applies each lambda to a fresh `Selector` and delegates to the
existing `PropertyExpression…` form, inheriting the transformation guard unchanged.
(`CollectionProperty.select(t -> …)` inside `expand` is unaffected: that lambda builds a
nested `$select` option chain, which is expand's feature — see §2.3.)

### 2.2 `orderBy` / `filter` — lambda mirrors constant one-to-one

```java
// before (unchanged)
client.people().orderBy(Person.USER_NAME.asc()).get();
client.people().filter(Person.FIRST_NAME.equalTo("Scott")).get();

// after
client.people().orderBy(p -> p.USER_NAME.asc()).get();
client.people().filter(p -> p.FIRST_NAME.equalTo("Scott")).get();
```

Collection-lambda filters (`any`/`all`) are untouched by this plan:

```java
client.people().filter(Person.TRIPS.any(t -> t.BUDGET.greaterThan(500f))).get();
```

### 2.3 `expand` — one constant form, one lambda form

```java
// before (all still compile: constants only changed type)
client.people().expand(Person.PHOTO).get();                        // single-valued nav
client.people().expand(Person.TRIPS).get();                        // bare collection nav
client.people().expand(Person.TRIPS, Person.FRIENDS).get();        // multi
client.people().expand(Person.TRIPS.select(Trip.NAME)).get();      // nav + options

// after: lambda form
client.people()
    .expand(p -> p.PHOTO)
    .expand(p -> p.TRIPS)
    .expand(p -> p.TRIPS.select(t -> t.NAME))
    .expand(p -> p.TRIPS
        .select(t -> t.NAME)
        .filter(t -> t.BUDGET.greaterThan(500f))
        .top(2))
    .get();
```

Nested expands take lambdas at every hop (D1 full depth, §3) — each hop's factory arrives
with the nav value, so one `Sel` parameter composes recursively:

```java
client.people()
    .expand(p -> p.TRIPS.select(t -> t.NAME)
        .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID)))
    .get();
// renders: Trips($select=Name;$expand=PlanItems($select=PlanItemId))
```

### 2.4 Casts (`as`) — unchanged spelling, constants still work inside lambdas

```java
// before (unchanged)
client.versions().expand(VERSIONS_AS_DOC).get();

// after
client.versions().expand(v -> v.VERSIONS.as("NS.Doc", Doc.class)).get();
client.versions()
    .expand(v -> v.VERSIONS.as("NS.Doc", Doc.class).select(Doc.TITLE))
    .get();
```

### 2.5 What the compiler rejects (all compile errors, never runtime 400s)

```java
client.people().expand(Trip.FLIGHTS);          // cross-entity constant: Expandable<Trip> !<: Expandable<? super Person>
client.people().expand(p -> p.FLIGHTS);        // cross-entity lambda: FLIGHTS is not a member of Person.Selector
client.people().select(p -> p.NOT_A_MEMBER);   // cross-entity select lambda: not a member of Person.Selector
client.people().expand(p -> p.PHOTO.select(Photo.NAME.toLower()));  // transformation in nested $select (inherited guard)
client.people()
    .expand(p -> p.TRIPS.select(t -> t.NAME)     // full depth: every level takes lambdas
        .filter(t -> t.BUDGET.greaterThan(1f))
        .orderBy(t -> t.NAME.desc())
        .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID)));
```

Factory-less queries fail fast on lambdas (never silently): hand-built
`new CollectionProperty<>(…)` (≤4 args), `NavQuery.of(name)`, `NavQuery.raw(…)`, and the
2-arg `as()` carry no factory — constants chain fine there, lambdas throw
`IllegalStateException` (same shape as `any()`'s fail-fast).

---

## 3. D1 rule (full lambda depth via class-level `Sel`)

A method-level generic (`<Sel> select(Function<Sel, …>)`) cannot type a selector lambda:
inference flows from the target type into the lambda, never the reverse, so `Sel` would
collapse to `Object` and `t.NAME` would not resolve. The selector type must already be
nailed down in the receiver's type — hence class-level parameters:

- `NavQuery<S, T, Sel>` — `Sel` is T's selector type; 10th record component
  `Supplier<Sel> selectorFactory`, propagated through all builder construction sites.
- `CollectionProperty<E, T, F, Sel>` — fourth parameter appended; new 5-arg full
  constructor `(edmName, entityType, elementType, filterableFactory, selectorFactory)`,
  older ctors delegate with nulls (all 27 existing `new CollectionProperty` call sites
  compile unchanged). `Filterable`-inner fields declare the type with a wildcard
  (`CollectionProperty<…, Trip.Filterable, ?>`) and keep 4-arg construction — they serve
  `any`/`all`, never selector lambdas, and the wildcard is honest about that.

One `Sel` suffices at every depth because each hop's factory arrives *with the value*:
the request builds `new Person.Selector()` concretely → `p.TRIPS` carries
`Trip.Selector::new` → `.select(t -> …)` returns a `NavQuery` propagating it →
`.expand(u -> …)` (new `NavQuery` lambda overload) applies the carried factory →
`u.PLAN_ITEMS` carries hop 3's factory. Full-depth chains (§2.3/§2.5) compose recursively.

### 3.1 Factory rules (uniform with the `filterableFactory` precedent)

| Construction | Factory | Lambda-after | Constants-after |
|---|---|---|---|
| Generated nav constant / `NavQuery.of(name, Sel::new)` / 3-arg `as()` | Wired | ✓ | ✓ |
| `NavQuery.of(name)` / `raw(…)` / 2-arg `as()` / hand-built `new CollectionProperty<>(…)` (≤4 args) | null | Fail-fast `IllegalStateException` (same shape as `any()`'s) | ✓ |

### 3.2 `as()`-factory swap

Narrowing the type must narrow the factory. Two overloads on both `NavQuery` and
`CollectionProperty` (factory appended last, matching ctor convention):

```java
NavQuery<S,S2,?> as(String qualifiedCast, Class<S2> subtype);                                    // nulls factory
<S2 extends T, Sel2> NavQuery<S,S2,Sel2> as(String qualifiedCast, Class<S2> subtype, Supplier<Sel2> selectorFactory);
```

Generated cast constants use the 3-arg form (the generator knows the subtype):

```java
public static final NavQuery<Container, Doc, Doc.Selector> VERSIONS_AS_DOC =
    VERSIONS.as("NS.Doc", Doc.class, Doc.Selector::new);
```

### 3.3 Record pollution: acknowledged and contained

The `Supplier` component joins the record's `equals`/`toString`, and method-ref suppliers
are distinct objects per evaluation — so instance equality was never meaningful.
Containment: (a) evidence — the suite asserts *rendered strings* everywhere, and generated
requests store rendered strings (lesson 157), so `NavQuery` equality is unrelied-upon;
(b) a convention test pins it — identically-built queries assert equal *rendering*, never
`assertEquals` on instances — so a future reader leaning on record equality gets an
explanation, not a silent trap.

---

## 4. Runtime changes (`odata-codegen-runtime/.../query/`)

### 4.1 New `Expandable.java` (+1 file, ~8 lines)

```java
public sealed interface Expandable<E> permits NavQuery, CollectionProperty {
    String toODataExpand();
}
```

Names the concept "things that can appear in `$expand`". The single type parameter is the
*source* entity (what scoping checks need); `NavQuery<S,T,Sel> implements Expandable<S>`,
`CollectionProperty<E,T,F,Sel> implements Expandable<E>`.

### 4.2 `NavProperty.java`: deleted, methods relocated

| Today on `NavProperty` | After |
|---|---|
| `select/filter/orderBy/top/skip/count/expand/as` builders | Copied onto `CollectionProperty` (same bodies; `edmName` becomes its own field) |
| `selectableName` (static), `requireNonNegative` (private static) | Move to `NavQuery` as package-private statics (same package — visible to `CollectionProperty`) |
| `as(qualifiedCast, subtype)` | Added to `NavQuery` (new: single-valued-nav cast, parity for the deleted method); `CollectionProperty` keeps its inherited copy as its own method |
| `getEdmName/getEntityType/getNavType`, 3-arg ctor | Deleted (`getNavType` has zero call sites; rendering uses names only) |

### 4.3 `NavQuery<S, T, Sel>` (record + one component)

- `implements Expandable<S>` (already has public `toODataExpand()`).
- New 10th component `Supplier<Sel> selectorFactory`, propagated through all builder
  construction sites; 9-arg and 8-arg convenience constructors delegate with null.
- New `public static <S, T, Sel> NavQuery<S, T, Sel> of(String edmName)` (null factory)
  and `of(String edmName, Supplier<Sel> selectorFactory)` (greenfield; generated
  single-nav constants use the 2-arg form).
- New lambda overloads `select` / `filter` / `orderBy` /
  `expand(Function<Sel, ? extends Expandable<? super T>>)` — the last is the hop-2+
  enabler (applied with the carried factory). Each null-checks the factory (fail-fast),
  applies it, and delegates to the existing constant form (inheriting the
  transformation guard, parenthesization, and rendering unchanged).
- Second `expand` overload widens from `NavProperty<? super T, ?>...` to
  `Expandable<? super T>...`.
- `as()` gains the 3-arg factory-swapping form (§3.2); the 2-arg form nulls the factory.

### 4.4 `CollectionProperty<E, T, F, Sel>`

- `implements Expandable<E>`; add `toODataExpand()` returning `edmName` (bare collection nav).
- New `selectorFactory` field + 5-arg full constructor (older ctors delegate with nulls —
  same pattern as `filterableFactory`) + `select` / `filter` / `orderBy` / `expand`
  lambda overloads with the same fail-fast `IllegalStateException` shape as `any`/`all`
  (`CollectionProperty.java:37-42`).
- The 8 moved-over constant builders (`select/filter/orderBy/top/skip/count/expand×2/as`)
  construct `NavQuery<E, T, Sel>` carrying the factory through.

---

## 5. Core generation changes (`odata-codegen-core/.../generator/`)

### 5.1 `EntityGenerator`

Single-valued nav constants change type (name stable — call sites rebind) and wire the
factory (the generator knows the target type):

```java
// before
public static final NavProperty<Person, Photo> PHOTO =
    new NavProperty<>("Photo", Person.class, Photo.class);
// after
public static final NavQuery<Person, Photo, Photo.Selector> PHOTO =
    NavQuery.of("Photo", Photo.Selector::new);
```

Collection nav constants gain the type parameter and the selector factory argument:

```java
public static final CollectionProperty<Person, Trip, Trip.Filterable, Trip.Selector> TRIPS =
    new CollectionProperty<>("Trips", Person.class, Trip.class,
        Trip.Filterable::new, Trip.Selector::new);
```

(`Filterable`-inner fields use `CollectionProperty<…, Trip.Filterable, ?>` with unchanged
4-arg construction — wildcard, no factory.) Cast constants use the 3-arg `as()` (§3.2):

```java
public static final NavQuery<Container, Doc, Doc.Selector> VERSIONS_AS_DOC =
    VERSIONS.as("NS.Doc", Doc.class, Doc.Selector::new);
```

New `Selector` emission per parent-plan §2.1 (shared instances, no prefix, scalars +
collections + single-valued navs):

```java
public static class Selector {
    public final StringProperty<Person> FIRST_NAME = Person.FIRST_NAME;
    public final NumberProperty<Person, Long> CONCURRENCY = Person.CONCURRENCY;
    public final CollectionProperty<Person, Trip, Trip.Filterable, Trip.Selector> TRIPS = Person.TRIPS;
    public final NavQuery<Person, Photo, Photo.Selector> PHOTO = Person.PHOTO;
}
```

### 5.2 `RequestGenerator` (4 sites: collection + entity requests)

Two `expand` overloads collapse to one constant-form plus one lambda-form. Collection
requests emit four lambda overloads (select / orderBy / expand / filter); entity requests
keep their constant `select`/`expand` surface plus the same lambda overloads for
`select` and `expand`:

```java
@SafeVarargs
public final PersonCollectionRequest select(
        java.util.function.Function<Person.Selector, ? extends PropertyExpression<? super Person, ?>>... selectors) {
    Person.Selector s = new Person.Selector();
    PropertyExpression<? super Person, ?>[] resolved = new PropertyExpression[selectors.length];
    for (int i = 0; i < selectors.length; i++) {
        resolved[i] = selectors[i].apply(s);
    }
    return select(resolved);   // inherits the transformation guard unchanged
}

@SafeVarargs
public final PersonCollectionRequest expand(Expandable<? super Person>... expands) { ... }

public final PersonCollectionRequest expand(
        java.util.function.Function<Person.Selector, ? extends Expandable<? super Person>> query) {
    Person.Selector s = new Person.Selector();
    return expand(query.apply(s));
}

@SafeVarargs
public final PersonCollectionRequest orderBy(
        java.util.function.Function<Person.Selector, ? extends OrderExpression<? super Person, ?>>... expressions) { ... }

public final PersonCollectionRequest filter(
        java.util.function.Function<Person.Selector, ? extends FilterExpression<? super Person>> predicate) { ... }
```

Same `@SafeVarargs public final` conventions; delegation inherits the transformation guard,
`getODataPath()` rendering, and filter parenthesization unchanged. Every lambda overload
erases to a type distinct from its constant-form counterpart (`Function[]`/`Function` vs
`PropertyExpression[]`/`Expandable[]`/`OrderExpression[]`/`FilterExpression`) — no clashes.

### 5.3 `Names.java:312`

Remove `"NavProperty"` from the `runtime.query.*` shadow list (the class no longer exists;
an entity named `NavProperty` becomes legal again).

---

## 6. Blast radius (inventoried, all must be touched)

- Runtime main: `NavProperty.java` (delete), `NavQuery<S,T,Sel>` (factory component +
  propagation, `of` forms, `as` forms, 4 lambda overloads, `Expandable`), `CollectionProperty<E,T,F,Sel>`
  (moved builders, both factories, 4 lambda overloads, `toODataExpand`). `EntityOperations` /
  `ContextPath` / serializers reference neither type — no changes there.
- Core main: `EntityGenerator` (constants + `Selector`), `RequestGenerator` (4 sites),
  `Names` (shadow list), `AbstractTypeGenerator` + `EntityGenerator` `Filterable`-field
  types (wildcard 4th param; construction text unchanged). `ContainerGenerator` /
  `OperationGenerator` — untouched.
- Tests: `NavPropertyExpandTest` (full rewrite → `NavQueryExpandTest`), `NavQueryValidationTest:46`
  (`TestProps.NAME` → `NavQuery.of`), `RequestGeneratorNarrowQueryTest`,
  `RequestGeneratorEntityQueryOptionsTest`, `EntityGeneratorPolymorphicExpandTest:22`,
  `EntityGeneratorFilterableTest:48` (nav-constant emission pin gains the 5th factory arg),
  `NamesPolishTest:44` (expectation flips to unreserved). The 27 generated-client `.expand(`
  call sites recompile unchanged (proof of call-site compat — asserted by the compile
  harnesses, not by hand).
- Complex types emit no nav constants and get no `Selector` — unaffected.

---

## 7. Test plan (TDD, red-first per repo discipline)

| # | Test | Asserts |
|---|---|---|
| 1 | `NavPropertyExpandTest` → rewritten `NavQueryExpandTest` | Same ~20 rendering assertions on swapped fixtures; nested-`Expandable` expand |
| 2 | New `ExpandableTest` | Bare `NavQuery` / bare `CollectionProperty` render the plain segment |
| 3 | `CollectionPropertyTypedLambdaTest` additions | First-hop `select/filter/orderBy/expand` lambdas render identically to constants; hop-2 lambda (`expand(u -> …)`) composes; factory-less fails fast; `as()` 3-arg swaps / 2-arg nulls the factory; rendering-not-instance convention pin |
| 4 | `NavQueryValidationTest` | Fixture migration (`NavQuery.of`) |
| 5 | New `EntityGeneratorSelectorTest` + nav-emission updates | `Selector` shared instances; `NavQuery.of` single-nav constants; selector-factory wiring on collections |
| 6 | New `RequestGeneratorLambdaTest` | `select`/`orderBy`/`expand`/`filter` lambda overload signatures and delegation bodies (collection & entity requests; entity = `select`+`expand` only); render-equivalence of lambda vs constant select |
| 7 | Updated pins | Narrow-query scoping via `Expandable`; entity-request options; `VERSIONS_AS_DOC` emission; `NavProperty` unreserved |
| 8 | Compile referees | TripPin/Northwind/OData Demo regenerate + compile with generated-client tests **unchanged**; new full-depth lambda-chain referee; `QueryTypeSafetyCompilationTest` + cross-entity-lambda negative |
| 9 | Behavioral | TripPin lambda/constant URL-equivalence; `-Plive-tests` green |
| 10 | Docs + bookkeeping | `expand.md`, `select-order.md`, `filter.md`, `reference/query-api.md`; AGENTS.md decision 97 + lesson + totals |

## Verification

- New tests red → green, each observed failing pre-fix (lesson 186 discipline).
- Full reactor offline green; `-Plive-tests` green.
- Generated output for existing corpora differs only by the additive emission (`Selector`
  classes + overloads + retyped constants); `NavProperty` unreferenced anywhere after the
  change. Referee: `grep -rnE "\bNavProperty\b" --include="*.java"` over the four module
  source roots → zero matches (as implemented, the generator's own methods were renamed to
  `generateNavConstant`/`generateSubtypeNavConstant`/`generateFilterableNavField` so the
  word-boundary pattern is clean; a bare `grep NavProperty` still hits legitimate
  domain-vocabulary test names like `expandSingleNavProperty` and the tests asserting the
  class's absence — those are the referee, not violations).

---

## 8. Implementation order

0. ~~Step-0 probe~~ — done (single-overload design validated; probe inlined as Appendix A).
1. Runtime, one atomic change (red: tests 1–3): the record's 3rd param and
   `CollectionProperty`'s 4th param cannot land separately (neither compiles without the
   other) — `Expandable` + `NavProperty` deletion + builder relocation + `of`/`as`
   forms + both factories + all lambda overloads incl. `NavQuery.expand`-lambda.
2. Core emission: constants with factories, `Selector`, overloads, shadow list, `Filterable`
   wildcard (red: tests 5–6).
3. Pinned-test updates (tests 4, 7).
4. Compile referees + negatives (test 8).
5. Equivalence + live + docs + AGENTS.md (tests 9–10).

## 9. Open questions

1. ~~D2 vs D1~~ — resolved: **D1 full depth** (§3).
2. **Sealed vs plain interface** for `Expandable` (recommended: sealed — documents the closed
   two-implementor set; both implementors are in the same package as required by `permits`).

---

## Appendix A. Step-0 probe (inline — the `/tmp` original is ephemeral)

Paste this appendix (or a link to it) into the implementation PR description so the
erasure evidence survives beyond `/tmp`. Test 8 re-proves the conclusion in-suite, but the
probe is the reason §5.2 has one lambda `expand` instead of two.

Compiled with `javac --release 17` against `odata-codegen-runtime/target/classes`.
With both overloads present, compilation fails:

```
error: name clash: expand(Function<PersonSelector,NavQuery<? super Person,?>>)
  and expand(Function<PersonSelector,NavProperty<? super Person,?>>)
  have the same erasure
```

(`Function<S, NavProperty<…>>` vs `Function<S, NavQuery<…>>` differ only in type arguments,
which erase — the declarations are illegal side by side, before overload resolution is
ever reached.) Renaming the twin to `expandQuery` compiles clean, and every shape routes
correctly at runtime:

```java
import io.github.akbarhusain.odata.runtime.query.*;
import java.util.function.Function;

public class Probe0 {
    static class Person {}
    static class Trip {}
    static class Photo {}

    static class PersonSelector {
        public final NavProperty<Person, Photo> PHOTO =
                new NavProperty<>("Photo", Person.class, Photo.class);
        public final CollectionProperty<Person, Trip, Object> TRIPS =
                new CollectionProperty<>("Trips", Person.class, Trip.class, null);
    }

    static class Req {
        String expand(Function<PersonSelector, NavProperty<? super Person, ?>> nav) {
            return "NAV";
        }
        // coexists only when renamed: expandQuery (see erasure error above)
        String expandQuery(Function<PersonSelector, NavProperty.NavQuery<? super Person, ?>> query) {
            return "QUERY";
        }
    }

    public static void main(String[] args) {
        Req r = new Req();
        System.out.println("photo -> " + r.expand(p -> p.PHOTO));              // NAV
        System.out.println("trips.select -> " + r.expandQuery(p -> p.TRIPS.select())); // QUERY
        System.out.println("trips -> " + r.expand(p -> p.TRIPS));              // NAV (subtype)
        System.out.println("block -> " + r.expandQuery(p -> {                  // QUERY
            return p.TRIPS.top(5);
        }));
    }
}
// output: photo -> NAV / trips.select -> QUERY / trips -> NAV / block -> QUERY
```
