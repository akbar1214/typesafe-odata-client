# Batch API

The runtime batch API models an OData v4 `multipart/mixed` request. It supports standalone operations, atomic changesets, explicit or generated Content-IDs, URL references, and typed response views.

## `Changeset`

```java
public record Changeset(List<BatchOperation> operations)
```

A changeset must contain at least one operation, cannot contain GET, and rejects duplicate explicit Content-IDs. Its operation list is immutable.

## `BatchOperation`

```java
public record BatchOperation(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body,
    String contentId
)
```

Headers and body bytes are defensively copied. URLs may be absolute HTTP(S) URLs or service-root-relative request targets; `BatchRequest` resolves relative targets before encoding.

### Factory Methods

| Method | Result |
|--------|--------|
| `get(url)` | GET operation |
| `get(url, headers)` | GET with custom headers |
| `get(url, contentId)` | GET with explicit Content-ID |
| `getWithContentId(url, contentId)` | Explicit GET Content-ID |
| `post(url, body)` | JSON-compatible POST operation |
| `post(url, body, headers)` | POST with custom headers |
| `post(url, body, contentId)` | POST with explicit Content-ID |
| `postWithContentId(url, body, contentId)` | Explicit POST Content-ID |
| `postWithContentType(url, body, contentType)` | POST with a media type |
| `patch(url, body)` | PATCH operation |
| `patch(url, body, etag)` | PATCH with `If-Match` |
| `patch(url, body, contentId, etag)` | Conditional PATCH with explicit ID |
| `put(url, body)` | PUT operation |
| `put(url, body, headers)` | PUT with custom headers |
| `put(url, body, contentId)` | PUT with explicit ID |
| `putWithContentId(url, body, contentId)` | Explicit PUT Content-ID |
| `media(url, body, contentType)` | Binary PUT with the supplied content type |
| `binary(url, body, contentType)` | Alias for `media` |
| `putMedia(...)` / `postMedia(...)` | Related media factories |
| `delete(url)` | DELETE operation |
| `delete(url, headers)` | DELETE with custom headers |
| `delete(url, contentId)` | DELETE with explicit ID |
| `deleteWithContentId(url, contentId)` | Explicit DELETE Content-ID |

`withContentId(String)` returns a copy with an explicit ID. `withUrl(String)` returns a copy with a new request target.

## `BatchRequest`

```java
BatchRequest batch = context.batch();
batch.add(operation);
batch.addChangeset(changeset);
batch.continueOnError();
BatchResponse response = batch.execute();
CompletableFuture<BatchResponse> future = batch.executeAsync();
```

`size()` counts operations inside changesets, not just top-level entries. `continueOnError()` adds `Prefer: continue-on-error=true` to the outer request.

The encoder assigns Content-IDs to changeset operations in one batch-wide sequence. `$1`, `$2`, and similar references are resolved only to earlier operations. Explicit IDs are preserved, and duplicate or unresolved references fail during request preparation.

## `BatchResponse`

```java
public class BatchResponse implements Iterable<BatchResult<?>> {
    public int size();
    public boolean isEmpty();
    public BatchResult<?> get(int index);
    public List<BatchResult<?>> results();
    public List<BatchResult<?>> wireOrder();
    public List<BatchResult<?>> wireResults();
    public BatchResult<?> getByContentId(String contentId);
    public BatchResult<?> getByRelatedContentId(String contentId);
    public <T> BatchResult<T> get(int index, Class<T> type);
    public <T> List<BatchResult<T>> getAll(Class<T> type);
}
```

`get(index)` is the submitted-operation view. `wireOrder()` is the actual response-part view. Standalone parts without explicit IDs are matched by relative wire order; keyed parts and changeset parts are correlated by Content-ID.

`getByContentId(...)` accepts a real ID or a related ID and returns `null` when no submitted operation has that ID. `getByRelatedContentId(...)` is an alias. `wireResults()` is an alias for `wireOrder()`. Null and blank lookup arguments are rejected.

### Typed Entity Reads

```java
Person person = response.getEntity(
    0,
    Person.class,
    context.serializer(),
    com.example.trippin.schema.SchemaInfo.INSTANCE);
```

The overload uses the generated `SchemaInfo` registry for `@odata.type` resolution and applies a response `ETag` header when the deserialized entity has no body ETag. The `get(index, type)` and `getAll(type)` views preserve all `BatchResult` metadata.

## `BatchResult`

```java
public record BatchResult<T>(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body,
    Type targetType,
    String contentId,
    Set<String> relatedContentIds,
    String contentIdGroup,
    int wireIndex
)
```

Useful methods include:

- `isSuccessful()` and `isDeleted()`
- `getText()` for UTF-8 text
- `getHeader(String)` with case-insensitive lookup
- `getEntity(Serializer)` and `getEntity(Serializer, SchemaInfo)`
- `withType(Type)`, `withWireIndex(int)`, and metadata-preserving copy methods
- `relatedContentIdSet()` / `relatedIds()`
- `groupId()` / `contentIdGroupId()`
- `wireOrderIndex()`

A failed changeset can produce one non-success part for several submitted operations. In that case the correlated result is repeated at each submitted index; `relatedContentIds()` preserves the represented IDs and `contentIdGroup()` is the runtime's internal correlation-group identifier.

## Multipart Contract

The outer request is `multipart/mixed`. Each standalone operation is an `application/http` part. A changeset is a nested `multipart/mixed` part whose operations are encoded as `application/http` parts with `Content-ID` headers. Response parsing requires a valid boundary, line-anchored delimiters, and complete closing boundaries; malformed parts throw `ODataException`.

The runtime accepts quoted, case-varying boundary parameters and preserves binary response bodies byte-for-byte.

## Errors and Async

A non-2xx outer response is converted with `ODataException.fromResponse(...)`. Individual operation failures are returned as `BatchResult` values. `executeAsync()` returns a future and reports preparation, transport, parsing, and correlation failures through that future.

## What's Next

- [HTTP Transport](http-transport.md)
- [Error Handling](error-handling.md)
- [OData URL Patterns](odata-urls.md)
