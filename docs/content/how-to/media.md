# Work with Media Streams (HasStream & Edm.Stream)

OData v4 lets services expose binary content two ways:

* **Media entities** — an entity type declares `HasStream="true"`. The entity *is* the
  media; its bytes live at `.../<EntitySet>(key)/$value`.
* **Named streams** — an entity property is typed `Edm.Stream`. The stream lives at
  `.../<EntitySet>(key)/<PropertyName>` (the media resource itself).

For both, the generated **entity request** class exposes `stream*` / `set*` methods.
Entities themselves (which hold no `Context`) do **not** get stream methods — use the
request returned by the container or a key accessor.

## Read a Media Entity (`HasStream="true"`)

The OData Demo service models `Advertisement` as a media entity. Read its bytes with
`streamMedia()`:

```java
Advertisement ad = client.advertisements().top(1).get().currentPage().get(0);

try (InputStream media = client.advertisements()
        .advertisementByID(ad.getID())
        .streamMedia()) {
    byte[] bytes = media.readAllBytes();
    // bytes is the raw media at .../Advertisements(<id>)/$value
}
```

`streamMedia()` issues `GET .../<EntitySet>(key)/$value` and requests
`Accept: */*` so the server returns the raw bytes, not JSON metadata.

## Write a Media Entity

Upload new bytes with `setMedia(...)`. Pass the current ETag for optimistic
concurrency (the runtime sends `If-Match`):

```java
client.advertisements()
    .advertisementByID(ad.getID())
    .setMedia(new ByteArrayInputStream(newBytes), ad.getETag().orElse(null));
```

Without an ETag, `setMedia(InputStream)` sends a plain `PUT`.

## Read a Named Stream (`Edm.Stream`)

The OData Demo `PersonDetail` has a `Photo` property of type `Edm.Stream`. Read it
with the generated `streamPhoto()` (the method name is `stream` + the property name):

```java
PersonDetail pd = client.personDetails().top(1).get().currentPage().get(0);

try (InputStream photo = client.personDetails()
        .personDetailByPersonID(pd.getPersonID())
        .streamPhoto()) {
    byte[] bytes = photo.readAllBytes();
    // bytes is the raw media at .../PersonDetails(<id>)/Photo
}
```

Write it back with `setPhoto(InputStream)` / `setPhoto(InputStream, etag)` — a `PUT`
to the same `.../<EntitySet>(key)/<PropertyName>` URL.

## What's Next

- [Perform CRUD Operations](crud.md) — Create, read, update, delete
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency with ETags
