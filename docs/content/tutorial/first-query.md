# Your First Query

This tutorial walks through querying the TripPin OData service end-to-end.

## Setup

Add the Maven plugin and runtime dependency (see [Getting Started](../getting-started.md)), then generate the client:

```bash
mvn generate-sources
```

## Connect to the Service

```java
import com.modernodata.runtime.entity.Context;
import com.example.trippin.container.DefaultContainer;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);
```

## List All People

```java
import com.modernodata.runtime.paging.CollectionPage;
import com.example.trippin.entity.Person;

CollectionPage<Person> people = client.people().get();
```

This executes a `GET /V4/TripPinService/People` and returns an immutable `CollectionPage<Person>`.

## Filter Results

```java
CollectionPage<Person> filtered = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott"))
    .get();
```

The `.filter()` method accepts a type-safe expression. `Person.FIRST_NAME` is a `StringProperty` with string-specific methods like `equalTo()`, `contains()`, `startsWith()`.

## Select Specific Fields

```java
CollectionPage<Person> projected = client.people()
    .select(Person.FIRST_NAME, Person.LAST_NAME, Person.EMAILS)
    .get();
```

Only the requested fields are returned by the service.

## Order Results

```java
CollectionPage<Person> ordered = client.people()
    .orderBy(Person.LAST_NAME.asc(), Person.FIRST_NAME.desc())
    .get();
```

## Limit Results

```java
CollectionPage<Person> topTen = client.people()
    .top(10)
    .get();

CollectionPage<Person> page2 = client.people()
    .top(10)
    .skip(10)
    .get();
```

## Combine Operations

```java
CollectionPage<Person> result = client.people()
    .filter(Person.FIRST_NAME.contains("Scott"))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(5)
    .get();
```

## Get a Single Person

```java
import com.example.trippin.request.PersonEntityRequest;

PersonEntityRequest request = client.peopleByUserName("scottketchum");
Person scott = request.get();
```

## Navigate to Related Data

```java
// Get trips for a specific person
CollectionPage<Trip> trips = client.peopleByUserName("scottketchum")
    .trips()
    .get();

// Get trips with a filter
CollectionPage<Trip> expensiveTrips = client.peopleByUserName("scottketchum")
    .trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .orderBy(Trip.STARTS_AT.desc())
    .get();
```

## Count Results

```java
CollectionPage<Person> people = client.people()
    .count()
    .get();

Optional<Long> total = people.count(); // Optional[8]
```

## Iterate Safely

```java
CollectionPage<Person> people = client.people().top(5).get();

// For-each
for (Person person : people) {
    System.out.println(person.getFirstName());
}

// Stream
people.forEach(p -> System.out.println(p.getFirstName()));
```

## What's Next

- [Filter with Type-Safe Expressions](../how-to/filter.md) — All filter operations
- [CRUD Operations](../how-to/crud.md) — Create, update, delete
- [Pagination](../how-to/pagination.md) — Handle large result sets
