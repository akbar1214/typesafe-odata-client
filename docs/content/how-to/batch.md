# Batch Requests

`Context.batch()` sends a `multipart/mixed` request to the service's `$batch` endpoint. A batch can contain standalone operations and atomic `Changeset` groups.

## Basic Usage

```java
import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResponse;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.entity.Context;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .add(BatchOperation.get("People('scottketchum')/Trips"))
    .execute();

BatchResult<?> person = response.get(0);
BatchResult<?> trips = response.get(1);
String body = person.getText();
```

`get(index)` is the submitted-operation view. The result also exposes the actual response-part order through `wireOrder()`.

## Changesets

A `Changeset` is a non-empty group of non-GET operations executed atomically by the service:

```java
byte[] customerBody = ctx.serializer().serialize(newCustomer, Customer.class);
byte[] orderBody = ctx.serializer().serialize(newOrder, Order.class);

Changeset creation = new Changeset(List.of(
    BatchOperation.post("Customers", customerBody),
    BatchOperation.post("Orders", orderBody)
));

BatchResponse response = ctx.batch()
    .addChangeset(creation)
    .add(BatchOperation.get("Customers"))
    .execute();
```

Changeset operations receive batch-wide generated Content-IDs. The encoder wraps the group in a nested `multipart/mixed` boundary and each embedded request in an `application/http` part.

### Mixing operations

```java
Changeset changes = new Changeset(List.of(
    BatchOperation.post("Customers", customerBody),
    BatchOperation.patch("Orders(1)", orderBody, "W/\"etag\"")
));

BatchResponse response = ctx.batch()
    .addChangeset(changes)
    .add(BatchOperation.get("Customers"))
    .add(BatchOperation.get("Orders"))
    .execute();
```

The positional view is:

```text
response.get(0)  first changeset operation
response.get(1)  second changeset operation
response.get(2)  standalone Customers GET
response.get(3)  standalone Orders GET
```

Changeset parts are matched by Content-ID even if the service returns them in a different order. Standalone parts without explicit IDs retain their relative wire order; assign an ID when the service may reorder those parts.

## Content-ID References and Correlation

An operation in a changeset can refer to an earlier Content-ID with `$1`, `$2`, and so on. References are resolved to the earlier request URL while the multipart request is prepared:

```java
BatchOperation first = BatchOperation.post("Customers", customerBody, "1");
BatchOperation child = BatchOperation.post("$1/Orders", orderBody, "2");
BatchOperation read = BatchOperation.get("Customers").withContentId("3");

BatchResponse response = ctx.batch()
    .addChangeset(new Changeset(List.of(first, child)))
    .add(read)
    .execute();

BatchResult<?> firstResult = response.getByContentId("1");
BatchResult<?> secondResult = response.getByContentId("2");
```

The response-side contract is:

- `get(index)` follows the submitted operation plan, including a reordered changeset.
- `wireOrder()` returns the response parts in the order received.
- `getByContentId(String)` returns the result carrying that ID or a related ID. Null or blank IDs are rejected.
- Missing, duplicate, or unexpected IDs in a keyed response fail with `ODataException` rather than silently shifting results.
- A failed atomic changeset may legally collapse several submitted operations into one non-success part. The same result is exposed at each affected submitted index; `relatedContentIds()` contains all represented IDs and `contentIdGroup()` is the runtime's internal correlation-group identifier.

## Supported Operations

```java
BatchOperation.get("People('scottketchum')");
BatchOperation.post("People", body);
BatchOperation.patch("People('scottketchum')", body);
BatchOperation.patch("People('scottketchum')", body, "W/\"etag\"");
BatchOperation.put("People('scottketchum')", body);
BatchOperation.delete("People('scottketchum')");
```

For binary content, use `media(...)` so the request is labeled with its media type rather than the default JSON label:

```java
BatchOperation media = BatchOperation.media(
    "Advertisements(0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001)/$value",
    bytes, "image/png");
```

`binary(...)`, `putMedia(...)`, and `postMedia(...)` are aliases or related convenience factories. The generic factories remain available when an explicit custom `Content-Type` is supplied.

## Generated Requests

Generated request objects can contribute operations to a batch:

```java
BatchResponse response = ctx.batch()
    .add(client.people("scott").toBatchOperation())
    .add(client.people("scott").select(Person.FIRST_NAME)
        .expand(Person.TRIPS).toBatchOperation())
    .add(client.people("scott").patchToBatchOperation(updatedPerson))
    .add(client.people("scott").deleteToBatchOperation())
    .add(client.people().top(5).toBatchOperation())
    .execute();
```

Collection requests expose `create(...)` for HTTP execution and `postToBatchOperation(...)` for batch use. Entity requests expose `putToBatchOperation(...)`, `patchToBatchOperation(...)`, `deleteToBatchOperation()`, and `toBatchOperation()`. The generated batch GET uses the request's current read options.

## Reading Results

```java
BatchResult<?> raw = response.get(0);
String text = raw.getText();
boolean successful = raw.isSuccessful();
int status = raw.statusCode();

Person person = response.get(0, Person.class)
    .getEntity(ctx.serializer());
```

Typed views preserve Content-ID, related-ID, group, and wire-index fields. Both `getEntity` overloads capture a response `ETag` header when the deserialized entity has no body ETag; pass the generated `SchemaInfo` when the declared entity type may be polymorphic:

```java
Person person = response.getEntity(
    0,
    Person.class,
    ctx.serializer(),
    com.example.trippin.schema.SchemaInfo.INSTANCE);
```

The response is iterable and can be inspected in positional order:

```java
for (BatchResult<?> result : response) {
    System.out.println(result.statusCode());
}
```

## Error Handling

A non-2xx outer batch response, a missing boundary, malformed part, or correlation mismatch throws `ODataException`. Individual operation failures inside an otherwise successful multipart response remain available through `BatchResult` values.

## Continue on Error

`continueOnError()` adds the OData 4.01 preference header:

```java
BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .add(BatchOperation.get("People('ronaldmundy')"))
    .continueOnError()
    .execute();
```

## Async Execution

```java
ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .executeAsync()
    .thenAccept(result -> System.out.println(result.get(0).statusCode()));
```

Synchronous and asynchronous batch failures are delivered through the corresponding synchronous exception or failed future.

## What's Next

- [Perform CRUD Operations](crud.md)
- [Handle Errors Gracefully](error-handling.md)
- [Batch API Reference](../reference/batch-api.md)
