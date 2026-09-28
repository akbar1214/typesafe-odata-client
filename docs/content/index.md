# OData Codegen

_A type-safe OData v4 client generator for Java. Generated models, request objects, and compile-time checked query expressions._

OData Codegen reads a service's CSDL metadata and generates Java sources for entities, complex types, enums, collection requests, entity requests, operation requests, containers, and a per-package `SchemaInfo` registry.

## Why OData Codegen

- **Type-safe queries** — `Person.FIRST_NAME.equalTo("Scott")` is checked by the generated property type instead of being assembled as an unchecked string.
- **Generated model and request layers** — model objects hold data; generated request objects own HTTP execution state and navigation paths.
- **Pluggable HTTP** — `HttpTransport.submit(...)` and `stream(...)` return `CompletableFuture` values. `JdkHttpTransport` is the built-in implementation.
- **Pluggable serialization** — `JacksonSerializer` is the default. Generated model setters use Jackson annotations, so a replacement serializer must handle that model contract.
- **Typed errors** — HTTP status codes map to specific `ODataException` subclasses, and parsed service errors are available from `getError()`.
- **Inheritance and polymorphism** — CSDL inheritance becomes Java inheritance; generated request reads resolve `@odata.type` through the generated `SchemaInfo` registry.
- **Media and open types** — media requests and dynamic open-type properties are generated when the metadata declares them.

## Quick Example

```java
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

DefaultContainer client = new DefaultContainer(ctx);

CollectionPage<Person> people = client.people()
    .filter(p -> p.FIRST_NAME.equalTo("Scott"))
    .select(p -> p.FIRST_NAME, p -> p.LAST_NAME)
    .orderBy(p -> p.LAST_NAME.asc())
    .top(10)
    .get();

PersonEntityRequest personRequest = client.people("scottketchum");
Person person = personRequest.get();

CollectionPage<Trip> trips = personRequest.trips()
    .filter(t -> t.BUDGET.greaterThan(500.0f))
    .get();
```

`PersonEntityRequest.get()` returns the generated entity type directly. It is not an `Optional`; a service that returns an empty body can still produce `null` for that direct return value.

New to OData? Start with [OData for Newcomers](concepts/odata-for-newcomers.md). To install the plugin, see [Getting Started](getting-started.md).

## Architecture

```text
odata-codegen/
├── odata-codegen-core/       # CSDL parser, model records, generators
├── odata-codegen-runtime/    # Runtime used by generated code
├── odata-codegen-maven-plugin/
└── odata-codegen-test/       # Generated-client tests
```

The runtime depends on Jackson and SLF4J. The built-in HTTP implementation uses the JDK `HttpClient`; no Apache or OkHttp transport is bundled.

## Documentation

- [Getting Started](getting-started.md)
- [How-to Guides](how-to/index.md)
- [Concepts](concepts/code-generation.md)
- [Reference](reference/maven-plugin.md)
- [Release Notes](release-notes.md)

## Project Status

The default Maven test run is hermetic and excludes live-service tests. Use the `live-tests` profile when running the public TripPin, Northwind, and OData Demo integration suites.

## Getting Help

- [OData for Newcomers](concepts/odata-for-newcomers.md)
- [GitHub Discussions](https://github.com/akbar1214/typesafe-odata-client/discussions)
- [GitHub Issues](https://github.com/akbar1214/typesafe-odata-client/issues)
- [OData reference](https://github.com/akbar1214/typesafe-odata-client/blob/main/ODATA.md)
