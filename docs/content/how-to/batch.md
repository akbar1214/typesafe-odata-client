# Batch Requests

Send multiple OData operations in a single HTTP request with `$batch`.

## Basic Usage

```java
import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResponse;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;

Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .add(BatchOperation.get("People('scottketchum')/Trips"))
    .execute();

// Access results
BatchResult<?> scott = response.get(0);
BatchResult<?> trips = response.get(1);

System.out.println(scott.statusCode());  // 200
System.out.println(scott.getText());     // {"UserName":"scottketchum",...}
```

## Atomic Changesets

Group operations that must succeed or fail together:

```java
byte[] customerBody = ctx.serializer().serialize(newCustomer, Customer.class);
byte[] orderBody = ctx.serializer().serialize(newOrder, Order.class);

Changeset accountCreation = new Changeset(List.of(
    BatchOperation.post("Customers", customerBody),
    BatchOperation.post("Orders", orderBody)
));

BatchResponse response = ctx.batch()
    .addChangeset(accountCreation)
    .add(BatchOperation.get("Customers"))
    .execute();
```

Changesets emit `Content-ID` headers for each operation and wrap them in a nested
`multipart/mixed` boundary. The server processes the entire changeset atomically —
either all operations succeed or all are rolled back.

### Mixed with Standalone Operations

```java
Changeset cs = new Changeset(List.of(
    BatchOperation.post("Customers", customerBody),
    BatchOperation.patch("Orders(1)", orderUpdate, "W/\"etag\"")
));

// Query-type batch operations are always standalone (no changeset needed)
BatchResponse response = ctx.batch()
    .addChangeset(cs)
    .add(BatchOperation.get("Customers"))
    .add(BatchOperation.get("Orders"))
    .execute();

// Results are flattened in insertion order:
// [0] = changeset POST Customer result
// [1] = changeset PATCH Order result
// [2] = standalone GET Customers result
// [3] = standalone GET Orders result
```

### Known Limitation

Changesets do not yet support `Content-ID` references within URLs
(e.g., `POST Customers` → `PATCH $1/Contact`). Each operation must use an
absolute or service-relative URL directly.

## Supported Operations

### GET

```java
// Single entity
BatchOperation.get("People('scottketchum')")

// Collection
BatchOperation.get("People?$top=5")

// With query params
BatchOperation.get("People('scottketchum')?$select=UserName,FirstName")
```

### PATCH

```java
byte[] body = context.serializer().serialize(updatedPerson, Person.class);
BatchOperation.patch("People('scottketchum')", body)
```

### PATCH with ETag

```java
BatchOperation.patch("People('scottketchum')", body, "W/\"12345\"")
```

### DELETE

```java
BatchOperation.delete("People('scottketchum')")
```

### POST

```java
byte[] body = context.serializer().serialize(newPerson, Person.class);
BatchOperation.post("People", body)
```

## Using Generated Request Classes

Each generated request class has `toBatchOperation()` and related methods:

```java
DefaultContainer client = new DefaultContainer(ctx);

BatchResponse response = ctx.batch()
    // Entity request → GET
    .add(client.peopleByUserName("scott").toBatchOperation())

    // Entity request → PATCH
    .add(client.peopleByUserName("scott").patchToBatchOperation(updatedPerson))

    // Entity request → DELETE
    .add(client.peopleByUserName("louis").deleteToBatchOperation())

    // Collection request → GET
    .add(client.people().top(5).toBatchOperation())

    .execute();
```

## Accessing Results

### By Index

```java
BatchResult<?> result = response.get(0);
System.out.println(result.statusCode());  // 200
System.out.println(result.getText());     // JSON string
```

### Deserializing

```java
Person person = response.get(0, Person.class).getEntity(context.serializer());
```

### Iterating

```java
for (BatchResult<?> result : response) {
    System.out.println(result.statusCode());
}
```

## Error Handling

Individual operations can fail without failing the entire batch:

```java
BatchResponse response = ctx.batch()
    .add(BatchOperation.get("People('nonexistent')"))  // 404
    .add(BatchOperation.get("People('scottketchum')"))  // 200
    .execute();

// First result: 404 Not Found
// Second result: 200 OK
```

Check individual status codes:

```java
for (BatchResult<?> result : response) {
    if (!result.isSuccessful()) {
        System.out.println("Failed: " + result.statusCode());
    }
}
```

## Async Execution

```java
ctx.batch()
    .add(BatchOperation.get("People('scottketchum')"))
    .executeAsync()
    .thenAccept(response -> {
        BatchResult<?> result = response.get(0);
        System.out.println(result.statusCode());
    });
```

## What's Next

- [Perform CRUD Operations](crud.md) — Single operation patterns
- [Handle Errors Gracefully](error-handling.md) — Error handling strategies
