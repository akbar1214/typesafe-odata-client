# Generated Code Structure

This page describes the source emitted for a base package such as `com.example.trippin`. Names and exact types depend on the CSDL metadata; the examples below use TripPin names that are present in the repository's generated fixtures.

## Directory Layout

```text
com/example/trippin/
├── entity/
│   ├── Person.java
│   ├── Trip.java
│   └── ...
├── complex/
│   ├── Location.java
│   ├── City.java
│   └── ...
├── enums/
│   └── PersonGender.java
├── entity/request/
│   ├── PersonEntityRequest.java
│   └── ...
├── collection/request/
│   ├── PersonCollectionRequest.java
│   └── ...
├── operation/
│   ├── GetNearestAirportFunctionRequest.java
│   └── ...
├── container/
│   └── DefaultContainer.java
└── schema/
    └── SchemaInfo.java
```

## Entity Classes

Entity classes implement `ODataEntityType`. They are classes rather than records and use protected fields because Jackson populates them through no-argument construction and setters. The no-argument constructor is public for concrete entity types and protected for abstract entity types.

```java
public final class Person implements ODataEntityType {
    public static final StringProperty<Person> FIRST_NAME =
        new StringProperty<>("FirstName", Person.class);

    public static final CollectionProperty<Person, String,
        CollectionProperty.FilterableElement<String>, ?> EMAILS =
        new CollectionProperty<>("Emails", Person.class, String.class,
            CollectionProperty.FilterableElement::new, null, "Edm.String");

    public static final NavCollectionProperty<Person, Trip,
        Trip.Filterable, Trip.Selector> TRIPS =
        new NavCollectionProperty<>("Trips", Person.class, Trip.class,
            Trip.Filterable::new, Trip.Selector::new);

    public static final NavQuery<Person, Photo, Photo.Selector> PHOTO =
        NavQuery.of("Photo", Photo.Selector::new);

    protected String firstName;
    protected List<String> emails;
    protected List<Trip> trips;
    protected Photo photo;

    public Person() {
    }

    @JsonProperty("FirstName")
    public void setFirstName(String value) {
        this.firstName = value;
    }

    public String getFirstName() {
        return firstName;
    }

    public List<String> getEmails() {
        return emails == null ? List.of() : Collections.unmodifiableList(emails);
    }

    public List<Trip> getTrips() {
        return trips == null ? List.of() : Collections.unmodifiableList(trips);
    }

    public Optional<Photo> getPhoto() {
        return Optional.ofNullable(photo);
    }
}
```

A scalar getter returns `Optional<T>` when its CSDL property is nullable and returns the boxed type otherwise. Collection getters always return an unmodifiable, empty-safe list. Singleton navigation getters return `Optional<T>`.

Generated query constants are type-specific: scalar and enum properties implement `PropertyExpression`; complex-typed, binary, and spatial properties receive select-only `SelectableProperty` constants; collection-valued structural properties receive `SelectableCollectionProperty`; and entity navigations use `NavCollectionProperty` or `NavQuery`. `Edm.Stream` properties receive no query constant at all (a stream accompanies `$select`, it is not named by it, and has dedicated stream methods on the entity request), and complex-target navigation constants are not emitted.

### Builders and copy-on-write

Concrete top-level entity types receive `builder()`. `with*()` methods are emitted only when the plugin's `generateWithMethods` parameter is true. They copy collection fields and dynamic-property maps, and merge the changed CSDL name into `changedFields`. The copy-on-write calls below therefore assume that option was enabled for the generated output.

```java
Person person = Person.builder()
    .userName("scott")
    .firstName("Scott")
    .build();

Person renamed = person.withFirstName("Scotty");
```

### Selector and Filterable views

Each entity exposes a `Selector` for request selector lambdas and a `Filterable` view for collection `any`/`all` lambdas. `Selector` fields share the static constants where possible. Entity navigation collections use `NavCollectionProperty`; structural primitive collections use `CollectionProperty.FilterableElement`.

### Inheritance and open types

CSDL `BaseType` values become Java `extends` clauses on model classes. Base properties, keys, and navigation members are inherited; request generators re-emit the applicable navigation, stream, key, and bound-operation members on subtype request classes. Abstract types remain abstract.

Open types emit `@JsonAnySetter` and `@JsonAnyGetter` support, expose `getUnmappedFields()` and `getDynamicProperty(...)`, and filter `@odata.*` control fields. Derived types emit a getter-only `@JsonProperty("@odata.type")` carrying the qualified subtype name.

## Entity Request Classes

Entity requests are final classes. They support typed `select` and `expand` options, navigation requests, CRUD, `$ref`, and batch conversion; media and bound-operation methods are added when the metadata declares them.

