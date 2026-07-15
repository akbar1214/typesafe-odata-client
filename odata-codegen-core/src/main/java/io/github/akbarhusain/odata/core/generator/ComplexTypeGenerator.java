package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.ComplexTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ComplexTypeGenerator extends AbstractTypeGenerator {

    private Map<String, ComplexTypeModel> complexTypeMap;
    private Map<String, ComplexTypeModel> complexTypeByQualifiedName;
    private java.util.Map<String, String> complexTypeNamespace;
    private java.util.Map<String, Set<String>> schemaOpenRootNames;

    public ComplexTypeGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null, List.of());
    }

    public ComplexTypeGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage) {
        this(basePackage, schemaPackages, defaultBasePackage, List.of());
    }

    public ComplexTypeGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage, List<SchemaModel> allSchemas) {
        this(basePackage, schemaPackages, defaultBasePackage, allSchemas, false);
    }

    public ComplexTypeGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage, List<SchemaModel> allSchemas, boolean generateWithMethods) {
        super(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
    }

    public ComplexTypeGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public String generate(ComplexTypeModel complexType, SchemaModel schema) {
        initEffectiveSchemas(schema);
        ensureSchemaCache(schema);
        String pkg = basePackage + Names.packageNameSuffixComplexType();
        String className = Names.complexTypeClassName(complexType.name());
        ComplexTypeModel base = findBase(complexType);
        String baseSimpleName = base != null ? Names.complexTypeClassName(Names.simpleNameFromFullName(complexType.baseType())) : null;

        List<PropertyModel> ownProps = complexType.properties();
        List<PropertyModel> inheritedProps = inheritedProperties(complexType);
        List<PropertyModel> allProps = new ArrayList<>(inheritedProps);
        allProps.addAll(ownProps);

        List<NavigationPropertyModel> ownNavs = complexType.navigationProperties();
        List<NavigationPropertyModel> inheritedNavs = inheritedNavProperties(complexType);
        List<NavigationPropertyModel> allNavs = new ArrayList<>(inheritedNavs);
        allNavs.addAll(ownNavs);

        // OpenType dynamic-property support: capture undeclared JSON fields into unmappedFields.
        boolean openType = openTypeResolved(complexType);
        boolean firstOpen = openType && (base == null || !openTypeResolved(base));
        boolean rootMutableMap = base == null && subtreeHasOpen(complexType);
        // hierarchyHasOpen: true when any type in the hierarchy is open.
        // Used for internal constructor and with*() — subtypes also need to preserve unmappedFields.
        boolean hierarchyHasOpen = subtreeHasOpen(rootOf(complexType));

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        boolean hasCollection = false;
        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ODataType");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.query.*");
        if (openType) {
            imports.add("io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter");
        }
        if (base != null) {
            imports.add(basePackageForType(complexType.baseType(), schema) + Names.packageNameSuffixComplexType() + "." + baseSimpleName);
        }
        for (PropertyModel prop : allProps) {
            addPropertyImports(prop, imports, schema);
            if (Names.isCollectionType(prop.edmType())) {
                hasCollection = true;
            }
        }
        for (NavigationPropertyModel nav : allNavs) {
            addNavImports(nav, imports, schema);
        }
        if (hasCollection || openType || !allNavs.isEmpty()) {
            imports.add("java.util.Collections");
        }
        if (hasCollection || !allNavs.isEmpty()) {
            imports.add("java.util.List");
        }
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Class declaration
        if (complexType.abstractType()) {
            sb.append("public abstract class ").append(className);
        } else {
            sb.append("public class ").append(className);
        }
        if (base != null) {
            sb.append(" extends ").append(baseSimpleName);
        }
        sb.append(" implements ODataType {\n\n");

        // Typed filterable for collection lambda operators (any/all)
        sb.append(generateFilterableClass(allProps, allNavs, className, schema));

        // Fields (protected so subclasses can access inherited state via bare name)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema);
            sb.append("    protected ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }

        // Navigation-property fields hold expanded ($expand) data deserialized from JSON.
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append("    protected ").append(navJavaType(nav, schema)).append(" ")
              .append(Names.toJavaFieldName(nav.name())).append(";\n");
        }
        if (base == null && subtreeHasOpen(complexType)) {
            sb.append("    protected java.util.Map<String, Object> unmappedFields;\n");
        }
        sb.append("\n");

        // No-args constructor for Jackson, Builder, and with*() copy-on-write
        sb.append("    ").append(complexType.abstractType() ? "protected" : "public").append(" ").append(className).append("() {\n");
        if (base != null) {
            sb.append("        super();\n");
        } else if (rootMutableMap) {
            sb.append("        this.unmappedFields = new java.util.HashMap<>();\n");
        }
        sb.append("    }\n\n");

        // Setters annotated with @JsonProperty for Jackson deserialization (also used by Builder/with*)
        if (!complexType.abstractType()) {
            for (PropertyModel prop : ownProps) {
                String javaType = resolvePropertyJavaType(prop, schema);
                String fn = Names.toJavaFieldName(prop.name());
                sb.append("    @com.fasterxml.jackson.annotation.JsonProperty(\"").append(prop.name()).append("\")\n");
                sb.append("    public void set").append(Names.capitalize(fn)).append("(").append(javaType).append(" value) {\n");
                sb.append("        this.").append(fn).append(" = value;\n");
                sb.append("    }\n\n");
            }
            for (NavigationPropertyModel nav : ownNavs) {
                String javaType = navJavaType(nav, schema);
                String fn = Names.toJavaFieldName(nav.name());
                sb.append("    @com.fasterxml.jackson.annotation.JsonProperty(\"").append(nav.name()).append("\")\n");
                sb.append("    public void set").append(Names.capitalize(fn)).append("(").append(javaType).append(" value) {\n");
                sb.append("        this.").append(fn).append(" = value;\n");
                sb.append("    }\n\n");
            }
        }

        // Getters (own props only; inherited getters are inherited from the parent)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema);
            String fn = Names.toJavaFieldName(prop.name());
            if (Names.isCollectionType(prop.edmType())) {
                sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return ").append(fn).append(" == null ? List.of() : Collections.unmodifiableList(").append(fn).append(");\n");
                sb.append("    }\n\n");
            } else if (prop.nullable()) {
                sb.append("    public Optional<").append(javaType).append("> ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return ").append(fn).append(";\n");
                sb.append("    }\n\n");
            }
        }

        // Navigation property getters — materialized expanded ($expand) data
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append(generateNavGetter(nav, schema));
        }

        // with*() copy-on-write methods — generated for all concrete complex types so that
        // subtypes can be modified immutably (the public all-args constructor is reused).
        // Inherited properties are referenced by field name (protected) to avoid wrapping
        // nullable getters' Optional<T> in the raw-typed constructor.
        // Skipped when generateWithMethods is false.
        if (!complexType.abstractType() && generateWithMethods) {
            for (PropertyModel prop : allProps) {
                sb.append(generateWithMethod(prop, allProps, allNavs, className, hierarchyHasOpen, schema));
            }
            for (NavigationPropertyModel nav : allNavs) {
                sb.append(generateNavWithMethod(nav, allProps, allNavs, className, hierarchyHasOpen, schema));
            }
        }

        // Builder — generated only for concrete top-level complex types. Subtypes reuse
        // with*() for copy-on-write (mirrors the entity design: a static builder() in a
        // subtype would clash with the inherited builder() due to different Builder types).
        if (base == null && !complexType.abstractType()) {
            generateBuilder(sb, allProps, ownNavs, className, rootMutableMap, schema);
        }

        // ODataType interface
        sb.append("    @Override\n");
        sb.append("    public String odataTypeName() {\n");
        sb.append("        return \"").append(schema.namespace()).append(".").append(complexType.name()).append("\";\n");
        sb.append("    }\n\n");

        if (openType) {
            sb.append("    @com.fasterxml.jackson.annotation.JsonAnyGetter\n");
        }
        sb.append("    @Override\n");
        sb.append("    public java.util.Map<String, Object> getUnmappedFields() {\n");
        sb.append("        return ").append(openType ? "Collections.unmodifiableMap(unmappedFields)" : "java.util.Map.of()").append(";\n");
        sb.append("    }\n\n");

        // OpenType: capture undeclared JSON fields (dynamic properties) into unmappedFields.
        // Generated only at the topmost open type in the chain to avoid duplicate any-setters.
        if (firstOpen) {
            sb.append("    @com.fasterxml.jackson.annotation.JsonAnySetter\n");
            sb.append("    protected void putDynamicProperty(String name, Object value) {\n");
            sb.append("        if (name != null && !name.startsWith(\"@\")) {\n");
            sb.append("            unmappedFields.put(name, value);\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            sb.append("    public Optional<Object> getDynamicProperty(String name) {\n");
            sb.append("        return Optional.ofNullable(unmappedFields.get(name));\n");
            sb.append("    }\n\n");

            sb.append("    public <T> Optional<T> getDynamicProperty(String name, Class<T> type) {\n");
            sb.append("        Object v = unmappedFields.get(name);\n");
            sb.append("        return v == null ? Optional.empty()\n");
            sb.append("                : Optional.of(io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter.convert(v, type));\n");
            sb.append("    }\n\n");
        }

        sb.append("    @Override\n");
        sb.append("    public ContextPath getContextPath() {\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        // toString (all props; inherited props accessible via protected fields)
        sb.append("    @Override\n");
        sb.append("    public String toString() {\n");
        sb.append("        return \"").append(className).append("{\" +\n");
        boolean first = true;
        for (PropertyModel prop : allProps) {
            String fn = Names.toJavaFieldName(prop.name());
            if (!first) sb.append("            \", ");
            else sb.append("            \"");
            sb.append(prop.name()).append("=\" + ").append(fn).append(" +\n");
            first = false;
        }
        sb.append("            \"}\";\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String generateWithMethod(PropertyModel prop, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, boolean hierarchyHasOpen, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema);
        String fn = Names.toJavaFieldName(prop.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(Names.withMethod(prop))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        ").append(className).append(" e = new ").append(className).append("();\n");
        for (PropertyModel p : allProps) {
            String pfn = Names.toJavaFieldName(p.name());
            if (Names.isCollectionType(p.edmType())) {
                sb.append("        e.").append(pfn).append(" = this.").append(pfn)
                  .append(" == null ? null : List.copyOf(this.").append(pfn).append(");\n");
            } else {
                sb.append("        e.").append(pfn).append(" = this.").append(pfn).append(";\n");
            }
        }
        for (NavigationPropertyModel nav : allNavs) {
            String nfn = Names.toJavaFieldName(nav.name());
            if (Names.isCollectionType(nav.type())) {
                sb.append("        e.").append(nfn).append(" = this.").append(nfn)
                  .append(" == null ? null : List.copyOf(this.").append(nfn).append(");\n");
            } else {
                sb.append("        e.").append(nfn).append(" = this.").append(nfn).append(";\n");
            }
        }
        if (hierarchyHasOpen) {
            sb.append("        e.unmappedFields = unmappedFields == null ? null : new java.util.HashMap<>(unmappedFields);\n");
        }
        sb.append("        e.").append(fn).append(" = value;\n");
        sb.append("        return e;\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private void generateBuilder(StringBuilder sb, List<PropertyModel> allProps, List<NavigationPropertyModel> navs, String className, boolean mutableUnmappedFields, SchemaModel schema) {
        sb.append("    public static Builder builder() {\n");
        sb.append("        return new Builder();\n");
        sb.append("    }\n\n");

        sb.append("    public static final class Builder {\n");
        for (PropertyModel prop : allProps) {
            sb.append("        private ").append(resolvePropertyJavaType(prop, schema)).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        for (NavigationPropertyModel nav : navs) {
            sb.append("        private ").append(navJavaType(nav, schema)).append(" ")
              .append(Names.toJavaFieldName(nav.name())).append(";\n");
        }
        if (mutableUnmappedFields) {
            sb.append("        private java.util.Map<String, Object> unmappedFields = new java.util.HashMap<>();\n");
        }
        sb.append("\n");

        for (PropertyModel prop : allProps) {
            String javaType = resolvePropertyJavaType(prop, schema);
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        for (NavigationPropertyModel nav : navs) {
            String javaType = navJavaType(nav, schema);
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        sb.append("        public ").append(className).append(" build() {\n");
        sb.append("            ").append(className).append(" e = new ").append(className).append("();\n");
        for (PropertyModel prop : allProps) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("            e.").append(fn).append(" = ").append(fn).append(";\n");
        }
        for (NavigationPropertyModel nav : navs) {
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("            e.").append(fn).append(" = ").append(fn).append(";\n");
        }
        if (mutableUnmappedFields) {
            sb.append("            e.unmappedFields = unmappedFields;\n");
        }
        sb.append("            return e;\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    private void ensureSchemaCache(SchemaModel schema) {
        if (complexTypeMap != null) return;
        complexTypeMap = new HashMap<>();
        Map<String, ComplexTypeModel> crossSchemaMap = new HashMap<>();
        for (SchemaModel s : effectiveSchemas) {
            for (ComplexTypeModel ct : s.complexTypes()) {
                String qn = s.namespace() + "." + ct.name();
                crossSchemaMap.put(qn, ct);
                if (s.namespace().equals(schema.namespace())) {
                    complexTypeMap.put(Names.complexTypeClassName(ct.name()), ct);
                }
            }
        }
        complexTypeByQualifiedName = crossSchemaMap;
        java.util.Map<String, String> ctNs = new java.util.HashMap<>();
        for (SchemaModel s : effectiveSchemas) {
            for (ComplexTypeModel ct : s.complexTypes()) {
                ctNs.put(Names.complexTypeClassName(ct.name()), s.namespace());
            }
        }
        complexTypeNamespace = ctNs;
        schemaOpenRootNames = new java.util.HashMap<>();
    }

    // True if this type or any ancestor declares OpenType="true" (propagates to subtypes).
    private boolean openTypeResolved(ComplexTypeModel complexType) {
        if (complexType.openType()) {
            return true;
        }
        ComplexTypeModel base = findBase(complexType);
        return base != null && openTypeResolved(base);
    }

    private ComplexTypeModel rootOf(ComplexTypeModel complexType) {
        ComplexTypeModel base = findBase(complexType);
        return base == null ? complexType : rootOf(base);
    }

    // True if any type in the hierarchy rooted here is open (root must hold a mutable map).
    private Set<String> openRootNamesForSchema(String namespace) {
        return schemaOpenRootNames.computeIfAbsent(namespace, ns -> {
            Set<String> roots = new HashSet<>();
            for (SchemaModel s : effectiveSchemas) {
                for (ComplexTypeModel ct : s.complexTypes()) {
                    if (openTypeResolved(ct)) {
                        ComplexTypeModel root = rootOf(ct);
                        String rootNs = complexTypeNamespace.get(Names.complexTypeClassName(root.name()));
                        if (rootNs != null && rootNs.equals(ns)) {
                            roots.add(Names.complexTypeClassName(root.name()));
                        }
                    }
                }
            }
            return roots;
        });
    }

    private boolean subtreeHasOpen(ComplexTypeModel root) {
        String ns = complexTypeNamespace.get(Names.complexTypeClassName(root.name()));
        if (ns == null) return false;
        return openRootNamesForSchema(ns).contains(Names.complexTypeClassName(root.name()));
    }

    private ComplexTypeModel findBase(ComplexTypeModel complexType) {
        String bt = complexType.baseType();
        if (bt == null || bt.isBlank()) {
            return null;
        }
        // Prefer qualified-name lookup (cross-schema)
        ComplexTypeModel base = complexTypeByQualifiedName.get(bt);
        if (base != null) return base;
        // Fallback: same-schema by simple name
        return complexTypeMap.get(Names.complexTypeClassName(Names.simpleNameFromFullName(bt)));
    }

    private String generateNavWithMethod(NavigationPropertyModel nav, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, boolean hierarchyHasOpen, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(navWithMethod(nav))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        ").append(className).append(" e = new ").append(className).append("();\n");
        for (PropertyModel p : allProps) {
            String pfn = Names.toJavaFieldName(p.name());
            if (Names.isCollectionType(p.edmType())) {
                sb.append("        e.").append(pfn).append(" = this.").append(pfn)
                  .append(" == null ? null : List.copyOf(this.").append(pfn).append(");\n");
            } else {
                sb.append("        e.").append(pfn).append(" = this.").append(pfn).append(";\n");
            }
        }
        for (NavigationPropertyModel n : allNavs) {
            String nfn = Names.toJavaFieldName(n.name());
            if (n.name().equals(nav.name())) {
                sb.append("        e.").append(nfn).append(" = value;\n");
            } else if (Names.isCollectionType(n.type())) {
                sb.append("        e.").append(nfn).append(" = this.").append(nfn)
                  .append(" == null ? null : List.copyOf(this.").append(nfn).append(");\n");
            } else {
                sb.append("        e.").append(nfn).append(" = this.").append(nfn).append(";\n");
            }
        }
        if (hierarchyHasOpen) {
            sb.append("        e.unmappedFields = unmappedFields == null ? null : new java.util.HashMap<>(unmappedFields);\n");
        }
        sb.append("        return e;\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private List<NavigationPropertyModel> inheritedNavProperties(ComplexTypeModel complexType) {
        ComplexTypeModel base = findBase(complexType);
        if (base == null) {
            return List.of();
        }
        List<NavigationPropertyModel> result = new ArrayList<>(inheritedNavProperties(base));
        Set<String> seen = new HashSet<>();
        for (NavigationPropertyModel n : result) {
            seen.add(n.name());
        }
        for (NavigationPropertyModel n : base.navigationProperties()) {
            if (seen.add(n.name())) {
                result.add(n);
            }
        }
        return result;
    }

    private List<PropertyModel> inheritedProperties(ComplexTypeModel complexType) {
        ComplexTypeModel base = findBase(complexType);
        if (base == null) {
            return List.of();
        }
        List<PropertyModel> result = new ArrayList<>(inheritedProperties(base));
        Set<String> seen = new HashSet<>();
        for (PropertyModel p : result) {
            seen.add(p.name());
        }
        for (PropertyModel p : base.properties()) {
            if (seen.add(p.name())) {
                result.add(p);
            }
        }
        return result;
    }
}
