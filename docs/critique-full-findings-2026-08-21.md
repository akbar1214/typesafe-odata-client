# Full Code Critique — All Findings (2026-08-21)

> **Scope:** `main` @ `2ccd4cd` (20 Aug 2026) — `odata-codegen-core`, `odata-codegen-runtime`, `odata-codegen-maven-plugin`, `pom.xml`, `docs/`  
> **Branch:** `fix/critique-h1-h6` (failing tests for H1–H6 committed in `53d8131`)  
> **Total:** 38 distinct bugs / risks — 12 High, 14 Medium, 12 Low + Doc drift. Each entry gives `file:line`, exploit/impact, and explanation.

---

## 1. Summary Table

| ID | Severity | Area | One-line | Primary file |
|----|----------|------|----------|--------------|
| H1 | 🔴 High | Parser | `parseAction` ReturnType alias not resolved | `StaxCsdlParser.java:415` |
| H2 | 🔴 High | Parser | Cross-schema alias scope single-schema | `StaxCsdlParser.java:30,574` |
| H3 | 🔴 High | Generator | Cross-schema base marked `final` (unqualified BaseType) | `EntityGenerator.java:335` |
| H4 | 🔴 High | Runtime | `encodeQueryParam` restores `=` inside value (injection) | `ContextPath.java:232` |
| H5 | 🔴 High | Runtime | `encodeKeyValue` misses `/` `+` | `ContextPath.java:290` |
| H6 | 🔴 High | Runtime | Batch double-slash `baseUrl + "/" + "/People"` | `BatchRequest.java:130` / `ContextPath.java:169` |
| H7 | 🔴 High | Generator | `Generator.writeCode` path traversal | `Generator.java:109` |
| H8 | 🔴 High | Maven | Marker hash ignores `metadataHeaders` values | `GenerateMojo.java:252` |
| H9 | 🔴 High | Maven | 304 treated as redirect | `GenerateMojo.java:187` |
| H10 | 🔴 High | Generator | `toPackageName` illegal package `3d_model` | `Names.java:12` |
| H11 | 🔴 High | Generator | `TypeKind` cache `putIfAbsent` first-wins cross-schema | `Names.java:358` / `AbstractTypeGenerator.java:190` |
| H12 | 🔴 High | Runtime | `buildTransportChain` WeakHashMap get/put race | `EntityOperations.java:404` |
| M1 | 🟠 Med | Parser | `PropertyRef@Name` null → NPE | `StaxCsdlParser.java:284` |
| M2 | 🟠 Med | Parser | Unqualified container Extends nondeterministic | `StaxCsdlParser.java:644` |
| M3 | 🟠 Med | Generator | `sanitizeClassName` drops `-` → collision `A-B` vs `AB` | `Names.java:226` |
| M4 | 🟠 Med | Generator | Collection-of-complex typedef nav not skipped | `RequestGenerator.java:58` |
| M5 | 🟠 Med | Generator | Enum member sanitization collision `A-B` → `A_B` | `EnumGenerator.java:27` |
| M6 | 🟠 Med | Runtime | `DateTimeProperty.formatTime` nano no zero-pad | `DateTimeProperty.java:121` |
| M7 | 🟠 Med | Runtime | `NumberExpression.divide` `div` vs `divby` by value type | `NumberExpression.java:77` |
| M8 | 🟠 Med | Runtime | `any`/`all` alias `x` shadowing in nested `any` | `CollectionProperty.java:54` |
| M9 | 🟠 Med | Batch | Encoded `%0D%0A` not rejected (header injection) | `BatchOperation.java:60` |
| M10 | 🟠 Med | HTTP | Duplicate `OData-*` headers via `builder.header` | `JdkHttpTransport.java:97` |
| M11 | 🟠 Med | Serialize | `NON_EMPTY` hides intentional empty collection clear | `JacksonSerializer.java:41` |
| M12 | 🟠 Med | Maven | Temp metadata `deleteOnExit` leak | `GenerateMojo.java:210` |
| M13 | 🟠 Med | Generator | Duplicate detection only within one `generate()` | `Generator.java:118` |
| M14 | 🟠 Med | Runtime | `startsWith("http")` case-sensitive | `EntityOperations.java:193,223` |
| L1–L9 | 🟡 Low | Various | See §4 | — |
| D1–D9 | 🟡 Doc | Docs | API drift vs generated code | `docs/content/**/*.md` |

