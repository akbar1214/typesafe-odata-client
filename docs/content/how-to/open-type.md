# Work with OpenType Dynamic Properties

Some OData services return **extra JSON fields** on an entity that aren't declared in
the CSDL schema. These services mark the type `OpenType="true"` (e.g. TripPin
`Person`, `Event`, `Location`; OData Demo `Category`). The generated client captures
those dynamic properties so they aren't lost.

## Reading a Dynamic Property

```java
Person scott = client.peopleByUserName("russellwhyte").get();

// Whole map of undeclared fields
java.util.Map<String, Object> dynamic = scott.getUnmappedFields();
String nickname = (String) dynamic.get("Nickname");

// Single field, type-safe Optional access
scott.getDynamicProperty("Nickname").ifPresent(v -> System.out.println(v));

// Typed access — coerces the stored Jackson value into your class:
// nested objects become POJOs, numbers coerce (e.g. Integer -> Long).
City homeCity = scott.getDynamicProperty("HomeCity", City.class).orElseThrow();
Long age = scott.getDynamicProperty("Age", Long.class).orElseThrow();
```

`getUnmappedFields()` returns an **unmodifiable** view — you can't mutate the entity's
internal map. The typed `getDynamicProperty(name, Class)` overload throws
`IllegalArgumentException` if the value can't be coerced to the requested type.

## What Gets Captured

- Any JSON field not declared as a static property is captured into `unmappedFields`.
- `@odata.*` control annotations (`@odata.id`, `@odata.editLink`, ...) are **filtered
  out** automatically.
- Openness propagates to subtypes: a subtype of an open base type also captures dynamic
  fields (they are stored in the base's `unmappedFields`).

## Round-Tripping

Dynamic properties are re-serialized when you POST/PATCH the entity, so they survive a
write cycle:

```java
scott.getDynamicProperty("Loyalty")
    .ifPresent(v -> System.out.println("loyalty=" + v));
// On serialize(), "Loyalty" is emitted as a top-level JSON field again.
```

## What's Next

- [Aggregate with $apply](aggregate.md) — server-side transformations
- [Select and Order Results](select-order.md) — `$select`/`$orderby`
