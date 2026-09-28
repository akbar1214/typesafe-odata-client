# Perform CRUD Operations

Generated collection requests create entities, while keyed entity requests read and modify them. A single-entity request returns the generated entity type directly from `get()`.

## Read (GET)

```java
import com.example.trippin.entity.Person;
import com.example.trippin.entity.request.PersonEntityRequest;

PersonEntityRequest request = client.people("scottketchum");
Person person = request.get();
```

Entity requests also support the single-entity read options:

```java
Person person = client.people("scottketchum")
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .expand(Person.TRIPS)
    .get();
```

`filter`, `orderBy`, `top`, and `skip` remain collection-only because they are not valid options for a single-entity GET.

## Create (POST)

Create through the collection request:

```java
Person newPerson = Person.builder()
    .userName("mike")
    .firstName("Mike")
    .lastName("Smith")
    .emails(List.of("mike@example.com"))
    .build();

Person created = client.people().create(newPerson);
```

The generated method is `create(...)`, not `post(...)`. The service may return an empty body for a write; in that case the generated direct return value can be `null`.

## Update (PATCH)

`patch(...)` sends a full-body merge for an entity whose `changedFields` set is empty. Entities built with `Builder`, or copied with generated `with*()` methods, track their changed CSDL names, so the default Jackson serializer sends only those fields.

Enable copy-on-write generation to use `with*()`:

```xml
<configuration>
    <generateWithMethods>true</generateWithMethods>
</configuration>
```

With that option enabled:

```java
PersonEntityRequest request = client.people("mike");
Person fetched = request.get();

Person updated = fetched.withFirstName("Michael");
Person result = request.patchWithETag(updated, fetched.getETag().orElseThrow());
```

When copy-on-write generation is disabled, use a `Builder` value for a tracked partial update or the generated public setters. Setters used for normal application updates do not mark fields as changed, so that path intentionally uses full-body PATCH semantics.

## Full Replace (PUT)

```java
Person replacement = Person.builder()
    .userName("mike")
    .firstName("Michael")
    .lastName("Smith")
    .build();

client.people("mike").put(replacement);
String etag = client.people("mike").get().getETag().orElseThrow();
client.people("mike").putWithETag(replacement, etag);
```

## Delete (DELETE)

```java
client.people("mike").delete();
client.people("mike").deleteWithETag(etag);
```

ETag methods add `If-Match` when a non-null, non-empty token is supplied. Services such as TripPin can require the header for PATCH and DELETE.

## Related Entity CRUD

Create a contained trip through the navigation collection request:

```java
Trip newTrip = Trip.builder()
    .tripId(1001)
    .name("Business Trip")
    .budget(1500.0f)
    .build();

Trip createdTrip = client.people("scottketchum")
    .trips()
    .create(newTrip);
```

Keyed navigation overloads return the target entity request directly:

```java
Trip fetchedTrip = client.people("scottketchum")
    .trips(1001)
    .get();
client.people("scottketchum")
    .trips(1001)
    .deleteWithETag(fetchedTrip.getETag().orElseThrow());
```

The path is `People('scottketchum')/Trips(1001)`; the keyed navigation overload is the direct entity-request form.

## What's Next

- [Work with Media Streams](media.md) — `HasStream` entities and `Edm.Stream` properties
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency
- [Manage Navigation Links](ref.md) — Add and remove `$ref` relationships