Failing-test coverage this branch: **H1–H6** have committed red tests (11 failing / 16 new). H7–H12, M1–M14 have no tests yet (open).

---

## 2. High Severity — Must Fix

### H1 — `StaxCsdlParser.java:415` Action ReturnType not alias-resolved
**Code:**
```java
// parseFunction:385
resolveTypeRef(getAttr(child,"Type"))
// parseAction:415
getAttr(child,"Type")   // ← bug: verbatim "self.Person"
```
**Impact:** `Schema Namespace="NS" Alias="self"` with `<Action><ReturnType Type="self.Person"/>` parses as `self.Person`. `Names.resolveTypeKind("self.Person")` → `UNKNOWN` → wrong import/package (should be `NS.Person`). `Function` is correct; `Action` is inconsistent.  
**Fix:** `resolveTypeRef(getAttr(child,"Type"))` (one-line).  
**Test:** `StaxCsdlParserActionAliasTest.actionReturnTypeAliasIsResolved` — expects `NS.Person`, gets `self.Person` (red).

### H2 — `StaxCsdlParser.java:30-33,574-585` Cross-schema alias not resolved
`currentAlias`/`currentNamespace` mutated per `parseSchema:105`. `resolveTypeRef` only checks `currentAlias`:
```java
if (inner.startsWith(currentAlias + ".")) inner = currentNamespace + ...
```
Schema B `a.Foo` where `a` belongs to Schema A is not resolved; stored as `a.Foo`.  
**Impact:** Any `a.Foo`, `Collection(a.Foo)` cross-schema stays `a.Foo` → generator emits `UNKNOWN` type, wrong suffix. Single-schema alias tests pass, multi-schema fails.  
**Fix:** Global `Map<alias,namespace>` built in `parseSchema`, `resolveTypeRef` looks up prefix in map.  
**Test:** `StaxCsdlParserCrossSchemaAliasTest` — expects `NS.A.Foo`, gets `a.Foo` (2 red).

### H3 — `EntityGenerator.java:335-352` (+ `ComplexTypeGenerator`) cross-schema `final`
`extendedBasesForSchema(schema)` decides `public class` vs `public final class`:
```java
for (s : effectiveSchemas) for (et : s.entityTypes()) {
  String baseNs = namespaceFromFullName(et.baseType());
  if (baseNs.isEmpty()) baseNs = s.namespace();
  if (baseNs.equals(ns)) bases.add(...);
}
```
Qualified `BaseType="NS.A.Base"` → `baseNs=NS.A` matches `ns=NS.A` (Base’s schema) → correctly non-final. **Bug remains for unqualified** `BaseType="Base"` cross-schema: `baseNs=""` → `s.namespace()=NS.B` ≠ `NS.A` → not added → `Base` emitted `public final class Base` yet `Derived` in `NS.B` does `extends Base` → compile error or lost inheritance.  
**Test:** `CrossSchemaInheritanceFinalTest.crossSchemaUnqualifiedBaseIsNotFinal` — expects `public class Base`, gets `public final class Base` (red); qualified variant already passes.

### H4 — `ContextPath.java:232-242` `encodeQueryParam` restores `=`
```java
case "3D" -> sb.append('='); // %3D → =
```
`URLEncoder.encode("a=b")` → `a%3Db` → restored to `a=b`. Then:
```java
sb.append(encodeQueryParam(name)).append("=").append(encodeQueryParam(value));
```
produces `?q=a=b` → parsed as `q=a` + stray `b`.  
**Impact:** Any filter/query value containing `=` (e.g. `filter("Name eq 'a=b'")`) injects a spurious param separator.  
**Fix:** Remove `case "3D"`; `=` must stay `%3D`. Valid separator `=` is added once between name and value in `appendSegments:213`.  
**Test:** `ContextPathEncodeQueryParamTest` — `addQuery("q","a=b")` expects `a%3Db` (2 red).

