# Perform CRUD Operations

Create, read, update, and delete entities.

## Read (GET)

### Get a Single Entity

```java
PersonEntityRequest request = client.peopleByUserName("scottketchum");
Person person = request.get();
```

### Get with Select

```java
Person person = client.peopleByUserName("scottketchum")
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .get();
```

### Get with Expand

```java
Person person = client.peopleByUserName("scottketchum")
    .expand(Person.TRIPS)
    .get();
```

## Create (POST)

### Create an Entity

```java
Person newPerson = Person.builder()
    .userName("mike")
    .firstName("Mike")
    .lastName("Smith")
    .emails(List.of("mike@example.com"))
    .build();

client.people()
    .post(newPerson);
```

### Create and Get Back

```java
Person created = client.people()
    .post(newPerson);

System.out.println(created.getUserName()); // "mike"
```

## Update (PATCH)

### Update an Entity

```java
PersonEntityRequest request = client.peopleByUserName("mike");

Person updated = Person.builder()
    .firstName("Michael")
    .build();

request.patch(updated);
```

### Update with ETag

See [Handle ETags and Concurrency](etag.md).

## Delete (DELETE)

### Delete an Entity

```java
client.peopleByUserName("mike")
    .delete();
```

## Related Entity CRUD

### Create a Trip for a Person

```java
Trip newTrip = Trip.builder()
    .tripId(1001L)
    .name("Business Trip")
    .budget(1500.0f)
    .build();

client.peopleByUserName("scottketchum")
    .trips()
    .post(newTrip);
```

### Delete a Trip

```java
client.peopleByUserName("scottketchum")
    .tripByTripId(1001L)
    .delete();
```

## What's Next

- [Work with Media Streams](media.md) — `HasStream` entities and `Edm.Stream` named properties
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency
- [Manage Navigation Links ($ref)](ref.md) — Add/remove relationships
