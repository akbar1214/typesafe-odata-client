package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ComplexTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class ComplexTypeGenerator {

    private final String basePackage;

    public ComplexTypeGenerator(String basePackage) {
        this.basePackage = basePackage;
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

        // OpenType dynamic-property support: capture undeclared JSON fields into unmappedFields.
        boolean openType = openTypeResolved(complexType, schema);
        boolean firstOpen = openType && (base == null || !openTypeResolved(base, schema));
        boolean rootMutableMap = base == null && subtreeHasOpen(complexType, schema);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ODataType");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        if (openType) {
            imports.add("java.util.Collections");
            imports.add("io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter");
        }
        if (base != null) {
            imports.add(pkg + "." + baseSimpleName);
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
            String javaType = resolvePropertyJavaType(prop);
            sb.append("    protected final ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
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
              .append(resolvePropertyJavaType(prop)).append(" ").append(Names.toJavaFieldName(prop.name()));
            if (i < allProps.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("    ) {\n");
        if (base != null) {
            sb.append("        super(");
            for (int i = 0; i < inheritedProps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(Names.toJavaFieldName(inheritedProps.get(i).name()));
            }
            sb.append(");\n");
        }
        for (PropertyModel prop : ownProps) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
        }
        if (rootMutableMap) {
            sb.append("        this.unmappedFields = new java.util.HashMap<>();\n");
        }
        sb.append("    }\n\n");

        // Getters (own props only; inherited getters are inherited from the parent)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop);
            String fn = Names.toJavaFieldName(prop.name());
            if (prop.nullable()) {
                sb.append("    public Optional<").append(javaType).append("> ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
            } else {
                sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
                sb.append("        return ").append(fn).append(";\n");
            }
            sb.append("    }\n\n");
        }

        // with*() copy-on-write methods — generated for all concrete complex types so that
        // subtypes can be modified immutably (the public all-args constructor is reused).
        // Inherited properties are referenced by field name (protected) to avoid wrapping
        // nullable getters' Optional<T> in the raw-typed constructor.
        if (!complexType.abstractType()) {
            for (PropertyModel prop : allProps) {
                sb.append(generateWithMethod(prop, allProps, className));
            }
        }

        // Builder — generated only for concrete top-level complex types. Subtypes reuse
        // with*() for copy-on-write (mirrors the entity design: a static builder() in a
        // subtype would clash with the inherited builder() due to different Builder types).
        if (base == null && !complexType.abstractType()) {
            generateBuilder(sb, allProps, className);
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

    private String generateWithMethod(PropertyModel prop, List<PropertyModel> allProps, String className) {
        String javaType = resolvePropertyJavaType(prop);
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
        sb.append(");\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private void generateBuilder(StringBuilder sb, List<PropertyModel> allProps, String className) {
        sb.append("    public static Builder builder() {\n");
        sb.append("        return new Builder();\n");
        sb.append("    }\n\n");

        sb.append("    public static final class Builder {\n");
        for (PropertyModel prop : allProps) {
            sb.append("        private ").append(resolvePropertyJavaType(prop)).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        sb.append("\n");

        for (PropertyModel prop : allProps) {
            String javaType = resolvePropertyJavaType(prop);
            String fn = Names.toJavaFieldName(prop.name());
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

    private String resolvePropertyJavaType(PropertyModel prop) {
        String edmType = prop.edmType();
        if (Names.isPrimitiveType(edmType)) {
            return Names.edmTypeToSimpleJavaType(edmType);
        }
        return Names.complexTypeClassName(Names.simpleNameFromFullName(edmType));
    }
}