```java
public final class PersonEntityRequest {
    public PersonEntityRequest select(
        SelectableExpression<? super Person>... properties);
    public PersonEntityRequest select(
        Function<Person.Selector,
            ? extends SelectableExpression<? super Person>>... selectors);

    public PersonEntityRequest expand(Expandable<? super Person>... expandables);
    public PersonEntityRequest expand(
        Function<Person.Selector, ? extends Expandable<? super Person>> query);

    public Person get();
    public Person patch(Person entity);
    public Person patchWithETag(Person entity, String etag);
    public Person put(Person entity);
    public Person putWithETag(Person entity, String etag);
    public void delete();
    public void deleteWithETag(String etag);

    public TripCollectionRequest trips();
    public TripEntityRequest trips(Integer tripId);
}
```

`get()` returns `Person` directly, not `Optional<Person>`. An empty response body can result in `null`.

Media-capable requests add methods such as `streamMedia()`, `setMedia(InputStream)`, `setMedia(InputStream, String etag)`, `streamPhoto()`, and `setPhoto(...)`.

Batch conversion methods include `toBatchOperation()`, `postToBatchOperation(...)` on collection requests, and `patchToBatchOperation(...)`, `putToBatchOperation(...)`, and `deleteToBatchOperation()` on entity requests. The entity-request batch GET includes the request's current `$select` and `$expand` options.

## Collection Request Classes

Collection requests are final immutable-style request builders. Each option returns a new request instance.

```java
public final class PersonCollectionRequest {
    public PersonCollectionRequest filter(
        FilterExpression<? super Person> predicate);
    public PersonCollectionRequest filter(
        Function<Person.Selector,
            ? extends FilterExpression<? super Person>> predicate);

    public PersonCollectionRequest select(
        SelectableExpression<? super Person>... properties);
    public PersonCollectionRequest select(
        Function<Person.Selector,
            ? extends SelectableExpression<? super Person>>... selectors);

    public PersonCollectionRequest expand(
        Expandable<? super Person>... expandables);
    public PersonCollectionRequest expand(
        Function<Person.Selector,
            ? extends Expandable<? super Person>> query);

    public PersonCollectionRequest orderBy(
        OrderExpression<? super Person, ?>... expressions);
    public PersonCollectionRequest top(int count);
    public PersonCollectionRequest skip(int count);
    public PersonCollectionRequest count();
    public PersonCollectionRequest search(String term);
    public PersonCollectionRequest apply(ApplyExpression expression);
    public PersonCollectionRequest apply(String rawExpression);

    public CollectionPage<Person> get();
    public Stream<Person> stream();
    public List<Person> toList();
    public Person create(Person entity);
    public PersonCollectionRequest nextPage(String nextLink);
    public long countValue();
}
```

`count()` adds `$count=true` to the collection response. `countValue()` uses the `/$count` endpoint and removes options that are not valid for a count-only request. Zero-argument `select()` and `orderBy()` bridges exist because the generated request has both constant and selector-lambda varargs overloads.

## Container Classes

The generated container owns the `Context` and exposes one accessor per entity set, singleton, function import, and action import. Keyed entity sets receive overloads that return entity requests directly.

```java
public class DefaultContainer {
    public PersonCollectionRequest people();
    public PersonEntityRequest people(String userName);
    public TripCollectionRequest trips();
    public GetNearestAirportFunctionRequest getNearestAirport(
        double lat, double lon);
    public ResetDataSourceActionRequest resetDataSource();
}
```

Collection requests do not expose a separate keyed accessor family. Use the keyed container overload and, for navigation, the keyed overload on the containing entity request.

## Complex Types

Complex types implement `ODataType`, have protected fields and typed setters/getters, and use the same inheritance conventions as entities. Concrete complex types have a public no-argument constructor; abstract complex types have a protected one. They have no key and no request class. A concrete top-level complex type receives a `Builder`; concrete subtypes use copy-on-write methods where enabled.

## Enums

Generated enums implement `ODataEnumValue`. They retain the CSDL numeric value, expose `getValue()`, serialize using the original CSDL member wire name through `@JsonValue`, and deserialize strings or numbers through `fromJson(Object)`. Sanitized Java member names do not change the wire name.

## SchemaInfo

Each output package receives one aggregate registry:

```java
public class SchemaInfo
        implements io.github.akbarhusain.odata.runtime.entity.SchemaInfo {
    public static final SchemaInfo INSTANCE = new SchemaInfo();

    @Override
    public Class<?> getClassFromTypeWithNamespace(String name) {
        return classes.get(name);
    }
}
```

The registry maps fully qualified CSDL type names to generated classes and is used for polymorphic `@odata.type` reads.

## Naming and URL Rules

| Metadata name | Generated form |
|---------------|----------------|
| `Person` entity | `Person.java` |
| `FirstName` property | `firstName` field, `FIRST_NAME` constant |
| `GetTrips` function | `getTrips()` accessor where bound, or an operation request class |
| Namespace `Microsoft.OData.SampleService.Models.TripPin` | configured base package plus generated suffix |

Single-key accessors use a nameless key predicate, while composite keys include property names. Generated key formatting uses the CSDL Edm type rather than guessing from the Java value shape.