### H5 — `ContextPath.java:290-305` `encodeKeyValue` misses `/` `+`
```java
switch(c) { case '\'' -> "''"; case '&' -> "%26"; case '?' -> "%3F"; case '#' -> "%23"; case '%' -> "%25"; case ' ' -> "%20"; default -> c; }
```
Missing `'/'=>%2F`, `'+'=>%2B`. `People('a/b')` where `/` is a path separator splits into `People('a` / `b')`; `a+b` ambiguous (some servers decode `+` as space).  
**Fix:** Add `'/' -> "%2F"`, `'+' -> "%2B"` (plus control chars if desired).  
**Test:** `ContextPathEncodeKeyValueSlashPlusTest` — expects `%2F`/`%2B` (3 red).

### H6 — `BatchRequest.java:130` + `ContextPath.java:169` double-slash
```java
// BatchRequest.java:130
url = baseUrl + "/" + url;
// ContextPath.toRelativeUrl() returns "People('scott')" (no leading slash), but
// BatchOperation.get("/People('scott')") has leading "/"
```
Result: `https://svc//People('scott')`. Strict services (TripPin) reject.  
**Fix:** `url = baseUrl + (url.startsWith("/") ? "" : "/") + url;` (normalize at batch layer).  
**Test:** `BatchRequestDoubleSlashTest` — leading-slash body contains `//People` (2 red).

