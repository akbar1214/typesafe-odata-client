# How Code Generation Works

OData Codegen parses CSDL metadata and writes Java source files during the Maven `generate-sources` phase.

## Pipeline

```text
CSDL XML
  -> StaxCsdlParser
  -> CsdlModel records
  -> type, request, operation, container, and schema generators
  -> generated Java source files
  -> javac and the application build
```

## Parsed Model

`CsdlModel` is an immutable record containing schemas and parser warnings. A schema contains entity types, complex types, enums, type definitions, functions, actions, and containers. Properties retain their Edm type and nullability; navigation properties retain their target type, containment, and referential constraints.

## Generated Packages

For a base package `com.example.trippin`, the generator writes:

| Package | Contents |
|---------|----------|
| `entity` | Entity model classes |
| `complex` | Complex model classes |
| `enums` | Java enum types |
| `entity.request` | Entity request classes |
| `collection.request` | Collection request classes |
| `operation` | Function and action request classes |
| `container` | Entity-container entry points |
| `schema` | `SchemaInfo` registry |

A schema-to-package mapping can place different schemas under different base packages. One `SchemaInfo` is emitted per resolved output package and merges all schemas assigned to that package.

## Entity Generation

For each entity type, the generator emits:

1. A Java class implementing `ODataEntityType`.
2. Protected fields and a no-argument constructor for Jackson and generated builders; the constructor is public on concrete model types and protected on abstract model types.
3. Public `@JsonProperty` setters for declared properties and navigations.
4. Typed static constants for supported scalar/enum property expressions, collection-valued properties, and entity navigation properties. Binary, stream, and spatial properties do not receive property constants, and complex-valued entity navigation constants are not emitted.
5. `Filterable` and `Selector` inner classes used by collection lambdas and request selector lambdas.
6. A `Builder` for concrete top-level types.
7. Copy-on-write `with*()` methods when `generateWithMethods` is enabled and the type is concrete.

Entities are not Java records. Generated builders and copy-on-write methods create model values, while public setters support Jackson and application updates; collection getters return unmodifiable empty-safe views and nullable scalar getters follow the CSDL `Nullable` attribute.

## Inheritance and Open Types

CSDL `BaseType` relationships become Java `extends` relationships on model classes. The generator walks the complete base chain for properties, keys, constants, and request-layer navigation/operation members. Abstract types remain abstract and do not receive concrete `with*()` methods.

Open entity and complex types capture unknown JSON properties through `@JsonAnySetter` and expose them through `getUnmappedFields()` and `getDynamicProperty(...)`. Derived types also emit the OData type annotation getter used for polymorphic payloads.

## Annotation and Serialization Contract

Generated model classes use Jackson annotations for property and ETag mapping, and derived types may emit a getter-only `@JsonProperty("@odata.type")`. The default `JacksonSerializer` is the supported wire implementation. The `Serializer` interface remains pluggable, but a replacement must honor the generated annotations and OData JSON conventions.

## What's Next

- [The Context Pattern](context.md) — Runtime configuration
- [Type-Safe Query Building](query-builder.md) — Expression hierarchy
- [Generated Code Structure](../reference/generated-code.md) — Detailed request APIs
