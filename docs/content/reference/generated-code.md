# Generated Code Structure

What Modern OData Client generates from your CSDL metadata.

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

### Record (Data)

```java
public record Person(
    String userName,
    String firstName,
    String lastName,
    Long age,
    List<String> emails,
    List<Trip> trips,
    // ... other fields
) implements ODataEntityType {
    // Static constants
    public static final StringProperty USER_NAME = ...;
    public static final StringProperty FIRST_NAME = ...;
    public static final NumberProperty<Long> AGE = ...;
    public static final CollectionProperty<String> EMAILS = ...;

    // Builder
    public static Builder builder() { return new Builder(); }

    // with*() methods
    public Person withFirstName(String firstName) { ... }

    // Builder class
    public static class Builder { ... }
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

## Request Classes

### Entity Request

```java
public class PersonEntityRequest {
    private final Context context;
    private final ContextPath path;

    public Person get() { ... }
    public void patch(Person person) { ... }
    public void patchWithETag(Person person, String etag) { ... }
    public void delete() { ... }

    // Navigation
    public TripCollectionRequest trips() { ... }
    public PersonCollectionRequest friends() { ... }
}
```

### Collection Request

```java
public class PersonCollectionRequest {
    private final Context context;
    private final ContextPath path;

    public CollectionPage<Person> get() { ... }
    public Person post(Person person) { ... }

    // Query operations
    public PersonCollectionRequest filter(Expression<Boolean> predicate) { ... }
    public PersonCollectionRequest select(Property<?>... properties) { ... }
    public PersonCollectionRequest orderBy(SortExpression<?>... sorts) { ... }
    public PersonCollectionRequest top(int count) { ... }
    public PersonCollectionRequest skip(int count) { ... }
    public PersonCollectionRequest count() { ... }
    public PersonCollectionRequest expand(NavigationProperty<?>... properties) { ... }
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

```java
public record City(
    String name,
    String countryRegion,
    String region,
    Double latitude,
    Double longitude
) {
    // Builder
    public static Builder builder() { ... }

    // with*() methods
    public City withName(String name) { ... }
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
