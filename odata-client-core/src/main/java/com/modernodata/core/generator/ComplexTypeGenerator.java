package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel;
import com.modernodata.core.model.CsdlModel.ComplexTypeModel;
import com.modernodata.core.model.CsdlModel.SchemaModel;

import java.util.Set;
import java.util.TreeSet;

public class ComplexTypeGenerator {

    private final String basePackage;

    public ComplexTypeGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(ComplexTypeModel complexType, String namespace) {
        String pkg = basePackage + Names.packageNameSuffixComplexType();
        String className = Names.complexTypeClassName(complexType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("java.util.Objects");
        imports.add("com.modernodata.runtime.entity.ODataType");
        imports.add("com.modernodata.runtime.entity.ContextPath");

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Skip inheritance for now — TODO: generate proper inheritance chain
        if (complexType.baseType() != null && !complexType.baseType().isEmpty()) {
            sb.append("// TODO: Complex type '").append(complexType.name())
              .append("' has base type '").append(complexType.baseType())
              .append("' — inheritance not yet supported\n");
        }

        sb.append("public class ").append(className)
          .append(" implements ODataType {\n\n");

        // Fields
        for (var prop : complexType.properties()) {
            String javaType = resolvePropertyJavaType(prop);
            sb.append("    private final ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        sb.append("\n");

        // Constructor with Jackson annotations for deserialization
        sb.append("    @com.fasterxml.jackson.annotation.JsonCreator\n");
        sb.append("    public ").append(className).append("(\n");
        for (int i = 0; i < complexType.properties().size(); i++) {
            var prop = complexType.properties().get(i);
            sb.append("            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(prop.name()).append("\") ")
              .append(resolvePropertyJavaType(prop)).append(" ").append(Names.toJavaFieldName(prop.name()));
            if (i < complexType.properties().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("    ) {\n");
        for (var prop : complexType.properties()) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
        }
        sb.append("    }\n\n");

        // Getters
        for (var prop : complexType.properties()) {
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

        // Builder
        sb.append("    public static Builder builder() {\n");
        sb.append("        return new Builder();\n");
        sb.append("    }\n\n");

        sb.append("    public static final class Builder {\n");
        for (var prop : complexType.properties()) {
            sb.append("        private ").append(resolvePropertyJavaType(prop)).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        sb.append("\n");

        for (var prop : complexType.properties()) {
            String javaType = resolvePropertyJavaType(prop);
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        sb.append("        public ").append(className).append(" build() {\n");
        sb.append("            return new ").append(className).append("(");
        for (int i = 0; i < complexType.properties().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Names.toJavaFieldName(complexType.properties().get(i).name()));
        }
        sb.append(");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // ODataType interface
        sb.append("    @Override\n");
        sb.append("    public String odataTypeName() {\n");
        sb.append("        return \"").append(namespace).append(".").append(complexType.name()).append("\";\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public java.util.Map<String, Object> getUnmappedFields() {\n");
        sb.append("        return java.util.Map.of();\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public ContextPath getContextPath() {\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        // toString
        sb.append("    @Override\n");
        sb.append("    public String toString() {\n");
        sb.append("        return \"").append(className).append("{\" +\n");
        boolean first = true;
        for (var prop : complexType.properties()) {
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

    private String resolvePropertyJavaType(CsdlModel.PropertyModel prop) {
        String edmType = prop.edmType();
        if (Names.isPrimitiveType(edmType)) {
            return Names.edmTypeToSimpleJavaType(edmType);
        }
        return Names.complexTypeClassName(Names.simpleNameFromFullName(edmType));
    }
}
