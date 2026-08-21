# Entity Immutability

Why OData Codegen uses truly immutable entities.

## The Problem with Mutable Entities

Many OData clients generate mutable entities:

```java
// Traditional mutable entity (NOT our approach)
public class Person {
    protected String firstName;  // Not final!
    protected Long age;
    private Set<String> changedFields = new HashSet<>();

    public void setFirstName(String firstName) {
        this.firstName = firstName;
        changedFields.add("firstName");
    }
}
```

### Issues

1. **Thread safety** — Multiple threads can modify the same instance
2. **Hidden state** — `changedFields` tracks mutations, adding complexity
3. **Null risks** — Fields can be null even when the schema requires them
4. **Framework coupling** — Often requires `@JacksonInject`, `@JsonProperty`, etc.

## Our Approach: Immutable-by-Contract Classes

OData Codegen generates `final` classes with copy-on-write semantics:

```java
public final class Person implements ODataEntityType {
    public static final StringProperty<Person> FIRST_NAME = new StringProperty<>("FirstName", Person.class);
    public static final StringProperty<Person> LAST_NAME = new StringProperty<>("LastName", Person.class);
    public static final CollectionProperty<Person, Trip, Trip.Filterable> TRIPS = new CollectionProperty<>("Trips", Person.class, Trip.class, Trip.Filterable::new);

    protected String userName;
    protected String firstName;
    protected String lastName;
    protected List<String> emails;
    protected Long age;
    protected List<Trip> trips;
    // Builder, with*() copy-on-write, getters return unmodifiableList / Optional, Jackson @JsonProperty setters
}
```

### Benefits

1. **Immutability by contract** — `with*()` copy-on-write and `Builder` are the only mutation paths; protected fields are mutated only via Jackson setters (deserialization) or `with*()` defensive copies
2. **Thread safe** — Instances are effectively immutable after construction (`List.copyOf` / `Collections.unmodifiableList`, immutable `unmappedFields` copies)
3. **No hidden state leaks** — `changedFields` is tracked separately for `patch()` and not exposed as mutable
4. **Jackson-annotated, serializer-pluggable** — `@JsonProperty` setters for deserialization, `Serializer` interface for custom JSON handling
5. **Concise** — 30+ entity types without boilerplate

## Copy-on-Write with Builders

To "modify" an entity, create a new instance:

```java
Person original = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .lastName("Ketchum")
    .build();

// "Update" by creating new instance
Person updated = original.withFirstName("Scotty");
// original is unchanged
```

### How with*() Works

```java
// Generated in each entity
public Person withFirstName(String firstName) {
    return new Person(
        this.userName,
        firstName,           // Changed
        this.lastName,
        this.emails,
        this.age,
        this.trips
    );
}
```

## Builder Pattern

For constructing new entities:

```java
Person person = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .lastName("Ketchum")
    .emails(List.of("scott@example.com"))
    .age(42L)
    .build();
```

### Builder is a Separate Class

The builder is a static inner class:

```java
public static class Builder {
    private String userName;
    private String firstName;
    // ...

    public Builder userName(String userName) {
        this.userName = userName;
        return this;
    }

    public Person build() {
        return new Person(userName, firstName, ...);
    }
}
```

## Serialization

Generated entities have no Jackson/Gson annotations. Serialization is handled by the pluggable `Serializer` interface:

```java
// Jackson (default, only built-in implementation)
Serializer jackson = new JacksonSerializer();

// Gson / Jakarta JSON-B: implement the Serializer interface yourself
```

This means the same entity works with any JSON library.

## Configuration: `generateWithMethods`

Copy-on-write `with*()` methods are **optional** and disabled by default. Enable them in the Maven plugin:

```xml
<configuration>
    <generateWithMethods>true</generateWithMethods>
</configuration>
```

### Why Disabled by Default?

For schemas with hundreds of entity types, `with*()` methods add significant
code volume — each method copies all properties (including inherited and
navigation properties) into a new instance, defensively deep-copying
collections and `unmappedFields`. With 1700+ entity types, this can add
hundreds of thousands of lines of generated code and slow down generation.

The `Builder` (generated for root-level concrete types) and Jackson setters
(input streams deserialize directly into fields) cover all common mutation
patterns. Use `with*()` only if you need the fluent copy-on-write style.

### With the Flag Disabled (default)

```java
// Use Builder for new instances and setters for updates
Person person = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .build();

// Update via Jackson setters (for deserialized entities)
person.setFirstName("Scotty");
```

### With the Flag Enabled

```java
// Fluent copy-on-write
Person updated = person
    .withFirstName("Scotty")
    .withAge(43L);
```

## What's Next

- [CSDL Metadata Parsing](csdl-parsing.md) — How metadata is processed
- [Generated Code Reference](../reference/generated-code.md) — Complete structure

## Why `changedFields` Exists

The tracking set is not dead weight: `patch()` serializes **only the tracked fields**
when it is non-empty, so `builder()`- and `with*()`-built entities get true partial
updates over the wire. Entities deserialized from a `get()` and modified via setters
deliberately track nothing (setter-side tracking would mark deserialization itself as a
change) and fall back to a full-body merge — legal OData either way.
