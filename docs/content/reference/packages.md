# Package Structure

Module organization and dependency graph.

## Modules

```
odata-codegen/
├── odata-codegen-core/        # Parser + Code Generator
├── odata-codegen-runtime/     # Runtime library
├── odata-codegen-maven-plugin/ # Build-time code generation
└── docs/                     # Documentation (MkDocs)
```

## Dependency Graph

```
odata-codegen-maven-plugin
    └── odata-codegen-core

odata-codegen-runtime
    └── (no internal dependencies)

Generated code
    └── odata-codegen-runtime
```

## odata-codegen-core

Parser and code generator.

### Contents

- `model/` — CsdlModel records (29 types)
- `parser/` — StaxCsdlParser
- `generator/` — 7 code generators
- `test/` — Parser and generator tests

### Dependencies

- `javax.xml.stream` (JDK built-in)
- No external dependencies

## odata-codegen-runtime

Runtime library for generated code.

### Contents

- `entity/` — Context, ContextPath, SchemaInfo
- `query/` — Expression hierarchy (StringProperty, NumberProperty, BooleanProperty, DateTimeProperty, GuidProperty, EnumProperty, CollectionProperty, FilterExpression)
- `http/` — HttpTransport, HttpRequest, HttpResponse, JdkHttpTransport
- `auth/` — AuthProvider implementations
- `serialization/` — JacksonSerializer, DynamicPropertyConverter
- `paging/` — CollectionPage
- `batch/` — BatchOperation, BatchRequest, BatchResponse, MultipartHelper
- `exception/` — Typed exceptions (ODataException hierarchy)
- `client/` — EntityOperations (HTTP execution)

### Dependencies

- Jackson (optional, default serializer)
- Apache HttpClient (optional)
- Java 17+

## odata-codegen-maven-plugin

Maven plugin for code generation.

### Contents

- `GenerateMojo.java` — Maven goal

### Dependencies

- `maven-core`
- `odata-codegen-core`

## Generated Code

Code generated from CSDL metadata.

### Contents

- `entity/` — Immutable entity classes (final, copy-on-write, `with*()` / `Builder`)
- `complex/` — Immutable complex type classes
- `enums/` — Java enums (with `fromValue` / `fromJson` / `fromFlags`)
- `request/` — Collection and entity request classes (type-safe query, CRUD, `$ref`, media)
- `container/` — Client entry points (e.g., `DefaultContainer`)
- `schema/` — SchemaInfo implementations (`SchemaInfo`)

### Dependencies

- `odata-codegen-runtime`
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
