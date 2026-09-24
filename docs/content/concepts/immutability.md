# Entity Immutability

Generated model classes are immutable-by-contract rather than Java records with final fields. They expose copy-on-write methods for application updates while retaining the public setters and no-argument constructor required by Jackson.

## Model Contract

A generated entity has protected fields, typed setters, and immutable views at its public data boundaries:

```java
public final class Person implements ODataEntityType {
    protected String userName;
    protected String firstName;
    protected List<String> emails;

    @JsonProperty("FirstName")
    public void setFirstName(String value) {
        this.firstName = value;
    }

    public String getUserName() {
        return userName;
    }

    public List<String> getEmails() {
        return emails == null ? List.of() : Collections.unmodifiableList(emails);
    }
}
```

A property declared `Nullable="true"` gets an `Optional<T>` getter. A non-nullable scalar or key getter returns its boxed Java type directly. This distinction follows the CSDL declaration; callers should not assume that every getter returns `Optional`.

Generated entity and complex-type classes are annotated with Jackson `@JsonProperty` on their setters. Derived types also emit a getter-only `@JsonProperty("@odata.type")` for subtype payloads. Lifecycle values such as `changedFields`, `getKey()`, `getETag()`, and context paths are excluded from wire serialization by runtime interface annotations.

## Copy-on-Write Updates

When `generateWithMethods` is enabled, each `with*()` method creates a new instance, copies the remaining state, defensively copies collections and dynamic-property maps, and merges the changed CSDL name into `changedFields`:

```java
Person updated = original.withFirstName("Scotty");
```

The original instance is not changed. A patch generated from a Builder-created or `with*()`-created entity can therefore send only the tracked fields. Public setters used for ordinary updates deliberately do not mark fields as changed; that path uses full-body PATCH semantics.

## Builders

Concrete top-level entities and complex types receive a static `builder()` method. Subtypes and abstract types do not receive a conflicting subtype builder; use inherited state and `with*()` methods where applicable.

```java
Person person = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .lastName("Ketchum")
    .emails(List.of("scott@example.com"))
    .build();
```

Builder-created entities track every field set through the builder. This is what makes a later generated `patch(...)` call a partial update when the entity also has a non-empty changed-field set.

## Serialization

`JacksonSerializer` is the default implementation. It uses the OData JSON configuration for ISO 8601 temporal values, preserves service offsets, serializes generated enum wire names, and omits lifecycle metadata and empty collections from ordinary write bodies. A custom `Serializer` can be supplied through `Context.builder().serializer(...)`, but it must implement the same model and OData wire contract.

## Enabling `with*()` Methods

The Maven plugin default is `false`:

```xml
<configuration>
    <generateWithMethods>true</generateWithMethods>
</configuration>
```

Disabling the option reduces generated source size for large schemas. Builders and setters remain available, and requests still support CRUD, media, `$ref`, and batch operations.

## What's Next

- [CSDL Metadata Parsing](csdl-parsing.md) — Metadata model
- [Generated Code Structure](../reference/generated-code.md) — Detailed model and request output
