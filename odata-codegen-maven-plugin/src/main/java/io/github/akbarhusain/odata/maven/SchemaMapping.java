package io.github.akbarhusain.odata.maven;

/**
 * Maps a CSDL namespace to a Java package name for code generation.
 *
 * <pre>{@code
 * <schemaPackages>
 *     <schema>
 *         <namespace>My.Service.Models</namespace>
 *         <packageName>com.example.myservice.models</packageName>
 *     </schema>
 * </schemaPackages>
 * }</pre>
 */
public class SchemaMapping {

    private String namespace;
    private String packageName;

    public SchemaMapping() {}

    public SchemaMapping(String namespace, String packageName) {
        this.namespace = namespace;
        this.packageName = packageName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
}
