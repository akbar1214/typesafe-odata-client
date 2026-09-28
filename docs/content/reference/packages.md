# Package Structure

The repository is split into a parser/generator, a runtime, a Maven plugin, and generated-client tests.

```text
odata-codegen/
├── odata-codegen-core/
├── odata-codegen-runtime/
├── odata-codegen-maven-plugin/
└── odata-codegen-test/
```

## Dependency Direction

```text
odata-codegen-maven-plugin
    -> odata-codegen-core

generated code
    -> odata-codegen-runtime

odata-codegen-runtime
    -> Jackson and SLF4J (no internal module dependency)
```

`odata-codegen-core` uses the JDK StAX API and SLF4J. Its test scope also uses the runtime and Jackson to compile and deserialize generated fixtures.

## Runtime Packages

- `entity/` — `Context`, `ContextPath`, `OperationPath`, `SchemaInfo`, model interfaces, and entity/literal helpers
- `query/` — property, filter, order, collection/navigation builder, and `$apply` expression types; generated model classes supply their own `Selector` and `Filterable` views
- `http/` — `HttpTransport`, `HttpRequest`, `HttpResponse`, `HttpInterceptor`, `HttpMethod`, `HttpHeaders`, and `JdkHttpTransport`
- `auth/` — `AuthProvider`, bearer, API-key, and basic authentication providers
- `serialization/` — `Serializer`, `JacksonSerializer`, and `DynamicPropertyConverter`
- `paging/` — `CollectionPage`
- `batch/` — `BatchOperation`, `Changeset`, `BatchRequest`, `BatchResponse`, and `BatchResult`
- `exception/` — typed OData exceptions and `ODataError`
- `client/` — synchronous execution helpers used by generated requests
- `internal/` — `MultipartHelper`, an internal multipart implementation detail

The runtime has no Apache HttpClient or OkHttp dependency. The built-in transport uses `java.net.http.HttpClient`; another transport is application-provided.

## Generated Packages

For a base package `com.example.trippin`:

- `entity/` — entity model classes
- `complex/` — complex model classes
- `enums/` — generated enums implementing `ODataEnumValue`
- `entity/request/` — keyed entity request classes
- `collection/request/` — collection query and CRUD request classes
- `operation/` — function/action import and bound-operation request classes
- `container/` — generated service-container entry points
- `schema/` — aggregate `SchemaInfo` registry classes

Generated classes use the runtime's HTTP and serialization APIs and Jackson annotations on model setters. The default serializer is Jackson; replacing it requires honoring that generated model contract.

## Versioning

All repository modules currently use:

```text
0.1.0-SNAPSHOT
```

## What's Next

- [Contributing](../contributing.md)
- [Release Notes](../release-notes.md)
- [Maven Plugin Configuration](maven-plugin.md)
