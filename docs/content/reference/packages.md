# Package Structure

Module organization and dependency graph.

## Modules

```
modern-odata-client/
├── odata-client-core/        # Parser + Code Generator
├── odata-client-runtime/     # Runtime library
├── odata-client-maven-plugin/ # Build-time code generation
└── docs/                     # Documentation (MkDocs)
```

## Dependency Graph

```
odata-client-maven-plugin
    └── odata-client-core

odata-client-runtime
    └── (no internal dependencies)

Generated code
    └── odata-client-runtime
```

## odata-client-core

Parser and code generator.

### Contents

- `model/` — CsdlModel records (29 types)
- `parser/` — StaxCsdlParser
- `generator/` — 7 code generators
- `test/` — Parser and generator tests

### Dependencies

- `javax.xml.stream` (JDK built-in)
- No external dependencies

## odata-client-runtime

Runtime library for generated code.

### Contents

- `entity/` — Context, ContextPath, SchemaInfo
- `query/` — Expression hierarchy
- `http/` — HttpTransport, JdkHttpTransport, ApacheHttpTransport
- `auth/` — AuthProvider implementations
- `serialization/` — JacksonSerializer
- `paging/` — CollectionPage
- `exception/` — Typed exceptions
- `client/` — EntityOperations (HTTP execution)

### Dependencies

- Jackson (optional, default serializer)
- Apache HttpClient (optional)
- Java 17+

## odata-client-maven-plugin

Maven plugin for code generation.

### Contents

- `GenerateMojo.java` — Maven goal

### Dependencies

- `maven-core`
- `odata-client-core`

## Generated Code

Code generated from CSDL metadata.

### Contents

- `entity/` — Immutable entity records
- `complex/` — Immutable complex type records
- `enums/` — Java enums
- `request/` — Request classes
- `container/` — Client entry points
- `schema/` — SchemaInfo implementations

### Dependencies

- `odata-client-runtime`
- Jackson (optional)
- Apache HttpClient (optional)

## Versioning

All modules share the same version number:

```
{{ odata_client_version }}
```

## What's Next

- [Contributing](../contributing.md) — How to contribute
- [Release Notes](../release-notes.md) — What's new
