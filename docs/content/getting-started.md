# Getting Started

This guide walks you through setting up Modern OData Client in your Maven project.

## Prerequisites

- Java 17 or later
- Maven 3.9 or later
- An OData v4 service endpoint (we'll use [TripPin](https://services.odata.org/V4/TripPinService) as an example)

## 1. Add the Maven Plugin

Add the plugin to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.modernodata</groupId>
            <artifactId>odata-client-maven-plugin</artifactId>
            <version>{{ odata_client_version }}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>generate</goal>
                    </goals>
                    <configuration>
                        <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
                        <basePackage>com.example.trippin</basePackage>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## 2. Add the Runtime Dependency

```xml
<dependencies>
    <dependency>
        <groupId>com.modernodata</groupId>
        <artifactId>odata-client-runtime</artifactId>
        <version>{{ odata_client_version }}</version>
    </dependency>
</dependencies>
```

## 3. Generate the Client

```bash
mvn generate-sources
```

This generates Java classes in `target/generated-sources/odata/`:

```
com/example/trippin/
├── entity/
│   ├── Person.java
│   ├── Trip.java
│   ├── Photo.java
│   └── ...
├── enums/
│   └── PersonGender.java
├── complex/
│   ├── Location.java
│   └── City.java
├── request/
│   ├── PersonEntityRequest.java
│   ├── PersonCollectionRequest.java
│   └── ...
├── container/
│   └── DefaultContainer.java
└── schema/
    └── ServiceSchemaInfo.java
```

## 4. Use the Client

```java
import com.modernodata.runtime.entity.Context;
import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Person;
import com.modernodata.runtime.paging.CollectionPage;

public class Main {
    public static void main(String[] args) {
        // Create context with the service base URL
        Context ctx = Context.builder()
            .baseUrl("https://services.odata.org/V4/TripPinService")
            .build();

        // Create the client
        DefaultContainer client = new DefaultContainer(ctx);

        // Query all people
        CollectionPage<Person> people = client.people().get();

        // Print results
        for (Person person : people) {
            System.out.println(person.getFirstName() + " " + person.getLastName());
        }
    }
}
```

## 5. Run It

```bash
mvn compile exec:java -Dexec.mainClass="com.example.trippin.Main"
```

You should see:

```
Scott Ketchum
Keith Combs
Louis Sons
...
```

## Alternative: Local Metadata File

If your OData service doesn't expose `$metadata` publicly, download it first:

```bash
curl -o metadata.xml https://your-service.com/odata/$metadata
```

Then reference it as a file:

```xml
<configuration>
    <metadataFile>metadata.xml</metadataFile>
    <basePackage>com.example.myservice</basePackage>
</configuration>
```

## What's Next

- [Your First Query](tutorial/first-query.md) — Walk through a complete example
- [Filter with Type-Safe Expressions](how-to/filter.md) — Build compile-time safe filters
- [CRUD Operations](how-to/crud.md) — Create, update, and delete entities
