# OData Codegen

A type-safe OData v4 client generator for Java. Parses CSDL XML metadata and generates immutable Java classes with compile-time validated query builders.

## Features

- **Type-safe query API** — Expression builders for `$filter`, `$select`, `$orderby`, `$expand` with compile-time validation
- **Truly immutable entities** — All fields `final`; copy-on-write semantics
- **Entity & complex-type inheritance** — Subtypes emit real Java `extends` clauses; base-type query predicates type-check against subtypes (e.g. `Flight` is a `PlanItem`, `EventLocation` is a `Location`)
- **Nested `$expand`** — Type-safe `$expand=Trips($select=...;$filter=...;$top=...)` via `NavQuery`
- **Pluggable HTTP** — `HttpTransport` interface; built-in JDK and Apache implementations
- **Pluggable serialization** — `Serializer` interface; Jackson by default
- **Typed exceptions** — `NotFoundException`, `UnauthorizedException`, `RateLimitException`, etc.
- **ETag concurrency** — `If-Match` header support for optimistic locking
- **`$count` support** — Get total count with collection queries
- **`$ref` support** — Add/remove navigation property links
- **Async-first** — `CompletableFuture`-based HTTP layer

## Quick Start

### 1. Add Maven Plugin

```xml
<plugin>
    <groupId>io.github.akbarhusain.odata</groupId>
    <artifactId>odata-codegen-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
                <basePackage>com.example.trippin</basePackage>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. Use the Generated Client

```java
import io.github.akbarhusain.odata.runtime.entity.Context;
import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Person;
import com.example.trippin.entity.Trip;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

// Create context with base URL
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

// Create client
DefaultContainer client = new DefaultContainer(ctx);
```

## Usage Examples

### Collection Queries

```java
// Get all people
CollectionPage<Person> people = client.people().get();

// Filter with type-safe expressions
CollectionPage<Person> scott = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .get();

// Complex filters
CollectionPage<Person> results = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .skip(5)
    .get();

// Get total count
CollectionPage<Person> page = client.people()
    .count()
    .top(10)
    .get();
long totalPeople = page.count().orElse(0L);
```

### Entity Navigation

```java
// Get entity by key
PersonEntityRequest personReq = client.peopleByUserName("scottketchum");
Person person = personReq.get();

// Navigate to collection
CollectionPage<Trip> trips = personReq.trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .orderBy(Trip.BUDGET.desc())
    .get();

// Get first trip
Trip trip = personReq.trips().top(1).get().currentPage().get(0);
```

### Expand with Navigation

```java
// Simple expand
CollectionPage<Person> peopleWithTrips = client.people()
    .expand(Person.TRIPS)
    .top(5)
    .get();

// Nested $expand options (select / filter / top / orderBy on the expanded nav)
CollectionPage<Person> peopleNested = client.people()
    .expand(Person.TRIPS.select(Trip.NAME).top(5)
        .filter(Trip.BUDGET.greaterThan(500.0f)))
    .get();
```

### CRUD Operations

```java
// Create entity
Person newPerson = Person.builder()
    .userName("newuser")
    .firstName("New")
    .lastName("User")
    .build();

// PATCH with ETag
PersonEntityRequest req = client.peopleByUserName("newuser");
Person existing = req.get();
String etag = existing.getETag().orElse(null);

Person updated = req.patchWithETag(
    existing.withFirstName("Updated"),
    etag
);

// DELETE
req.delete();
```

### `$ref` — Manage Navigation Links

```java
// Add friend
client.peopleByUserName("scottketchum")
    .addFriendsRef("People('keithcombs')");

// Remove friend
client.peopleByUserName("scottketchum")
    .removeFriendsRef("keithcombs");
```

### Error Handling

```java
import io.github.akbarhusain.odata.runtime.exception.*;

try {
    Person person = client.peopleByUserName("nonexistent").get();
} catch (NotFoundException e) {
    System.out.println("Person not found: " + e.getMessage());
} catch (UnauthorizedException e) {
    System.out.println("Authentication required");
} catch (RateLimitException e) {
    System.out.println("Rate limited, retry after: " + e.getRetryAfter());
} catch (ODataException e) {
    System.out.println("OData error: " + e.getMessage());
}
```

### Custom HTTP Transport

```java
// Use Apache HttpClient
import io.github.akbarhusain.odata.runtime.http.ApacheHttpTransport;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .transport(new ApacheHttpTransport())
    .authProvider(new BearerAuthProvider("your-token"))
    .build();
```

### Async Execution

```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<CollectionPage<Person>> future = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .getAsync();

future.thenAccept(people -> {
    people.forEach(System.out::println);
});
```

## Architecture

```
odata-codegen/
├── odata-codegen-core/        # Parser + Generator (no runtime deps)
│   ├── model/                # CsdlModel records
│   ├── parser/               # StaxCsdlParser
│   └── generator/            # Names, Generator, EntityGenerator, etc.
├── odata-codegen-runtime/     # Runtime types (generated code depends on this)
│   ├── entity/               # ODataEntityType, ContextPath, Context
│   ├── query/                # Expression hierarchy (StringProperty, etc.)
│   ├── http/                 # HttpTransport, HttpRequest, HttpResponse
│   ├── auth/                 # AuthProvider
│   ├── serialization/        # Serializer interface
│   └── paging/               # CollectionPage
├── odata-codegen-maven-plugin/ # Maven plugin wrapper
└── odata-codegen-test/        # Integration tests
```

## Generated Code Structure

```java
// Entity (immutable, annotation-free)
public final class Person implements ODataEntityType {
    public static final StringProperty<Person> FIRST_NAME = ...;
    public static final CollectionProperty<Person, Trip> TRIPS = ...;
    private final String userName;
    private final String firstName;
    // Builder, with*() methods, getters
}

// Collection request (type-safe query building)
public class PersonCollectionRequest {
    public PersonCollectionRequest filter(FilterExpression<Person> predicate);
    public PersonCollectionRequest select(PropertyExpression<?>... properties);
    public PersonCollectionRequest expand(NavProperty<?, ?>... navs);
    public PersonCollectionRequest expand(NavProperty.NavQuery<?>... queries);
    public PersonCollectionRequest top(int count);
    public CollectionPage<Person> get();
}

// Entity request (CRUD operations)
public class PersonEntityRequest {
    public Person get();
    public Person patch(Person entity);
    public Person patchWithETag(Person entity, String etag);
    public void delete();
    public TripCollectionRequest trips();
}
```

## License

Apache License 2.0
