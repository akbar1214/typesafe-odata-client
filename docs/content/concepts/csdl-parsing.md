# CSDL Metadata Parsing

How Modern OData Client processes OData CSDL metadata.

## What is CSDL?

CSDL (Conceptual Schema Definition Language) is the XML format used by OData to describe data models. It defines:

- **Entity Types** — Data structures (Person, Trip, Airline)
- **Complex Types** — Nested structures (Location, City)
- **Enum Types** — Enumeration values (PersonGender)
- **Entity Containers** — Entry points (default container)
- **Functions & Actions** — Operations on entities

## Example: TripPin Metadata

```xml
<edmx:Edmx Version="4.0">
  <edmx:DataServices>
    <Schema Namespace="Microsoft.OData.SampleService.Models.TripPin">
      <EntityType Name="Person">
        <Key>
          <PropertyRef Name="UserName"/>
        </Key>
        <Property Name="UserName" Type="Edm.String" Nullable="false"/>
        <Property Name="FirstName" Type="Edm.String"/>
        <Property Name="LastName" Type="Edm.String"/>
        <Property Name="Age" Type="Edm.Int64"/>
        <Property Name="Emails" Type="Collection(Edm.String)"/>
        <NavigationProperty Name="Trips" Type="Collection(Trip)"/>
      </EntityType>
      <!-- More types... -->
    </Schema>
  </edmx:DataServices>
</edmx:Edmx>
```

## StAX Parser

Modern OData Client uses StAX (Streaming API for XML) for parsing:

### Why StAX Over JAXB?

| Feature | StAX | JAXB |
|---------|------|------|
| XSD dependency | None | Required |
| Memory usage | Low (cursor) | High (tree) |
| Namespace handling | Explicit | Implicit |
| Java version | 6+ | 6+ (but complex setup) |

### How It Works

```java
StaxCsdlParser parser = new StaxCsdlParser();
CsdlModel model = parser.parse(metadataInputStream);
```

The parser:

1. Creates an `XMLStreamReader` from the input stream
2. Iterates through events (`START_ELEMENT`, `CHARACTERS`, `END_ELEMENT`)
3. Builds `CsdlModel` records as it encounters elements
4. Returns the complete model

## CsdlModel Structure

```java
public record CsdlModel(
    List<EntityType> entityTypes,
    List<ComplexType> complexTypes,
    List<EnumType> enumTypes,
    List<Actions> actions,
    List<Functions> functions,
    EntityContainer entityContainer,
    List<Annotations> annotations,
    List<Schema> schemas
) {}
```

### EntityType

```java
public record EntityType(
    String name,
    String baseType,
    List<Property> key,
    List<Property> properties,
    List<NavigationProperty> navigationProperties,
    List<Annotations> annotations
) {}
```

### Property

```java
public record Property(
    String name,
    String type,
    boolean nullable,
    String defaultValue,
    int maxLength,
    int precision,
    int scale,
    boolean unicode,
    List<Annotations> annotations
) {}
```

## Handling Variations

### Namespace Differences

OData v3 uses different namespaces than v4:

```java
// v3: http://schemas.microsoft.com/ado/2009/11/edm
// v4: http://docs.oasis-open.org/odata/ns/edm

String localPart = reader.getLocalName();
if (localPart.equals("EntityType")) {
    // Handle entity type regardless of namespace
}
```

### Self-Closing Tags

Some metadata uses self-closing tags with redundant closing tags:

```xml
<!-- This is valid XML -->
<Property Name="UserName" Type="Edm.String" Nullable="false"/>
</Property>
```

The parser handles this by checking for `END_ELEMENT` events after self-closing tags.

## Error Handling

The parser throws `CsdlParsingException` with details:

```java
try {
    CsdlModel model = parser.parse(inputStream);
} catch (CsdlParsingException e) {
    System.out.println("Parse error at line " + e.getLineNumber() +
                       ": " + e.getMessage());
}
```

## What's Next

- [Generated Code Reference](../reference/generated-code.md) — Complete structure
- [OData URL Patterns](../reference/odata-urls.md) — URL building rules
