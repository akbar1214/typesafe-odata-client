package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel.*;
import java.util.*;
import java.util.stream.Collectors;

public class EntityGenerator {

    private final String basePackage;

    public EntityGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(EntityTypeModel entityType, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixEntity();
        String className = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("java.util.List");
        imports.add("java.util.Objects");
        imports.add("java.util.Set");
        imports.add("com.modernodata.runtime.entity.ODataEntityType");
        imports.add("com.modernodata.runtime.entity.ContextPath");
        imports.add("com.modernodata.runtime.entity.EntityUtil");
        imports.add("com.modernodata.runtime.query.*");

        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            String elementClassName = Names.simpleNameFromFullName(Names.unwrapCollectionType(nav.type()));
            if (!isBuiltinType(elementClassName)) {
                imports.add(basePackage + Names.packageNameSuffixEntity() + "." + elementClassName);
            }
        }

        for (PropertyModel prop : entityType.properties()) {
            addPropertyImports(prop, imports, schema);
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Class declaration
        sb.append("public final class ").append(className).append(" implements ODataEntityType {\n\n");

        // Static property constants
        for (PropertyModel prop : entityType.properties()) {
            sb.append(generatePropertyConstant(prop, className, schema));
        }
        sb.append("\n");

        // Static navigation property constants
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            sb.append(generateNavPropertyConstant(nav, className, schema));
        }
        if (!entityType.navigationProperties().isEmpty()) sb.append("\n");

        // Fields
        for (PropertyModel prop : entityType.properties()) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append("    private final ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        sb.append("    private final ContextPath contextPath;\n");
        sb.append("    private final String etag;\n");
        sb.append("    private final java.util.Map<String, Object> unmappedFields;\n");
        sb.append("    private final Set<String> changedFields;\n\n");

        // Constructor with Jackson annotations for deserialization
        sb.append("    @com.fasterxml.jackson.annotation.JsonCreator\n");
        sb.append("    public ").append(className).append("(\n");
        sb.append("            @com.fasterxml.jackson.annotation.JsonProperty(\"@odata.etag\") String etag");
        for (PropertyModel prop : entityType.properties()) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append(",\n            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(prop.name()).append("\") ")
              .append(javaType).append(" ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(") {\n");
        sb.append("        this.contextPath = null;\n");
        sb.append("        this.etag = etag;\n");
        for (PropertyModel prop : entityType.properties()) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
        }
        sb.append("        this.unmappedFields = java.util.Map.of();\n");
        sb.append("        this.changedFields = Set.of();\n");
        sb.append("    }\n\n");

        // Internal constructor for builder/with methods
        sb.append("    private ").append(className).append("(ContextPath contextPath, String etag");
        for (PropertyModel prop : entityType.properties()) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append(", ").append(javaType).append(" ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(", java.util.Map<String, Object> unmappedFields, Set<String> changedFields) {\n");
        sb.append("        this.contextPath = contextPath;\n");
        sb.append("        this.etag = etag;\n");
        for (PropertyModel prop : entityType.properties()) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fn).append(";\n");
        }
        sb.append("        this.unmappedFields = unmappedFields != null ? unmappedFields : java.util.Map.of();\n");
        sb.append("        this.changedFields = changedFields != null ? changedFields : Set.of();\n");
        sb.append("    }\n\n");

        // Getters
        for (PropertyModel prop : entityType.properties()) {
            sb.append(generateGetter(prop, schema));
        }

