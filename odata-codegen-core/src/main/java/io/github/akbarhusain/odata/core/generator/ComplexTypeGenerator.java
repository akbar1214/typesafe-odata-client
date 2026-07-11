package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ComplexTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ComplexTypeGenerator {

    private final String basePackage;
    private final Map<String, String> schemaPackages;

    public ComplexTypeGenerator(String basePackage, Map<String, String> schemaPackages) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
    }

    public ComplexTypeGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public String generate(ComplexTypeModel complexType, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixComplexType();
        String className = Names.complexTypeClassName(complexType.name());
        ComplexTypeModel base = findBase(complexType, schema);
        String baseSimpleName = base != null ? Names.complexTypeClassName(Names.simpleNameFromFullName(complexType.baseType())) : null;

        List<PropertyModel> ownProps = complexType.properties();
        List<PropertyModel> inheritedProps = inheritedProperties(complexType, schema);
        List<PropertyModel> allProps = new ArrayList<>(inheritedProps);
        allProps.addAll(ownProps);

        List<NavigationPropertyModel> ownNavs = complexType.navigationProperties();
        List<NavigationPropertyModel> inheritedNavs = inheritedNavProperties(complexType, schema);
        List<NavigationPropertyModel> allNavs = new ArrayList<>(inheritedNavs);
        allNavs.addAll(ownNavs);

        // OpenType dynamic-property support: capture undeclared JSON fields into unmappedFields.
        boolean openType = openTypeResolved(complexType, schema);
        boolean firstOpen = openType && (base == null || !openTypeResolved(base, schema));
        boolean rootMutableMap = base == null && subtreeHasOpen(complexType, schema);
        // hierarchyHasOpen: true when any type in the hierarchy is open.
        // Used for internal constructor and with*() — subtypes also need to preserve unmappedFields.
        boolean hierarchyHasOpen = subtreeHasOpen(rootOf(complexType, schema), schema);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        boolean hasCollection = false;
        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ODataType");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        if (openType) {
            imports.add("io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter");
        }
        if (base != null) {
            imports.add(pkg + "." + baseSimpleName);
        }
        for (PropertyModel prop : ownProps) {
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

        // Fields (protected so subclasses can access inherited state via bare name)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema);
            sb.append("    protected final ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }

        // Navigation-property fields hold expanded ($expand) data deserialized from JSON.
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append("    protected final ").append(navJavaType(nav, schema)).append(" ")
              .append(Names.toJavaFieldName(nav.name())).append(";\n");
        }
        if (base == null && subtreeHasOpen(complexType, schema)) {
            sb.append("    protected final java.util.Map<String, Object> unmappedFields;\n");
        }
        sb.append("\n");

        // Constructor with Jackson annotations for deserialization
        sb.append("    @com.fasterxml.jackson.annotation.JsonCreator\n");
        sb.append("    public ").append(className).append("(\n");
        for (int i = 0; i < allProps.size(); i++) {
            PropertyModel prop = allProps.get(i);
            sb.append("            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(prop.name()).append("\") ")
              .append(resolvePropertyJavaType(prop, schema)).append(" ").append(Names.toJavaFieldName(prop.name()));
            if (i < allProps.size() - 1 || !allNavs.isEmpty()) sb.append(",");
            sb.append("\n");
        }
        for (int i = 0; i < allNavs.size(); i++) {
            NavigationPropertyModel nav = allNavs.get(i);
            sb.append("            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(nav.name()).append("\") ")
              .append(navJavaType(nav, schema)).append(" ").append(Names.toJavaFieldName(nav.name()));
            if (i < allNavs.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("    ) {\n");
        if (base != null) {
            sb.append("        super(");
            for (int i = 0; i < inheritedProps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(Names.toJavaFieldName(inheritedProps.get(i).name()));
            }
            for (int i = 0; i < inheritedNavs.size(); i++) {
                sb.append(", ").append(Names.toJavaFieldName(inheritedNavs.get(i).name()));
            }
            sb.append(");\n");
        }
        for (PropertyModel prop : ownProps) {
            String fn = Names.toJavaFieldName(prop.name());
            if (Names.isCollectionType(prop.edmType())) {
                sb.append("        this.").append(fn).append(" = ").append(fn)
                  .append(" == null ? List.of() : List.copyOf(").append(fn).append(");\n");
            } else {
                sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
            }
        }
        for (NavigationPropertyModel nav : ownNavs) {
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        this.").append(fn).append(" = ").append(navFieldInit(nav)).append(";\n");
        }
        if (rootMutableMap) {
            sb.append("        this.unmappedFields = new java.util.HashMap<>();\n");
        }
        sb.append("    }\n\n");

        // Internal constructor for with*() — preserves unmappedFields across copy-on-write
        if (hierarchyHasOpen) {
            sb.append("    protected ").append(className).append("(\n");
            for (int i = 0; i < allProps.size(); i++) {
                PropertyModel prop = allProps.get(i);
                sb.append("            ").append(resolvePropertyJavaType(prop, schema))
                  .append(" ").append(Names.toJavaFieldName(prop.name()));
                if (i < allProps.size() - 1 || !allNavs.isEmpty()) sb.append(",");
                sb.append("\n");
            }
            for (int i = 0; i < allNavs.size(); i++) {
                NavigationPropertyModel nav = allNavs.get(i);
                sb.append("            ").append(navJavaType(nav, schema)).append(" ")
                  .append(Names.toJavaFieldName(nav.name()));
                if (i < allNavs.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(",\n            java.util.Map<String, Object> unmappedFields) {\n");
            if (base != null) {
                sb.append("        super(");
                for (int i = 0; i < inheritedProps.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(Names.toJavaFieldName(inheritedProps.get(i).name()));
                }
                for (int i = 0; i < inheritedNavs.size(); i++) {
                    sb.append(", ").append(Names.toJavaFieldName(inheritedNavs.get(i).name()));
                }
                sb.append(", unmappedFields);\n");
            }
            for (PropertyModel prop : ownProps) {
                String fn = Names.toJavaFieldName(prop.name());
                if (Names.isCollectionType(prop.edmType())) {
                    sb.append("        this.").append(fn).append(" = ").append(fn)
                      .append(" == null ? List.of() : List.copyOf(").append(fn).append(");\n");
                } else {
                    sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
                }
            }
            for (NavigationPropertyModel nav : ownNavs) {
                String fn = Names.toJavaFieldName(nav.name());
                sb.append("        this.").append(fn).append(" = ").append(navFieldInit(nav)).append(";\n");
            }
            if (base == null) {
                sb.append("        this.unmappedFields = unmappedFields != null ? unmappedFields : java.util.Map.of();\n");
            }
            sb.append("    }\n\n");
        }

        // Getters (own props only; inherited getters are inherited from the parent)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema);
            String fn = Names.toJavaFieldName(prop.name());
            if (Names.isCollectionType(prop.edmType())) {
                sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return Collections.unmodifiableList(").append(fn).append(");\n");
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
        if (!complexType.abstractType()) {
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
        sb.append("        return new ").append(className).append("(");
        for (int i = 0; i < allProps.size(); i++) {
            PropertyModel p = allProps.get(i);
            if (i > 0) sb.append(", ");
            if (p.name().equals(prop.name())) {
                sb.append("value");
            } else {
                sb.append(Names.toJavaFieldName(p.name()));
            }
        }
        for (NavigationPropertyModel nav : allNavs) {
            sb.append(", ").append(Names.toJavaFieldName(nav.name()));
        }
        if (hierarchyHasOpen) {
            sb.append(", this.unmappedFields");
        }
        sb.append(");\n");
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
        sb.append("            return new ").append(className).append("(");
        for (int i = 0; i < allProps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Names.toJavaFieldName(allProps.get(i).name()));
        }
        for (NavigationPropertyModel nav : navs) {
            sb.append(", ").append(Names.toJavaFieldName(nav.name()));
        }
        if (mutableUnmappedFields) {
            sb.append(", unmappedFields");
        }
        sb.append(");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    // True if this type or any ancestor declares OpenType="true" (propagates to subtypes).
    private boolean openTypeResolved(ComplexTypeModel complexType, SchemaModel schema) {
        if (complexType.openType()) {
            return true;
        }
        ComplexTypeModel base = findBase(complexType, schema);
        return base != null && openTypeResolved(base, schema);
    }

    private ComplexTypeModel rootOf(ComplexTypeModel complexType, SchemaModel schema) {
        ComplexTypeModel base = findBase(complexType, schema);
        return base == null ? complexType : rootOf(base, schema);
    }

    // True if any type in the hierarchy rooted here is open (root must hold a mutable map).
    private boolean subtreeHasOpen(ComplexTypeModel root, SchemaModel schema) {
        for (ComplexTypeModel ct : schema.complexTypes()) {
            if (rootOf(ct, schema).name().equals(root.name()) && openTypeResolved(ct, schema)) {
                return true;
            }
        }
        return openTypeResolved(root, schema);
    }

    private ComplexTypeModel findBase(ComplexTypeModel complexType, SchemaModel schema) {
        String bt = complexType.baseType();
        if (bt == null || bt.isBlank()) {
            return null;
        }
        String baseSimple = Names.complexTypeClassName(Names.simpleNameFromFullName(bt));
        for (ComplexTypeModel ct : schema.complexTypes()) {
            if (Names.complexTypeClassName(ct.name()).equals(baseSimple)) {
                return ct;
            }
        }
        return null;
    }

    private String navJavaType(NavigationPropertyModel nav, SchemaModel schema) {
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.simpleNameFromFullName(unwrapped);
        if (Names.isCollectionType(nav.type())) {
            return "List<" + elementClassName + ">";
        }
        return elementClassName;
    }

    private String navGetterName(NavigationPropertyModel nav) {
        return "get" + Character.toUpperCase(nav.name().charAt(0)) + nav.name().substring(1);
    }

    private String navWithMethod(NavigationPropertyModel nav) {
        return "with" + Character.toUpperCase(nav.name().charAt(0)) + nav.name().substring(1);
    }

    private String navFieldInit(NavigationPropertyModel nav) {
        String fn = Names.toJavaFieldName(nav.name());
        if (Names.isCollectionType(nav.type())) {
            return fn + " == null ? List.of() : List.copyOf(" + fn + ")";
        }
        return fn;
    }

    private String generateNavGetter(NavigationPropertyModel nav, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        if (Names.isCollectionType(nav.type())) {
            sb.append("    public ").append(javaType).append(" ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return Collections.unmodifiableList(").append(fn).append(");\n");
            sb.append("    }\n\n");
        } else {
            sb.append("    public Optional<").append(javaType).append("> ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    private String generateNavWithMethod(NavigationPropertyModel nav, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, boolean hierarchyHasOpen, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(navWithMethod(nav))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        return new ").append(className).append("(");
        for (int i = 0; i < allProps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Names.toJavaFieldName(allProps.get(i).name()));
        }
        for (NavigationPropertyModel n : allNavs) {
            sb.append(", ");
            if (n.name().equals(nav.name())) {
                sb.append("value");
            } else {
                sb.append(Names.toJavaFieldName(n.name()));
            }
        }
        if (hierarchyHasOpen) {
            sb.append(", this.unmappedFields");
        }
        sb.append(");\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private void addNavImports(NavigationPropertyModel nav, Set<String> imports, SchemaModel schema) {
        String edmType = Names.unwrapCollectionType(nav.type());
        String simpleName = Names.simpleNameFromFullName(edmType);
        String suffix;
        if (schema.entityTypes().stream().anyMatch(e -> e.name().equals(simpleName))) {
            suffix = Names.packageNameSuffixEntity();
        } else if (schema.complexTypes().stream().anyMatch(c -> c.name().equals(simpleName))) {
            suffix = Names.packageNameSuffixComplexType();
        } else {
            suffix = Names.packageNameSuffixEntity();
        }
        imports.add(basePackageForType(edmType, schema) + suffix + "." + simpleName);
    }

    private List<NavigationPropertyModel> inheritedNavProperties(ComplexTypeModel complexType, SchemaModel schema) {
        ComplexTypeModel base = findBase(complexType, schema);
        if (base == null) {
            return List.of();
        }
        List<NavigationPropertyModel> result = new ArrayList<>(inheritedNavProperties(base, schema));
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

    private List<PropertyModel> inheritedProperties(ComplexTypeModel complexType, SchemaModel schema) {
        ComplexTypeModel base = findBase(complexType, schema);
        if (base == null) {
            return List.of();
        }
        List<PropertyModel> result = new ArrayList<>(inheritedProperties(base, schema));
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

    private String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema) + ">";
        }
        return resolveSingleJavaType(edmType, schema);
    }

    private String resolveSingleJavaType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return Names.complexTypeClassName(Names.simpleNameFromFullName(resolved));
    }

    private void addPropertyImports(PropertyModel prop, Set<String> imports, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String resolvedElement = resolveTypeDefinition(elementType, schema);
            if (Names.isPrimitiveType(resolvedElement)) {
                String javaType = Names.edmTypeToSimpleJavaType(resolvedElement);
                if (javaType.startsWith("java.")) imports.add(javaType);
            } else if (isEnumType(resolvedElement, schema)) {
                String pkg = basePackageForType(resolvedElement, schema);
                imports.add(pkg + Names.packageNameSuffixEnum() + "." + Names.simpleNameFromFullName(resolvedElement));
            } else {
                String pkg = basePackageForType(resolvedElement, schema);
                imports.add(pkg + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(Names.simpleNameFromFullName(resolvedElement)));
            }
        } else if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (javaType.startsWith("java.")) imports.add(javaType);
        } else if (isEnumType(edmType, schema)) {
            String pkg = basePackageForType(edmType, schema);
            imports.add(pkg + Names.packageNameSuffixEnum() + "." + Names.simpleNameFromFullName(edmType));
        } else {
            String pkg = basePackageForType(edmType, schema);
            imports.add(pkg + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(Names.simpleNameFromFullName(edmType)));
        }
    }

    private boolean isEnumType(String edmType, SchemaModel schema) {
        String simpleName = Names.simpleNameFromFullName(edmType);
        return schema.enumTypes().stream().anyMatch(e -> e.name().equals(simpleName));
    }

    // P0-4: Resolve TypeDefinition to its underlying Edm type (recursively)
    private String resolveTypeDefinition(String edmType, SchemaModel schema) {
        if (Names.isPrimitiveType(edmType)) return edmType;
        String simpleName = Names.simpleNameFromFullName(edmType);
        for (var td : schema.typeDefinitions()) {
            if (td.name().equals(simpleName)) {
                return resolveTypeDefinition(td.underlyingType(), schema);
            }
        }
        return edmType;
    }

    // P0-3: Look up the base package for a cross-namespace type reference
    private String basePackageForType(String edmType, SchemaModel schema) {
        String namespace = Names.namespaceFromFullName(edmType);
        if (namespace.isEmpty() || namespace.equals(schema.namespace())) {
            return basePackage;
        }
        return schemaPackages.getOrDefault(namespace, Names.toPackageName(namespace));
    }
}
