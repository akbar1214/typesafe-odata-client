# Work with Media Streams

OData v4 exposes binary content in two forms:

- A media entity declares `HasStream="true"`. Its bytes are at `.../<EntitySet>(key)/$value`.
- A named stream is an `Edm.Stream` property. Its bytes are at `.../<EntitySet>(key)/<PropertyName>`.

Generated stream methods are placed on entity request classes, not on model classes. The caller owns the returned `InputStream` and must close it.

## Read a Media Entity

For the OData Demo `Advertisement` media entity:

```java
Advertisement advertisement = client.advertisements().top(1).get().currentPage().get(0);

try (InputStream media = client.advertisements(advertisement.getID()).streamMedia()) {
    byte[] bytes = media.readAllBytes();
}
```

The keyed accessor is `client.advertisements(String id)`. The generated `getID()` return type follows the CSDL nullability of the key; for this metadata it is a `String`.

`streamMedia()` requests raw bytes with `Accept: */*` and maps HTTP failures to the runtime's typed exceptions.

## Write a Media Entity

```java
client.advertisements(advertisement.getID())
    .setMedia(new ByteArrayInputStream(newBytes), advertisement.getETag().orElse(null));
```

The overload without an ETag sends an unconditional PUT. The overload with an ETag adds `If-Match`.

Uploads are currently buffered: the generated method reads the complete `InputStream` into a `byte[]` before sending it. Large streaming uploads are not yet supported by the request body model.

## Read and Write a Named Stream

For the OData Demo `PersonDetail.Photo` stream:

```java
PersonDetail detail = client.personDetails().top(1).get().currentPage().get(0);

try (InputStream photo = client.personDetails(detail.getPersonID()).streamPhoto()) {
    byte[] bytes = photo.readAllBytes();
}

client.personDetails(detail.getPersonID())
    .setPhoto(new ByteArrayInputStream(newBytes), detail.getETag().orElse(null));
```

The named-stream URL ends at `/Photo`; it does not append `$value`.

## What's Next

- [Perform CRUD Operations](crud.md) — Create, read, update, and delete entities
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency with ETags