        // Navigation property accessor methods
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            sb.append(generateNavAccessor(nav, schema));
        }

        // Builder
        sb.append(generateBuilder(entityType, className, schema));

        // with*() methods
        for (PropertyModel prop : entityType.properties()) {
            sb.append(generateWithMethod(prop, className, entityType, schema));
        }

        // Interface methods
        sb.append("    @Override\n    public String odataTypeName() {\n");
        sb.append("        return \"").append(schema.namespace()).append(".").append(entityType.name()).append("\";\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public Optional<String> getETag() {\n");
        sb.append("        return Optional.ofNullable(etag);\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public java.util.Map<String, Object> getUnmappedFields() {\n");
        sb.append("        return unmappedFields;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public ContextPath getContextPath() {\n");
        sb.append("        return contextPath;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public Set<String> getChangedFields() {\n");
        sb.append("        return changedFields;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public Object getKey() {\n");
        if (!entityType.keys().isEmpty()) {
            String keyProp = entityType.keys().get(0).propertyRefs().get(0);
            sb.append("        return ").append(Names.toJavaFieldName(keyProp)).append(";\n");
        } else {
            sb.append("        return null;\n");
        }
        sb.append("    }\n\n");

        // toString
        sb.append("    @Override\n    public String toString() {\n");
        sb.append("        return \"").append(className).append("{\" +\n");
        boolean first = true;
        for (PropertyModel prop : entityType.properties()) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("            ").append(first ? "\"" : "\", ").append(prop.name()).append("=\" + ").append(fn).append(" +\n");
            first = false;
        }
        sb.append("            \"}\";\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String generatePropertyConstant(PropertyModel prop, String className, SchemaModel schema) {
        String edmType = prop.edmType();
        String constantName = Names.toConstantName(prop.name());

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String elementClassName = resolveClassNameForConstant(elementType, schema);
            return "    public static final CollectionProperty<" + className + ", " + elementClassName
                    + "> " + constantName
                    + " = new CollectionProperty<>(\"" + prop.name() + "\", " + className + ".class, "
                    + elementClassName + ".class);\n";
        }

        String constantType = getPropertyConstantType(edmType, schema);
        String typeParams = switch (constantType) {
            case "EnumProperty" -> "<" + className + ", " + resolveClassNameForConstant(edmType, schema) + ">";
            case "NumberProperty" -> "<" + className + ", " + getNumberJavaType(edmType) + ">";
            default -> "<" + className + ">";
        };

        return "    public static final " + constantType + typeParams + " "
                + constantName
                + " = new " + constantType + "<>(\"" + prop.name() + "\", " + className + ".class"
                + (constantType.equals("EnumProperty") ? ", " + resolveClassNameForConstant(edmType, schema) + ".class" : "")
                + ");\n";
    }

    private String generateNavPropertyConstant(NavigationPropertyModel nav, String className, SchemaModel schema) {
        boolean isCollection = Names.isCollectionType(nav.type());
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.simpleNameFromFullName(unwrapped);
        String constantName = Names.toConstantName(nav.name());

        if (isCollection) {
            return "    public static final CollectionProperty<" + className + ", "
                    + elementClassName + "> " + constantName
                    + " = new CollectionProperty<>(\"" + nav.name() + "\", " + className + ".class, "
                    + elementClassName + ".class);\n";
        } else {
            return "    public static final NavProperty<" + className + ", "
                    + elementClassName + "> " + constantName
                    + " = new NavProperty<>(\"" + nav.name() + "\", " + className + ".class, "
                    + elementClassName + ".class);\n";
        }
    }

    private String generateGetter(PropertyModel prop, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema, prop.nullable());
        String fn = Names.toJavaFieldName(prop.name());

        if (Names.isCollectionType(prop.edmType())) {
            sb().append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
            sb().append("        return ").append(fn).append(";\n");
            sb().append("    }\n\n");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (prop.nullable()) {
            sb.append("    public Optional<").append(javaType).append("> ").append(Names.getterMethod(prop)).append("() {\n");
            sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
        } else {
            sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
            sb.append("        return ").append(fn).append(";\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String generateNavAccessor(NavigationPropertyModel nav, SchemaModel schema) {
        boolean isCollection = Names.isCollectionType(nav.type());
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.simpleNameFromFullName(unwrapped);
        String methodName = Names.toJavaFieldName(nav.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    /**\n");
        sb.append("     * Navigation property accessor. Requires Context to execute HTTP requests.\n");
        sb.append("     * Use container or entity request methods for context-aware navigation.\n");
        sb.append("     */\n");
        sb.append("    public Object ").append(methodName).append("() {\n");
        sb.append("        throw new UnsupportedOperationException(\n");
        sb.append("            \"Navigation properties on deserialized entities require Context. \"\n");
        sb.append("            + \"Use the container or entity request to access navigation properties.\");\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String generateBuilder(EntityTypeModel entityType, String className, SchemaModel schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public static Builder builder() {\n        return new Builder();\n    }\n\n");

        sb.append("    public static final class Builder {\n");
        sb.append("        private final java.util.Set<String> changed = new java.util.HashSet<>();\n");
        sb.append("        private ContextPath contextPath;\n");
        sb.append("        private String etag;\n");
        for (PropertyModel prop : entityType.properties()) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append("        private ").append(javaType).append(" ").append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        sb.append("        private java.util.Map<String, Object> unmappedFields = new java.util.HashMap<>();\n\n");

        sb.append("        public Builder contextPath(ContextPath contextPath) {\n");
        sb.append("            this.contextPath = contextPath;\n");
        sb.append("            return this;\n");
        sb.append("        }\n\n");

        sb.append("        public Builder etag(String etag) {\n");
        sb.append("            this.etag = etag;\n");
        sb.append("            return this;\n");
        sb.append("        }\n\n");

        for (PropertyModel prop : entityType.properties()) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            sb.append("            changed.add(\"").append(prop.name()).append("\");\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        sb.append("        public ").append(className).append(" build() {\n");
        for (var key : entityType.keys()) {
            for (String keyProp : key.propertyRefs()) {
                sb.append("            Objects.requireNonNull(").append(Names.toJavaFieldName(keyProp))
                  .append(", \"").append(keyProp).append(" is required (key)\");\n");
            }
        }
        sb.append("            return new ").append(className).append("(contextPath, etag");
        for (PropertyModel prop : entityType.properties()) {
            sb.append(", ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(", unmappedFields, changed);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateWithMethod(PropertyModel prop, String className, EntityTypeModel entityType, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema, true);
        String fn = Names.toJavaFieldName(prop.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(Names.withMethod(prop))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        return new ").append(className).append("(contextPath, etag");
        for (PropertyModel p : entityType.properties()) {
            sb.append(", ");
            if (p.name().equals(prop.name())) {
                sb.append("value");
            } else {
                sb.append(Names.toJavaFieldName(p.name()));
            }
        }
        sb.append(", unmappedFields, EntityUtil.mergeChanged(changedFields, \"").append(prop.name()).append("\"));\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema, boolean boxed) {
        String edmType = prop.edmType();

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema, true) + ">";
        }

        return resolveSingleJavaType(edmType, schema, boxed);
    }

    private String resolveSingleJavaType(String edmType, SchemaModel schema, boolean boxed) {
        if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (boxed) return javaType;
            return switch (javaType) {
                case "Boolean" -> "boolean";
                case "Integer" -> "int";
                case "Long" -> "long";
                case "Float" -> "float";
                case "Double" -> "double";
                case "Byte" -> "byte";
                case "Short" -> "short";
                default -> javaType;
            };
        }
        if (isEnumType(edmType, schema)) {
            return Names.enumClassName(Names.simpleNameFromFullName(edmType));
        }
        return Names.complexTypeClassName(Names.simpleNameFromFullName(edmType));
    }

    private String resolveClassNameForConstant(String edmType, SchemaModel schema) {
        if (Names.isPrimitiveType(edmType)) {
            return Names.edmTypeToSimpleJavaType(edmType);
        }
        return Names.simpleNameFromFullName(edmType);
    }

    private String getNumberJavaType(String edmType) {
        return Names.edmTypeToSimpleJavaType(edmType);
    }

    private String getPropertyConstantType(String edmType, SchemaModel schema) {
        if (Names.isStringType(edmType)) return "StringProperty";
        if (Names.isBooleanType(edmType)) return "BooleanProperty";
        if (Names.isDateTimeType(edmType)) return "DateTimeProperty";
        if (isEnumType(edmType, schema)) return "EnumProperty";
        if (Names.isNumericType(edmType)) return "NumberProperty";
        return "StringProperty";
    }

    private boolean isEnumType(String edmType, SchemaModel schema) {
        String simpleName = Names.simpleNameFromFullName(edmType);
        return schema.enumTypes().stream().anyMatch(e -> e.name().equals(simpleName));
    }

    private boolean isBuiltinType(String name) {
        return switch (name) {
            case "String", "Boolean", "Integer", "Long", "Float", "Double", "Byte", "Short" -> true;
            default -> false;
        };
    }

    private void addPropertyImports(PropertyModel prop, Set<String> imports, SchemaModel schema) {
        String edmType = prop.edmType();
        if (Names.isCollectionType(edmType)) {
            imports.add("java.util.List");
            String elementType = Names.unwrapCollectionType(edmType);
            if (Names.isPrimitiveType(elementType)) {
                String javaType = Names.edmTypeToSimpleJavaType(elementType);
                if (javaType.startsWith("java.")) imports.add(javaType);
            } else if (isEnumType(elementType, schema)) {
                imports.add(basePackage + Names.packageNameSuffixEnum() + "." + Names.simpleNameFromFullName(elementType));
            } else {
                imports.add(basePackage + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(Names.simpleNameFromFullName(elementType)));
            }
        } else if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (javaType.startsWith("java.")) imports.add(javaType);
        } else if (isEnumType(edmType, schema)) {
            imports.add(basePackage + Names.packageNameSuffixEnum() + "." + Names.simpleNameFromFullName(edmType));
        } else {
            imports.add(basePackage + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(Names.simpleNameFromFullName(edmType)));
        }
    }

    private StringBuilder sb() { return new StringBuilder(); }
}
