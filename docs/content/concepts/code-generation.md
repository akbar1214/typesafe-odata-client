# How Code Generation Works

OData Codegen uses build-time code generation to create type-safe Java classes from OData CSDL metadata.

## Pipeline

```
CSDL XML Metadata
    ↓
StAX Parser (StaxCsdlParser)
    ↓
CsdlModel (29 Java records)
    ↓
Code Generator (7 generators)
    ↓
Java Source Files
    ↓
Java Compiler
    ↓
Type-Safe Client Classes
```

## Step 1: Parse Metadata

The StAX parser reads the CSDL XML and builds an in-memory model:

```java
// Input: TripPin metadata XML
// Output: CsdlModel with entities, complex types, enums, etc.

CsdlModel model = new StaxCsdlParser().parse(metadataInputStream);
```

The model is represented by 29 Java records:

```
CsdlModel
├── EntityType (name, baseType, key, properties, navigationProperties, annotations)
├── ComplexType (name, properties)
├── EnumType (name, members)
├── EntityContainer (entitySets, actionImports, functionImports)
├── Action (name, parameters, returnType)
├── Function (name, parameters, returnType, isBound)
└── ...
```

## Step 2: Generate Code

Seven generators transform the model into Java source files:

| Generator | Output | Description |
|-----------|--------|-------------|
| `EntityGenerator` | `entity/*.java` | Immutable entity classes with builders |
| `ComplexTypeGenerator` | `complex/*.java` | Immutable complex type classes |
| `EnumGenerator` | `enums/*.java` | Java enums for OData enum types |
| `RequestGenerator` | `request/*.java` | Entity and collection request classes |
| `ContainerGenerator` | `container/*.java` | Client entry point classes |
| `SchemaInfoGenerator` | `schema/*.java` | Type-to-class mapping |
| `ActionGenerator` | (included in request) | Bound and unbound actions |

## Step 3: Compile

The generated Java files are compiled alongside your application code. This is where type checking happens:

- Property names are validated against the schema
- Expression types are checked (e.g., can't call `greaterThan()` on a string)
- Method signatures are verified
- Import statements are resolved

## Example: Entity Generation

For each OData entity type, the generator creates:

1. **Immutable-by-contract class** — `final` class with `protected` fields, copy-on-write `with*()`, and `Builder`
2. **Builder class** — for constructing instances (root-level concrete types only)
3. **Static property constants** — for type-safe queries (`UPPER_CASE`, e.g., `Person.FIRST_NAME`)
4. **`odataTypeName()` method** — returns the OData type name

## Key Design Decisions

### Immutable-by-Contract Classes Over Records

Generated entities are `final` classes with protected fields and Jackson `@JsonProperty` setters, not records:

```java
// Generated entity
public final class Person implements ODataEntityType {
    public static final StringProperty<Person> FIRST_NAME = new StringProperty<>("FirstName", Person.class);
    protected String userName;
    protected String firstName;
    protected String lastName;
    protected List<String> emails;
    protected Long age;
    protected List<Trip> trips;
    // Builder, with*() copy-on-write, getters (unmodifiableList / Optional)
}
```

### Static Property Constants

Each property has a static constant for type-safe queries:

```java
public static final StringProperty FIRST_NAME = new StringProperty("FirstName");
public static final NumberProperty<Long> AGE = new NumberProperty<>("Age", Long.class);
```

### No Annotations

Generated entities have no Jackson/Gson annotations. Serialization is pluggable via the `Serializer` interface.

## What's Next

- [The Context Pattern](context.md) — How HTTP execution is configured
- [Type-Safe Query Building](query-builder.md) — Expression hierarchy
