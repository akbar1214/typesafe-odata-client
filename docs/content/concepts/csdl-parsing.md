# CSDL Metadata Parsing

OData Codegen uses the JDK StAX API to read OData v4 CSDL XML into immutable Java records.

## Supported Document Shape

The parser accepts an OData v4 `edmx:Edmx` document with the namespace `http://docs.oasis-open.org/odata/ns/edmx`. It reads v4 schema namespaces such as `http://docs.oasis-open.org/odata/ns/edm`.

OData v3 EDMX metadata and non-CSDL documents fail loudly. The parser does not silently turn an unsupported document into an empty model.

```xml
<edmx:Edmx Version="4.0"
           xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
  <edmx:DataServices>
    <Schema Namespace="Example.Models"
            xmlns="http://docs.oasis-open.org/odata/ns/edm">
      <EntityType Name="Person">
        <Key>
          <PropertyRef Name="UserName"/>
        </Key>
        <Property Name="UserName" Type="Edm.String" Nullable="false"/>
        <Property Name="FirstName" Type="Edm.String" Nullable="true"/>
      </EntityType>
    </Schema>
  </edmx:DataServices>
</edmx:Edmx>
```

## Parsing

```java
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import javax.xml.stream.XMLStreamException;
import java.io.InputStream;
import java.util.List;

StaxCsdlParser parser = new StaxCsdlParser();
try {
    CsdlModel model = parser.parse(inputStream);
    List<String> warnings = model.warnings();
} catch (XMLStreamException e) {
    throw new IllegalStateException("Could not read CSDL XML", e);
}
```

`parse(InputStream)` declares the StAX `XMLStreamException` for malformed XML or I/O parsing failures. Structural validation errors use `IllegalArgumentException`, including unsupported root namespaces, missing required attributes, malformed collection type expressions, invalid enum numeric values, duplicate aliases, and invalid or ambiguous container inheritance. Alias-qualified names are normalized during parsing, but the parser does not prove that every referenced model type exists; unknown targets are rejected by the generator.

## Model Shape

`CsdlModel` contains `schemas` and parser `warnings`. Each `SchemaModel` contains entity types, complex types, enums, type definitions, functions, actions, and containers. The important nested records are:

```java
public record CsdlModel(List<SchemaModel> schemas, List<String> warnings) {}

public record SchemaModel(
    String namespace,
    String alias,
    List<EntityTypeModel> entityTypes,
    List<ComplexTypeModel> complexTypes,
    List<EnumTypeModel> enumTypes,
    List<TypeDefinitionModel> typeDefinitions,
    List<FunctionModel> functions,
    List<ActionModel> actions,
    List<ContainerModel> containers
) {}

public record EntityTypeModel(
    String name,
    String baseType,
    boolean openType,
    boolean abstractType,
    boolean hasStream,
    List<KeyModel> keys,
    List<PropertyModel> properties,
    List<NavigationPropertyModel> navigationProperties
) {}
```

Property nullability, collection types, aliases, key references, operation parameters, and return types are retained in their corresponding model records. List-valued record components are defensively copied and exposed as unmodifiable lists.

## Parser Behavior

- Schema aliases are resolved during parsing, with a document-wide alias pass for cross-schema references.
- `Collection(...)` wrappers are normalized and malformed or nested collections are rejected.
- Required attributes such as `Name`, `Type`, and `EntityType` are validated with context.
- Unknown member-level elements are skipped and recorded in `CsdlModel.warnings()`; legal inline `Annotation` elements are ignored silently.
- Entity-container inheritance is merged base-first, with unknown, circular, or ambiguous unqualified references rejected.
- External entities and DTDs are disabled on the StAX input factory.

## What's Next

- [How Code Generation Works](code-generation.md) — Model-to-source generation
- [Generated Code Structure](../reference/generated-code.md) — Output layout and request classes
- [OData URL Patterns](../reference/odata-urls.md) — Key and query rendering
