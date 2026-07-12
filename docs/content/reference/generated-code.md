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
inheritance. Fields are `protected` (not `final`) so Jackson setters and the
no-args constructor can populate them; getters return immutable views.

```java
public final class Person implements ODataEntityType {
    // Static property constants (UPPER_CASE)
    public static final StringProperty<Person> USER_NAME = ...;
    public static final StringProperty<Person> FIRST_NAME = ...;
    public static final NumberProperty<Person, Long> AGE = ...;
    public static final CollectionProperty<Person, String, CollectionProperty.FilterableElement<String>> EMAILS = ...;

    // Mutable fields (deserialized via public setters)
    protected String userName;
    protected String firstName;
    // Navigation fields — hold expanded ($expand) data deserialized from JSON
    protected List<Trip> trips;
    protected Photo photo;

    // Public no-args constructor for Jackson, Builder, and with*()
    public Person() { ... }

    // Jackson setters
    @JsonProperty("UserName")
    public void setUserName(String value) { this.userName = value; }

    @JsonProperty("Trips")
    public void setTrips(List<Trip> trips) { this.trips = trips; }

    @JsonProperty("@odata.etag")
    public void setEtag(String etag) { ... }

    // Getters — nullable props return Optional<T>; collections are unmodifiable
    public String getUserName() { return userName; }
    public Optional<String> getFirstName() { return Optional.ofNullable(firstName); }

    // Navigation getters — materialized expanded ($expand) data
    public List<Trip> getTrips() { return trips == null ? List.of() : Collections.unmodifiableList(trips); }
    public Optional<Photo> getPhoto() { return Optional.ofNullable(photo); }

    // Copy-on-write
    public Person withFirstName(String firstName) { ... }
    public Person withTrips(List<Trip> trips) { ... }

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
real Java `extends` clause. Inherited fields, keys, getters, navigation
properties, and property constants all resolve up the base chain. The subclass
uses the public no-args constructor and copies inherited fields by name in its
`with*()` copy-on-write methods.

```java
// CSDL: Flight -> PublicTransportation -> PlanItem
public final class Flight extends PublicTransportation {
    // only Flight's own fields are declared here; base fields live in the parent
    public static final NumberProperty<Flight, Integer> FLIGHT_NUMBER = ...;

    public Flight withFlightNumber(Integer n) { ... }   // returns Flight, base fields preserved
}
```

## OpenType (Dynamic Properties)

A CSDL type marked `OpenType="true"` (or inheriting openness from a base type) may
carry JSON fields that are not declared in the metadata. The generated class captures
those into a `unmappedFields` map and exposes them:

```java
// CSDL: <EntityType Name="Person" OpenType="true">
public class Person implements ODataEntityType {
    // ... declared final fields, builder, with*() ...

    // Generated for OpenType types (and subtypes of an open base):
    @com.fasterxml.jackson.annotation.JsonAnySetter
    protected void putDynamicProperty(String name, Object value) { ... }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    @Override
    public Map<String, Object> getUnmappedFields() { ... }   // unmodifiable view

    public Optional<Object> getDynamicProperty(String name) { ... }

    // Typed coercion of the stored Jackson value into your class (nested objects -> POJOs,
    // numbers coerce, e.g. Integer -> Long). Throws IllegalArgumentException on mismatch.
    public <T> Optional<T> getDynamicProperty(String name, Class<T> type) { ... }
}
```

Notes:
- `@odata.*` control fields (`@odata.id`, `@odata.editLink`, ...) are filtered out and
  never land in `unmappedFields`.
- `getUnmappedFields()` returns an unmodifiable view; dynamic properties are also
  re-serialized (POST/PATCH) via the `@JsonAnyGetter`, so they round-trip.
- Openness propagates down the inheritance chain. An open subtype of a non-open base
  captures dynamic props into the base's `unmappedFields` (which is initialized mutable
  only when the hierarchy contains an open type).

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
    public PersonCollectionRequest select(PropertyExpression<? super Person, ?>... properties) { ... }
    public PersonCollectionRequest orderBy(OrderExpression<? super Person, ?>... sorts) { ... }
    public PersonCollectionRequest top(int count) { ... }
    public PersonCollectionRequest skip(int count) { ... }
    public PersonCollectionRequest count() { ... }
    public PersonCollectionRequest expand(NavProperty<? super Person, ?>... navs) { ... }
    public PersonCollectionRequest expand(NavProperty.NavQuery<? super Person, ?>... queries) { ... }
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

Complex types are keyless value types, generated as classes that implement
`ODataType`. Fields are `protected` so Jackson setters can populate them.

```java
public class Location implements ODataType {
    protected String address;
    protected City city;
    // Navigation fields — hold expanded ($expand) data deserialized from JSON
    protected Airport airportRef;

    // Public no-args constructor for Jackson, Builder, and with*()
    public Location() { ... }

    // Jackson setters
    @JsonProperty("Address")
    public void setAddress(String value) { this.address = value; }

    @JsonProperty("City")
    public void setCity(City value) { this.city = value; }

    @JsonProperty("AirportRef")
    public void setAirportRef(Airport value) { this.airportRef = value; }

    public String getAddress() { return address; }
    public City getCity() { return city; }

    // Navigation getter — materialized expanded ($expand) data
    public Optional<Airport> getAirportRef() { return Optional.ofNullable(airportRef); }

    // Copy-on-write
    public Location withAddress(String value) { ... }
    public Location withAirportRef(Airport value) { ... }

    // Builder — only for concrete, top-level complex types
    public static Builder builder() { ... }
}
```

### Complex Type Inheritance

Like entities, complex types honor `BaseType` and emit a real `extends` clause.
Subtypes declare only their own fields (base fields live in the parent) and get
`with*()` copy-on-write methods.

The `Builder` is generated **only for concrete top-level complex types** — a
static `builder()` in a subtype would clash with the inherited one (Java forbids
hiding a static method with an incompatible return type). Subtypes use `with*()`.

```java
// CSDL: EventLocation BaseType="...Location"
public class EventLocation extends Location {
    protected String buildingInfo;

    public EventLocation() { super(); }

    @JsonProperty("BuildingInfo")
    public void setBuildingInfo(String value) { this.buildingInfo = value; }

    public Optional<String> getBuildingInfo() { return Optional.ofNullable(buildingInfo); }

    // Copy-on-write (no Builder — reuses the inherited builder() via with*)
    public EventLocation withBuildingInfo(String value) {
        EventLocation e = new EventLocation();
        e.address = this.address;
        e.city = this.city;
        e.airportRef = this.airportRef;
        e.buildingInfo = value;
        return e;
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
