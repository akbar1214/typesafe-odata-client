# Generated Code Structure

What OData Codegen generates from your CSDL metadata.

## Directory Structure

```
com/example/trippin/
├── entity/
│   ├── Person.java
│   ├── Trip.java
│   ├── Photo.java
│   ├── Airline.java
│   ├── Airport.java
│   └── PlanItem.java
├── complex/
│   ├── Location.java
│   ├── City.java
│   ├── AirportLocation.java
│   └── ...
├── enums/
│   ├── PersonGender.java
│   └── TripPlanType.java
├── request/
│   ├── PersonEntityRequest.java
│   ├── PersonCollectionRequest.java
│   ├── TripEntityRequest.java
│   ├── TripCollectionRequest.java
│   └── ...
├── container/
│   └── DefaultContainer.java
└── schema/
    └── ServiceSchemaInfo.java
```

## Entity Classes

### Immutable class (not a record)

Generated entities are `final class` (not Java `record`s) so they can implement
`ODataEntityType`, carry Jackson deserialization annotations, and support
inheritance. All fields are `final`.

```java
public final class Person implements ODataEntityType {
    // Static property constants (UPPER_CASE)
    public static final StringProperty<Person> USER_NAME = ...;
    public static final StringProperty<Person> FIRST_NAME = ...;
    public static final NumberProperty<Person, Long> AGE = ...;
    public static final CollectionProperty<Person, String> EMAILS = ...;

    // final fields
    private final String userName;
    private final String firstName;
    // ...

    @JsonCreator
    public Person(@JsonProperty("@odata.etag") String etag, /* ...props */) { ... }

    // Getters — nullable props return Optional<T>
    public String getUserName() { return userName; }
    public Optional<String> getFirstName() { return Optional.ofNullable(firstName); }

    // Copy-on-write
    public Person withFirstName(String firstName) { ... }

    // Builder — only for concrete, top-level entities
    public static Builder builder() { return new Builder(); }
}
```

### Builder

```java
Person person = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .lastName("Ketchum")
    .age(42L)
    .emails(List.of("scott@example.com"))
    .build();
```

### Inheritance

When a CSDL entity type declares a `BaseType`, the generated subclass emits a
real Java `extends` clause and chains `super(...)` constructors. Inherited
fields, keys, getters, navigation properties, and property constants all resolve
up the base chain.

```java
// CSDL: Flight -> PublicTransportation -> PlanItem
public final class Flight extends PublicTransportation {
    // only Flight's own fields are declared here; base fields live in the parent
    public static final NumberProperty<Flight, Integer> FLIGHT_NUMBER = ...;

    public Flight withFlightNumber(Integer n) { ... }   // returns Flight, base fields preserved
}
```

## Request Classes

### Entity Request

```java
public class PersonEntityRequest {
    private final Context context;
    private final ContextPath path;

    public Person get() { ... }
    public Person patch(Person person) { ... }
    public Person patchWithETag(Person person, String etag) { ... }
    public void delete() { ... }

    // Navigation
    public TripCollectionRequest trips() { ... }
    public PersonCollectionRequest friends() { ... }

    // Media streams (only when HasStream="true" or an Edm.Stream property exists)
    // Media entity: bytes at .../<EntitySet>(key)/$value
    public java.io.InputStream streamMedia() { ... }
    public void setMedia(java.io.InputStream content) { ... }
    public void setMedia(java.io.InputStream content, String etag) { ... }

    // Named stream (Edm.Stream property "Photo"): bytes at .../<EntitySet>(key)/Photo
    public java.io.InputStream streamPhoto() { ... }
    public void setPhoto(java.io.InputStream content) { ... }
    public void setPhoto(java.io.InputStream content, String etag) { ... }
}
```

### Collection Request

