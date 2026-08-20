# Critique Fix Plan — H1 to H6 (Branch: fix/critique-h1-h6)

Source: full codebase critique @ `main` (`2ccd4cd`), 20 Aug 2026. This file tracks the 6 High-severity bugs selected for immediate fix.

## Scope

| ID | Area | Bug | File(s) |
|----|------|-----|---------|
| **H1** | Parser | `parseAction` ReturnType missing `resolveTypeRef` — alias-qualified types stored verbatim | `odata-codegen-core/src/main/java/io/github/akbarhusain/odata/core/parser/StaxCsdlParser.java:415` |
| **H2** | Parser | Alias scope single-schema — cross-schema `AliasA.Type` not resolved | `StaxCsdlParser.java:30-33,574-585` |
| **H3** | Generator | Cross-schema inheritance `final` — `extendedBasesForSchema` only marks same-namespace bases, so cross-schema base emitted `final` and cannot be extended | `EntityGenerator.java:335-352` + `ComplexTypeGenerator` equivalent |
| **H4** | Runtime | `ContextPath.encodeQueryParam` restores `=` inside value — injects spurious query-param separator | `ContextPath.java:220-250` |
| **H5** | Runtime | `ContextPath.encodeKeyValue` misses `/` and `+` — `People('a/b')` splits path, `a+b` ambiguous | `ContextPath.java:290-305` |
| **H6** | Runtime | Batch double-slash — `toRelativeUrl` leading `/` + `BatchRequest.resolveOperationUrl` prepends another `/` | `ContextPath.java:169-173` + `BatchRequest.java:127-135` |

Out-of-scope this branch (deferred): H7-H12, M1-M14, L1-L9, doc drift.

## Reproduction Strategy (Tests First, TDD)

Each H gets a dedicated failing test class committed before the fix:

* **H1** — `StaxCsdlParserActionAliasTest` (core) — XML with `Alias="self"` and `<Action><ReturnType Type="self.Person"/>`. Assert parsed `ActionModel.returnType.type()` == `Com.Example.Model.Person` (currently fails: `self.Person`).
* **H2** — `StaxCsdlParserCrossSchemaAliasTest` (core) — Two schemas: `Schema A Namespace="NS.A" Alias="a"` with type `Foo`; `Schema B` property `Type="a.Foo"`. Assert second schema’s property edmType resolves to `NS.A.Foo` (currently fails: `a.Foo`).
* **H3** — `CrossSchemaInheritanceFinalTest` (core) — Two schemas: `Base` in `NS.A`, `Derived` in `NS.B` with `BaseType="NS.A.Base"` (fully qualified). Generator emits `Base.java`; assert source contains `public class Base` (not `public final class Base`) and `Derived extends Base` compiles via `GeneratorCompilation` harness.
* **H4** — `ContextPathEncodeQueryParamTest` (runtime) — `new ContextPath(BASE).addQuery("$filter","Name eq 'a=b'").toUrl()` — assert contains `%3D` not literal `=` inside value; currently restores `=` → fails.
* **H5** — `ContextPathEncodeKeyValueSlashPlusTest` (runtime) — `addKey("Id","a/b")` → `%2F`; `addKey("Id","a+b")` → `%2B`. Currently fails: literal `/`/`+` present.
* **H6** — `BatchRequestDoubleSlashTest` (runtime) — `new BatchOperation.get("/People('x')")` via `BatchRequest` with `baseUrl=https://svc` — capture request URL via `HttpTransport` mock; assert `https://svc/People('x')` not `https://svc//People('x')`.

Tests are expected to **fail** on current `main`, **pass** after fix. CI: `mvn -Dtest=<TestClass> test`.

## Fix Outlines

### H1 — One-line patch
```java
// StaxCsdlParser.java:415
- getAttr(child,"Type")
+ resolveTypeRef(getAttr(child,"Type"))
```
Mirrors `parseFunction:385`. Also audit `parseAction` `Parameter` path (already correct via `parseParameter`).

### H2 — Global alias map
* Build `Map<String,String> aliasToNamespace` from `schema.alias() → namespace` while parsing (after `parseSchema` or at `resolveTypeRef` via scanning `schemas` already parsed). Prefer per-schema map keyed by `currentNamespace`? Simpler: maintain `Map<String,String> globalAliasMap` populated in `parseSchema:102-106`. `resolveTypeRef` looks up `inner.substring(0,dot)` in global map, not just `currentAlias`.
* No breaking change: unqualified/simple types still fall through.

### H3 — Global base tracking
* `EntityGenerator.extendedBasesForSchema` currently filters `baseNs.equals(ns)` — remove filter or add global `Set<String> allBases` checked in `generate()` class declaration: `isBase = globalBases.contains(className)` or keep per-schema but populate from all `effectiveSchemas`. Same for `ComplexTypeGenerator`.
* Declaration becomes: `if (base!=null || globalBases.contains(className)) → public class` else `public final class`.

### H4 — Keep `=` encoded
* `ContextPath.encodeQueryParam:232-242` — remove `case "3D" -> sb.append('=')`. `=` must stay `%3D`. Valid OData query options use `=` as separator *between* name/value (added at `appendSegments:213-215` via `kv.name + "=" + encodeValue`), not inside values. Restored safe set: `$ ' ( ) , / : @` only.

### H5 — Encode `/` and `+` in keys
* `ContextPath.encodeKeyValue:290-305` add cases: `'/' → %2F`, `'+' → %2B` (also encode control chars if desired). Keeps `''` doubling for `'`.

### H6 — Trim slash before concat
* `BatchRequest.resolveOperationUrl:130`:
```java
- url = baseUrl + "/" + url;
+ url = baseUrl + (url.startsWith("/") ? "" : "/") + url;
```
And/or normalize `ContextPath.toRelativeUrl` to not produce leading `/` if caller already handles. Prefer fix at batch layer (single place).

## Verification

1. `mvn test -Dtest=StaxCsdlParserActionAliasTest,StaxCsdlParserCrossSchemaAliasTest,CrossSchemaInheritanceFinalTest` (core)
2. `mvn test -Dtest=ContextPathEncodeQueryParamTest,ContextPathEncodeKeyValueSlashPlusTest,BatchRequestDoubleSlashTest` (runtime)
3. Full reactor: `mvn test` (offline) — expect all 6 new tests green + existing 630 unchanged.
4. Manual: `mvn -pl odata-codegen-core test` and `mvn -pl odata-codegen-runtime test`.

## Branch & Workflow

* Branch: `fix/critique-h1-h6` (created from `main`)
* Commit 1: this plan file
* Commit 2: failing tests (all 6 red)
* Commits 3-8: fixes one H at a time (green per H)
* Final: `mvn test` green, push, PR

## References

* Critique report: trajectory Aug 20 2026 (38 bugs, H1-H6 detailed)
* Prior lessons: `AGENTS.md` lessons 51-120; `ContextPath` lesson 12 (OData-safe restore), H4 tightens it.
