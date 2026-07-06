# Batch API Reference

Complete API reference for batch request support.

## BatchOperation

A single operation within a batch.

### Factory Methods

| Method | Description |
|--------|-------------|
| `get(String url)` | Create a GET request |
| `get(String url, Map<String, List<String>> headers)` | GET with custom headers |
| `post(String url, byte[] body)` | Create a POST request |
| `post(String url, byte[] body, Map<String, List<String>> headers)` | POST with custom headers |
| `patch(String url, byte[] body)` | Create a PATCH request |
| `patch(String url, byte[] body, String etag)` | PATCH with ETag |
| `put(String url, byte[] body)` | Create a PUT request |
| `delete(String url)` | Create a DELETE request |

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `method` | `HttpMethod` | HTTP method (GET, POST, PATCH, PUT, DELETE) |
| `url` | `String` | Relative or absolute URL |
| `headers` | `Map<String, List<String>>` | Request headers |
| `body` | `byte[]` | Request body (null for GET/DELETE) |

## BatchRequest

Collects operations and executes them as a single HTTP request.

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `add(BatchOperation)` | `BatchRequest` | Add an operation (fluent) |
| `execute()` | `BatchResponse` | Execute synchronously |
| `executeAsync()` | `CompletableFuture<BatchResponse>` | Execute asynchronously |
| `size()` | `int` | Number of operations |
| `isEmpty()` | `boolean` | Whether no operations are queued |

## BatchResponse

Result of a batch execution.

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `get(int index)` | `BatchResult<?>` | Get result by index |
| `get(int index, Class<T> type)` | `BatchResult<T>` | Get result with typed deserialization |
| `get(int index, Type type)` | `BatchResult<T>` | Get result with generic type |
| `getAll(Class<T> type)` | `List<BatchResult<T>>` | Get all results with type |
| `size()` | `int` | Number of results |
| `isEmpty()` | `boolean` | Whether no results |
| `iterator()` | `Iterator<BatchResult<?>>` | Iterate over results |

## BatchResult\<T\>

Individual result within a batch response.

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `statusCode()` | `int` | HTTP status code |
| `headers()` | `Map<String, List<String>>` | Response headers |
| `body()` | `byte[]` | Raw response body |
| `isSuccessful()` | `boolean` | True if 2xx status |
| `isDeleted()` | `boolean` | True if 204 No Content |
| `getText()` | `String` | Body as UTF-8 string |
| `getEntity(Serializer)` | `T` | Deserialize body to type |
| `getHeader(String name)` | `String` | Get header value |

## Context.batch()

Creates a new `BatchRequest` bound to the context.

```java
BatchRequest batch = context.batch();
```

## Generated Request Methods

### EntityRequest

| Method | Returns | Description |
|--------|---------|-------------|
| `toBatchOperation()` | `BatchOperation` | GET the entity |
| `patchToBatchOperation(T entity)` | `BatchOperation` | PATCH the entity |
| `deleteToBatchOperation()` | `BatchOperation` | DELETE the entity |

### CollectionRequest

| Method | Returns | Description |
|--------|---------|-------------|
| `toBatchOperation()` | `BatchOperation` | GET the collection with current query |

## Multipart Format

### Request Format

```
POST /V4/TripPinService/$batch HTTP/1.1
Content-Type: multipart/mixed; boundary={boundary}

--{boundary}
Content-Type: application/http
Content-Transfer-Encoding: binary

GET https://services.odata.org/V4/TripPinService/People('scottketchum') HTTP/1.1

--{boundary}--
```

### Response Format

```
HTTP/1.1 200 OK
Content-Type: multipart/mixed; boundary={boundary}

--{boundary}
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 200 OK
Content-Type: application/json

{"UserName":"scottketchum",...}
--{boundary}--
```
