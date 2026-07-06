# Handle ETags and Concurrency

Prevent lost updates using ETags for optimistic concurrency.

## What is an ETag?

An ETag is a version identifier returned with each entity. When you update an entity, you send the ETag back — if it's changed since you read it, the server rejects the update.

## Get an Entity with ETag

```java
PersonEntityRequest request = client.peopleByUserName("scottketchum");
Person person = request.get();

// The ETag is available on the entity
String etag = person.getETag();
```

## Update with ETag

### Use patchWithETag

```java
PersonEntityRequest request = client.peopleByUserName("scottketchum");
Person person = request.get();

// Make your changes
Person updated = Person.builder()
    .firstName("Scotty")
    .build();

// Update with ETag
request.patchWithETag(updated, person.getETag());
```

### What Happens

1. If the ETag matches → update succeeds (HTTP 204)
2. If the ETag doesn't match → update rejected (HTTP 412 Precondition Failed)

## Without ETag

```java
// This works but has no concurrency protection
request.patch(updated);
```

## Best Practices

- Always use ETags for updates in multi-user scenarios
- Handle `PreconditionFailedException` (HTTP 412) by re-fetching the entity
- Compare the new ETag with the old one before retrying

## What's Next

- [Manage Navigation Links ($ref)](ref.md) — Add/remove relationships
- [Handle Errors Gracefully](error-handling.md) — Error handling strategies
