# Maven Plugin Configuration

Configure `io.github.akbarhusain:odata-codegen-maven-plugin` to generate a client during Maven's `generate-sources` phase.

## Basic Configuration

The current project version is `0.1.0-SNAPSHOT`. These snapshot artifacts are not published; install this checkout with `./mvnw -DskipTests install` before consuming them from another Maven project.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.akbarhusain</groupId>
            <artifactId>odata-codegen-maven-plugin</artifactId>
            <version>0.1.0-SNAPSHOT</version>
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

## Options

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| `metadataUrl` | `String` | One of URL/file | HTTP(S) CSDL metadata URL |
| `metadataFile` | `File` | One of URL/file | Local CSDL metadata file |
| `basePackage` | `String` | No | Default output package; derived from the schema namespace when omitted |
| `schemaPackages` | List | No | Per-schema namespace-to-package mappings |
| `outputDirectory` | `File` | No | Defaults to `${project.build.directory}/generated-sources/odata` |
| `generateWithMethods` | `boolean` | No | Generate copy-on-write methods; defaults to `false` |
| `metadataHeaders` | `Properties` | No | Headers used when downloading `metadataUrl` |
| `skip` | `boolean` | No | Skip generation with `-Dodata.skip=true` |
| `forceRegenerate` | `boolean` | No | Ignore incremental state with `-Dodata.forceRegenerate=true` |

Exactly one of `metadataUrl` and `metadataFile` is normally supplied. If both are present, the file wins and the URL is ignored.

## Local Metadata

```xml
<configuration>
    <metadataFile>src/main/resources/metadata.xml</metadataFile>
    <basePackage>com.example.myservice</basePackage>
</configuration>
```

A private metadata endpoint can receive authentication headers:

```xml
<configuration>
    <metadataUrl>https://your-service.example/odata/$metadata</metadataUrl>
    <metadataHeaders>
        <Authorization>Bearer token</Authorization>
    </metadataHeaders>
    <basePackage>com.example.myservice</basePackage>
</configuration>
```

The plugin follows HTTP redirects for metadata downloads and rejects malformed or unsupported metadata sources.

## Schema-to-Package Mappings

When a metadata document contains several schemas, map namespaces explicitly when they should not share the default package:

```xml
<configuration>
    <metadataFile>src/main/resources/metadata.xml</metadataFile>
    <basePackage>com.example.default</basePackage>
    <schemaPackages>
        <schema>
            <namespace>Example.Models</namespace>
            <packageName>com.example.models</packageName>
        </schema>
        <schema>
            <namespace>Example.Shared</namespace>
            <packageName>com.example.shared</packageName>
        </schema>
    </schemaPackages>
</configuration>
```

Generated output uses the following package suffixes:

```text
com/example/models/
├── entity/
├── complex/
├── enums/
├── entity/request/
├── collection/request/
├── operation/
├── container/
└── schema/
```

One `SchemaInfo` class is generated per output package, even when multiple schemas are mapped to that package.

## Generated Source Root

The plugin adds its output directory to the Maven compile source roots. If an IDE does not detect it, add `target/generated-sources/odata` as a generated source root, or use `build-helper-maven-plugin`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>add-generated-sources</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>${project.build.directory}/generated-sources/odata</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Incremental Generation

Generation is incremental by default. A marker records the metadata source/configuration identity, plugin and core implementation fingerprint, and a manifest of generated files. When the marker is current, the plugin reuses the source tree; when generation runs, stale files from the previous manifest are removed safely. Use `-Dodata.forceRegenerate=true` to bypass incremental reuse explicitly.

The goal is marked thread-safe for Maven parallel builds.

## What's Next

- [Generated Code Structure](generated-code.md)
- [Query Expression API](query-api.md)
