# OData Codegen

_A type-safe OData v4 client generator for Java.
Immutable entities, compile-time validated queries, pluggable HTTP._

!!! success "Milestones 1-5 Complete!"

    Parser, code generator, runtime, Maven plugin, and integration tests against TripPin service — all working. 44 tests passing.

## Why OData Codegen

Most OData clients for Java force you into string-based queries, mutable entities, and tight coupling to specific HTTP libraries. OData Codegen generates clean, immutable Java classes from OData CSDL metadata with compile-time safety at every step:

* **Type-safe queries** — `Person.FIRST_NAME.equalTo("Scott")` not `filter("FirstName eq 'Scott'")`. Typos caught at compile time.
* **Truly immutable entities** — All fields `final`, copy-on-write semantics. No mutable state, no null fields, no `@JacksonInject` coupling.
* **Pluggable HTTP** — Use JDK, Apache HttpClient, OkHttp, or any `HttpTransport` implementation. Async-first with `CompletableFuture`.
* **Pluggable serialization** — Jackson by default, but swap in Gson or Jakarta JSON-B. Generated entities are annotation-free.
* **Typed exceptions** — `NotFoundException`, `UnauthorizedException`, `RateLimitException` — catch what matters, not generic `ClientException`.
* **Zero runtime overhead** — Code generated at build time. No reflection, no proxies, no magic.

## Quick Example

```java
// 1. Create context
Context ctx = Context.builder()
    .baseUrl("https://services.odata.org/V4/TripPinService")
    .build();

// 2. Create client
DefaultContainer client = new DefaultContainer(ctx);

// 3. Type-safe query
CollectionPage<Person> people = client.people()
    .filter(Person.FIRST_NAME.equalTo("Scott")
        .and(Person.LAST_NAME.startsWith("K")))
    .select(Person.FIRST_NAME, Person.LAST_NAME)
    .orderBy(Person.LAST_NAME.asc())
    .top(10)
    .get();

// 4. Navigate
PersonEntityRequest req = client.peopleByUserName("scottketchum");
Person scott = req.get();
CollectionPage<Trip> trips = req.trips()
    .filter(Trip.BUDGET.greaterThan(500.0f))
    .get();
```

Ready? [Install the Maven plugin](getting-started.md), then run [your first query](tutorial/first-query.md).

## Architecture

```
odata-codegen/
├── odata-codegen-core/        # Parser + Code Generator
│   ├── model/                # CsdlModel (29 Java records)
│   ├── parser/               # StAX CSDL parser
│   └── generator/            # Entity, Request, Container generators
├── odata-codegen-runtime/     # Runtime library
│   ├── entity/               # Context, ContextPath, SchemaInfo
│   ├── query/                # Expression builders (StringProperty, etc.)
│   ├── http/                 # HttpTransport + JdkHttpTransport
│   ├── auth/                 # AuthProvider implementations
│   ├── serialization/        # JacksonSerializer
│   ├── paging/               # CollectionPage<T>
│   └── exception/            # Typed exception hierarchy
└── odata-codegen-maven-plugin/ # Build-time code generation
```

## Status

- **44 tests passing** — Parser, generator, runtime, and live integration tests
- **Milestones 1-5 complete** — Full pipeline from CSDL to HTTP execution
- **TripPin service tested** — 9 integration tests against live OData service
- **Maven plugin working** — `odata-client:generate` goal in `generate-sources` phase

## Getting Help

- **Questions** — [GitHub Discussions](https://github.com/odata-codegen/odata-codegen/discussions)
- **Bug reports** — [GitHub Issues](https://github.com/odata-codegen/odata-codegen/issues)
- **OData reference** — See [ODATA.md](https://github.com/odata-codegen/odata-codegen/blob/main/ODATA.md)