```java
public class PersonCollectionRequest {
    private final Context context;
    private final ContextPath path;

    public CollectionPage<Person> get() { ... }
    public CollectionPage<Person> getAsync() { ... }
    public Person post(Person person) { ... }

    // Query operations
    public PersonCollectionRequest filter(FilterExpression<Person> predicate) { ... }
    public PersonCollectionRequest select(PropertyExpression<?>... properties) { ... }
    public PersonCollectionRequest orderBy(OrderExpression<?>... sorts) { ... }
    public PersonCollectionRequest top(int count) { ... }
    public PersonCollectionRequest skip(int count) { ... }
    public PersonCollectionRequest count() { ... }
    public PersonCollectionRequest expand(NavProperty<?, ?>... navs) { ... }
    public PersonCollectionRequest expand(NavProperty.NavQuery<?>... queries) { ... }
    public PersonCollectionRequest search(String term) { ... }         // $search
    public PersonCollectionRequest apply(ApplyExpression expr) { ... }  // $apply (aggregation / $compute)
    public PersonCollectionRequest apply(String raw) { ... }           // $apply (raw)
}
```

## Container Class

```java
public class DefaultContainer {
    private final Context context;

    public DefaultContainer(Context context) { ... }

    // Entity sets
    public PersonCollectionRequest people() { ... }
    public TripCollectionRequest trips() { ... }
    public AirlineCollectionRequest airlines() { ... }
    public AirportCollectionRequest airports() { ... }
    public PhotoCollectionRequest photos() { ... }

    // Entity by key
    public PersonEntityRequest peopleByUserName(String userName) { ... }
    public TripEntityRequest tripsByTripId(Long tripId) { ... }
}
```

## Complex Type Classes

Complex types are keyless value types, generated as immutable classes that
implement `ODataType`.

```java
public class Location implements ODataType {
    protected final String address;
    protected final City city;

    @JsonCreator
    public Location(@JsonProperty("Address") String address,
                    @JsonProperty("City") City city) { ... }

    public String getAddress() { return address; }
    public City getCity() { return city; }

    // Copy-on-write
    public Location withAddress(String value) { ... }

    // Builder — only for concrete, top-level complex types
    public static Builder builder() { ... }
}
```

### Complex Type Inheritance

Like entities, complex types honor `BaseType` and emit a real `extends` clause.
Subtypes declare only their own fields (base fields live in the parent), chain
`super(...)` in the constructor, and get `with*()` copy-on-write methods.

The `Builder` is generated **only for concrete top-level complex types** — a
static `builder()` in a subtype would clash with the inherited one (Java forbids
hiding a static method with an incompatible return type). Subtypes use `with*()`.

```java
// CSDL: EventLocation BaseType="...Location"
public class EventLocation extends Location {
    protected final String buildingInfo;

    @JsonCreator
    public EventLocation(@JsonProperty("Address") String address,
                         @JsonProperty("City") City city,
                         @JsonProperty("BuildingInfo") String buildingInfo) {
        super(address, city);
        this.buildingInfo = buildingInfo;
    }

    public Optional<String> getBuildingInfo() { return Optional.ofNullable(buildingInfo); }

    // Copy-on-write (no Builder — reuses the inherited builder() via with*)
    public EventLocation withBuildingInfo(String value) {
        return new EventLocation(address, city, value);
    }
}
```

## Enum Classes

```java
public enum PersonGender {
    MALE("Male"),
    FEMALE("Female"),
    UNDEFINED("Unspecified");

    private final String value;

    PersonGender(String value) { this.value = value; }

    public String getValue() { return value; }
}
```

## Schema Info

```java
public class ServiceSchemaInfo implements SchemaInfo {
    @Override
    public Class<?> getClassFromTypeWithNamespace(String name) {
        return switch (name) {
            case "Person" -> Person.class;
            case "Trip" -> Trip.class;
            case "Location" -> Location.class;
            // ... all types
            default -> null;
        };
    }
}
```

## Naming Conventions

| Input | Output |
|-------|--------|
| `Person` (entity type) | `Person.java` |
| `FirstName` (property) | `firstName` (field), `FIRST_NAME` (constant) |
| `GetTrips` (function) | `getTrips()` (method) |
| `Microsoft.OData.SampleService.Models.TripPin` | `com.example.trippin` |

## What's Next

- [Query Expression API](query-api.md) — Complete list of operations
- [HTTP Transport](http-transport.md) — API details
