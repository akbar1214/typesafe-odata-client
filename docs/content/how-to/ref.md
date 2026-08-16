# Manage Navigation Links ($ref)

Create and remove entity relationships using `$ref`.

## What is $ref?

In OData, relationships between entities are managed through navigation links. A `$ref`
operation links (or unlinks) an existing entity to a navigation property — the entities
themselves are not created or deleted.

## Add a Relationship

Collection navigation properties on the **entity request** expose `add<Nav>Ref` methods:

```java
// POST .../People('scottketchum')/Friends/$ref
client.people().personByUserName("scottketchum")
    .addFriendsRef("People('ronaldmundy')");
```

The target is an entity path (resolved against the service root) or an absolute URL —
the body sent is `{"@odata.id": "<absolute URL>"}`, as OData requires. The target entity
must already exist; linking to a nonexistent entity is a server-side error.

## Remove a Relationship

```java
// DELETE .../People('scottketchum')/Friends/$ref?$id=<absolute URL>
client.people().personByUserName("scottketchum")
    .removeFriendsRef("People('ronaldmundy')");
```

The link is removed; the entity itself is untouched. As with `@odata.id`, the `$id`
entity path is resolved to an absolute URL for you.

## Containment Navigation Has No $ref

Navigation properties declared with `ContainsTarget="true"` (like `Person → Trips`) own
their contained entities: they are created and deleted through the containment path,
not linked via `$ref` — which is why no `addTripsRef` methods are generated:

```java
// Create a contained Trip (POST .../People('scottketchum')/Trips)
Trip created = client.people().personByUserName("scottketchum")
    .trips()
    .create(Trip.builder()
        .tripId(1001)
        .name("Business Trip")
        .budget(1500.0f)
        .build());

// Delete it through the same path
client.people().personByUserName("scottketchum")
    .tripByTripId(1001)
    .delete();
```

## Notes on Strict Services

- Both `@odata.id` and `$id` must be **absolute URIs** on strict services (relative
  values are rejected — e.g. TripPin answers 500 with "relative URI value … odata.context
  annotation is missing"). The client resolves entity paths against the service root,
  mirroring how batch request URLs are resolved.
- Real services may also return **409 Conflict** when the link already exists, and some
  (TripPin's demo service among them) can intermittently fail link mutations with a
  server-side error — treat non-2xx `$ref` responses with the same error handling as any
  other request.

## What's Next

- [Add Authentication](auth.md) — OAuth2, API keys, custom auth
- [Use Custom HTTP Transport](custom-transport.md) — your own `HttpTransport`
