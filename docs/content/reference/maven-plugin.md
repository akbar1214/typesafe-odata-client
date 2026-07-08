# Maven Plugin Configuration

Configure the `odata-codegen-maven-plugin` for code generation.

## Basic Configuration

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.akbarhusain.odata</groupId>
            <artifactId>odata-codegen-maven-plugin</artifactId>
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

## Configuration Options

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `metadataUrl` | String | Yes* | URL to CSDL metadata endpoint |
| `metadataFile` | File | Yes* | Local path to CSDL metadata file |
| `basePackage` | String | Yes | Base package for generated classes |
| `schemaPackages` | Map | No | Schema-to-package mappings |

*Either `metadataUrl` or `metadataFile` is required.

## Using a Local File

```xml
<configuration>
    <metadataFile>src/main/resources/metadata.xml</metadataFile>
    <basePackage>com.example.myservice</basePackage>
</configuration>
```

## Schema-to-Package Mappings

When your metadata has multiple schemas, map them to different packages:

```xml
<configuration>
    <metadataUrl>https://services.odata.org/V4/TripPinService/$metadata</metadataUrl>
    <basePackage>com.example.trippin</basePackage>
    <schemaPackages>
        <entry key="Microsoft.OData.SampleService.Models.TripPin">com.example.trippin.trippin</entry>
        <entry key="com.example.shared">com.example.shared</entry>
    </schemaPackages>
</configuration>
```

## Downloading Metadata

If your metadata endpoint requires authentication or redirects, download it first:

```bash
# Download with authentication
curl -H "Authorization: Bearer token" \
     -o metadata.xml \
     https://your-service.com/odata/$metadata

# Follow redirects
curl -L -o metadata.xml https://services.odata.org/V4/TripPinService/$metadata
```

Then reference the local file:

```xml
<configuration>
    <metadataFile>metadata.xml</metadataFile>
    <basePackage>com.example.myservice</basePackage>
</configuration>
```

## Generated Output

The plugin generates Java files in `target/generated-sources/odata/`:

```
target/generated-sources/odata/
└── com/example/trippin/
    ├── entity/
    │   ├── Person.java
    │   ├── Trip.java
    │   └── ...
    ├── complex/
    │   ├── Location.java
    │   └── City.java
    ├── enums/
    │   └── PersonGender.java
    ├── request/
    │   ├── PersonEntityRequest.java
    │   ├── PersonCollectionRequest.java
    │   └── ...
    ├── container/
    │   └── DefaultContainer.java
    └── schema/
        └── ServiceSchemaInfo.java
```

## Adding Generated Sources

Most IDEs automatically detect `target/generated-sources/`. If not, add manually:

### IntelliJ IDEA

1. Right-click `target/generated-sources/odata`
2. Mark Directory as → Generated Sources Root

### Eclipse

1. Project → Properties → Java Build Path
2. Source tab → Add Folder
3. Select `target/generated-sources/odata`

### Maven Build Helper

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>add-source</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>target/generated-sources/odata</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## What's Next

- [Generated Code Reference](generated-code.md) — Complete structure
- [Query Expression API](query-api.md) — Complete list of operations