### H7 — `Generator.java:109-111` Path traversal
```java
String packageDir = packageName.replace('.','/');
Path dir = outputDir.resolve(packageDir);
```
No validation. `basePackage=foo/../../etc` or `schemaPackages` value `../evil` writes outside `target`.  
**Exploit:** Malicious `pom.xml` or compromised `basePackage` can overwrite `~/.m2` or `/tmp`.  
**Fix:** Validate each `packageName` segment `isJavaIdentifierStart/Part` and reject `..`, `/`, `\`, `:`.

### H8 — `GenerateMojo.java:252` marker hash ignores header values
```java
for (String name : metadataHeaders.stringPropertyNames()) config.append("header=").append(name).append('\n');
```
Rotating `Authorization: Bearer <token>` keeps same hash → second build thinks up-to-date, reuses stale metadata.  
**Fix:** Hash `name + "=" + valueDigest` or document that secrets are not hashed (and force `forceRegenerate`).

### H9 — `GenerateMojo.java:187` 304 treated as redirect
```java
if (code >=300 && code <400) { location = headers.firstValue("Location")... }
```
`304 Not Modified` has no `Location` → `MojoFailureException: Redirect without Location`. Should allow only `301,302,303,307,308`.

### H10 — `Names.java:12` `toPackageName` illegal identifier
```java
namespace.toLowerCase(ROOT).replace(".","_").replace("-","_")
```
`3D.Model` → `3d_model` (starts with digit, illegal `package 3d_model;`), `int.Model` questionable. Needs component-wise `sanitizeIdentifier`.

### H11 — `Names.java:358-377` + `AbstractTypeGenerator.java:190` `putIfAbsent` first-wins
```java
for (s: schemas) for (e: s.entityTypes()) {
  map.put(ns+"."+e.name(), ENTITY);
  map.putIfAbsent(e.name(), ENTITY); // first schema wins
}
```
Two schemas with same simple name but different kinds (`Entity` vs `Complex`) — first kind wins, second gets wrong `resolvedSuffix` → wrong package/import. Same for `typeDefinition` simple-name fallback in `AbstractTypeGenerator:213`. Should key by qualified name + require qualified lookup.

### H12 — `EntityOperations.java:404-440` interceptor chain race
`CHAIN_CACHE` is `synchronizedMap(WeakHashMap)` but `get` then `put` not atomic → duplicate chain construction (benign). More subtle: `Context` record `equals` includes `HttpTransport` identity, so two `Context` with same `baseUrl` but different `interceptors` list (identity-based `HttpInterceptor` equals) share cache correctly, but new equal `Context` pins old weak key.

---

## 3. Medium Severity — Should Fix

### M1 `StaxCsdlParser.java:284` `PropertyRef@Name` null
`getAttr(el,"Name")` not `requireAttr` inside `parseReferentialConstraint`. `null` enters `principal/dependent` → `ReferentialConstraintModel(null,…)` → NPE downstream.

### M2 `StaxCsdlParser.java:644-650` container Extends nondeterministic
Unqualified `Extends="DefaultContainer"` fallback scans `byQualifiedName.values()` (HashMap) picking first simple-name match — order random when two containers share name. Should require qualified name or fail.

### M3 `Names.java:226-248` `sanitizeClassName` drops `-`
`"A-B"` → `"AB"` (drops `-`) vs `sanitizeIdentifier` maps `-` → `_` → `A_B`. Two CSDL types `A-B` and `AB` collide to `AB.java` → duplicate detection misleading message.

### M4 `RequestGenerator.java:58` typedef-of-complex nav
`isComplexTypeNav` unwraps `Collection` then `resolveTypeKind`. If element is `TypeDefinition` of complex (`MyAddr`), `resolveTypeKind=UNKNOWN` → not skipped → generates `MyAddrEntityRequest` (complex has no request class) → uncompilable. Must unwrap `TypeDefinition` chain via `resolveTypeDefinition`.

### M5 `EnumGenerator.java:27` enum member collision
`"A-B"` sanitizes to `A_B` collides with verbatim `A_B` → `enum E { A_B(0), A_B(1) }` compile error. No `allocateConstantNames`-style dedup.

### M6 `DateTimeProperty.java:121` nano formatting
`sb.append(nano)` raw int: `10:15:30.000000001` → `10:15:30.1`, `1000000` → `10:15:30.1000000` (missing leading zeros). Use `DateTimeFormatter` `HH:mm:ss.SSSSSSSSS` trimmed.

### M7 `NumberExpression.java:77` `div` vs `divby`
Picks `div`/`divby` by `value instanceof Integer/Long`. Dividing `Edm.Double` property by `Integer 2` should be `divby` (floating). Heuristic must use property’s `Edm` type.

### M8 `CollectionProperty.java:54` nested `any` shadowing
`any(x: x/Name eq …)` inner `any(x: …)` reuses `x`, shadowing outer. Needs unique `x0,x1` (documented future work).

### M9 `BatchOperation.java:60` encoded injection
`rejectLineBreaks` checks raw `\r\n\0` only; `url=%0D%0A` passes → decoded as CRLF on server. Should decode `%` before check or reject `%0`.

### M10 `JdkHttpTransport.java:97` duplicate OData headers
`builder.header("OData-MaxVersion","4.01")` always adds; if caller supplied same header, `builder.header` adds second value `4.01, 4.01`. Should dedup case-insensitively.

### M11 `JacksonSerializer.java:41` `NON_EMPTY` hides clears
Empty collections omitted even when user explicitly clears `tags.withTags(List.of())` → `Tags: []` omitted → no clear. Need `serializeIncludeEmpty` escape.

### M12 `GenerateMojo.java:210` temp leak
`Files.createTempFile(...); deleteOnExit()` never `delete()` after `parseMetadata` → `/tmp` accumulation in daemon/-T builds.

### M13 `Generator.java:118` duplicate detection per-call only
`written` cleared per `generate()`. Across incremental builds, `isUpToDate=true` skip leaves old `.class` on classpath after type rename.

### M14 `EntityOperations.java:193,223` case-sensitive `http`
`startsWith("http")` fails `HTTP://` → incorrectly resolved as `baseUrl + "/HTTP:..."`. Use `regionMatches(true,0,"http",0,4)`.

---

## 4. Low Severity & Doc Drift

