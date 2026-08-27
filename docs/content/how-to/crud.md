# Perform CRUD Operations

Create, read, update, and delete entities.

## Read (GET)

### Get a Single Entity

```java
PersonEntityRequest request = client.people("scottketchum");
Person person = request.get();
```

### Get with Select

```java
Person person = client.people("scottketchum")
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .get();
```

### Get with Expand

```java
Person person = client.people("scottketchum")
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

Person created = client.people()
    .create(newPerson);

System.out.println(created.getUserName()); // "mike"
```

## Update (PATCH)

### Update an Entity

```java
PersonEntityRequest request = client.people("mike");

Person updated = Person.builder()
    .firstName("Michael")
    .build();

request.patch(updated);
```

### Partial vs Full Updates

`patch()` sends **only the tracked changes** when the entity was built via `builder()`
or `with*()` copy-on-write — the body below is `{"FirstName":"Michael"}`, not the whole
entity. Entities fetched with `get()` and modified via setters track nothing and send a
full-body merge (legal OData either way).

```java
// Partial: only FirstName is sent
Person updated = Person.builder()
    .firstName("Michael")
    .build();
request.patch(updated);

// Partial via copy-on-write from a fetched entity
Person renamed = fetched.withFirstName("Michael");
request.patch(renamed);        // still only FirstName
```

### Full Replace (PUT)

`put(entity)` replaces the entire entity (HTTP PUT); `putWithETag(entity, etag)` adds
the `If-Match` precondition.

### Update with ETag

See [Handle ETags and Concurrency](etag.md).

## Delete (DELETE)

### Delete an Entity

```java
client.people("mike")
    .delete();
```

## Related Entity CRUD

### Create a Trip for a Person

```java
Trip newTrip = Trip.builder()
    .tripId(1001)          // Edm.Int32 -> Integer
    .name("Business Trip")
    .budget(1500.0f)
    .build();

Trip createdTrip = client.people("scottketchum")
    .trips()
    .create(newTrip);
```

### Delete a Trip

```java
client.people("scottketchum")
    .tripByTripId(1001)
    .delete();
```

## What's Next

- [Work with Media Streams](media.md) — `HasStream` entities and `Edm.Stream` named properties
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency
- [Manage Navigation Links ($ref)](ref.md) — Add/remove relationships