**L1** `Names.java:299` `isReservedWord` missing `when`, `non-sealed` (Java 17/21) — enum member `when` verbatim breaks.  
**L2** `Names.java:102` `toConstantName` camelCase split only `Upper + lower(prev lower)` → `XMLHttp → XMLHTTP` not `XML_HTTP`.  
**L3** `ContextPath.java:143` malformed `%ZZ` left verbatim → re-encodes as `%25ZZ`.  
**L4** `ContextPath.java:273` `formatTypedValue` default `String.valueOf(value)` for unknown `edmType` may emit unquoted strings.  
**L5** `MultipartHelper.java:32` `BOUNDARY_PATTERN` requires `boundary=` no spaces → `boundary = "abc"` (legal) missed → changeset not decoded.  
**L6** `MultipartHelper.java:156` missing closing `--` when body ends at `--boundary` not detected → partial results silent.  
**L7** `CollectionPage.java:16` `unmodifiableList(currentPage)` without copy → caller mutation still visible.  
**L8** `BatchResponse.java:31` `getByContentId` NPE when `contentId==null` — should use `Objects.equals`.  
**L9** `DynamicPropertyConverter` duplicate mapper config — minor.  

**Docs drift** (verified vs generated API):

* `immutability.md:30` claims `record Person` — actual `final class` with protected fields.
* `select-order.md:29` `select(Person.FIRST_NAME, Person.TRIPS)` — `TRIPS` is `NavProperty` not `PropertyExpression` (compile error).
* `query-api.md:21` missing `StringProperty.greaterThan/lessThan`, `toLower/toUpper/concat`.
* `error-handling.md:42` `filter("invalid")` — no `filter(String)`; use `FilterExpression.of(...)`.
* `error-handling.md:91` `client.people().post(...)` — actual `create(...)`.
* `contributing.md:37` `mvn verify -Pintegration-tests` — profile is `live-tests`.
* `reference/http-transport.md:42` duplicate `### JdkHttpTransport` claims Apache but shows `JdkHttpTransport`.
* `reference/packages.md:52` duplicate `HttpTransport` missing `ContextPath`/`BatchRequest` etc.
* `README.md:229` snippet omits `GuidProperty`/`EnumProperty`/`DateTimeProperty`.

---

## 5. Tests — Branch `fix/critique-h1-h6`

| Test class | H | Expect | Actual (pre-fix) |
|------------|---|--------|-------------------|
| `StaxCsdlParserActionAliasTest` | H1 | `NS.Person` | `self.Person` ❌ |
| `StaxCsdlParserCrossSchemaAliasTest` (2) | H2 | `NS.A.Foo` | `a.Foo` ❌ (2) |
| `CrossSchemaInheritanceFinalTest` (qualified `NS.A.Base`) | H3a | `public class Base` | ✅ already passes |
| `CrossSchemaInheritanceFinalTest` (unqualified `Base`) | H3b | `public class Base` | `public final class Base` ❌ |
| `ContextPathEncodeQueryParamTest` (2) | H4 | `a%3Db` | `a=b` ❌ (2) |
| `ContextPathEncodeKeyValueSlashPlusTest` (3) | H5 | `%2F`/`%2B` | `a/b`/`a+b` ❌ (3) |
| `BatchRequestDoubleSlashTest` (2) | H6 | `…/People` | `…//People` ❌ (2) |

**11 failing / 16 new** (5 pass are control cases). After fixes: all 16 pass + existing 630.

Run: `mvn test -Dtest="StaxCsdlParserActionAliasTest,StaxCsdlParserCrossSchemaAliasTest,CrossSchemaInheritanceFinalTest" -pl odata-codegen-core -am -Dsurefire.failIfNoSpecifiedTests=false` and `mvn test -Dtest="ContextPathEncodeQueryParamTest,ContextPathEncodeKeyValueSlashPlusTest,BatchRequestDoubleSlashTest" -pl odata-codegen-runtime -am -Dsurefire.failIfNoSpecifiedTests=false`

---

## 6. Fix Order (this branch)

H1 one-line → H2 global alias map → H3 global `allBases` → H4 remove `3D` restore → H5 add `%2F`/`%2B` → H6 normalize `resolveOperationUrl`. Each step turns its 1–3 red tests green; `mvn test` stays green for prior Hs.

Full backlog H7–H12, M1–M14 tracked in §2–§3 for next milestones.

