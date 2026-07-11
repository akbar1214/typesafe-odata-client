package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class EntityGenerator {

    private final String basePackage;

    public EntityGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(EntityTypeModel entityType, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixEntity();
        String className = Names.entityClassName(entityType.name());
        EntityTypeModel base = findBase(entityType, schema);
        String baseSimpleName = base != null ? Names.entityClassName(base.name()) : null;

        Set<String> extendedBases = new HashSet<>();
        for (EntityTypeModel et : schema.entityTypes()) {
            if (et.baseType() != null && !et.baseType().isBlank()) {
                extendedBases.add(Names.entityClassName(Names.simpleNameFromFullName(et.baseType())));
            }
        }
        boolean isBase = extendedBases.contains(className);

        List<PropertyModel> ownProps = entityType.properties();
        List<PropertyModel> inheritedProps = inheritedProperties(entityType, schema);
        List<PropertyModel> allProps = new ArrayList<>(inheritedProps);
        allProps.addAll(ownProps);

        List<NavigationPropertyModel> allNavs = new ArrayList<>(inheritedNavProperties(entityType, schema));
        allNavs.addAll(entityType.navigationProperties());

        List<KeyModel> keys = resolvedKeys(entityType, schema);

        Set<String> ownPropNames = new HashSet<>();
        for (PropertyModel p : ownProps) {
            ownPropNames.add(p.name());
        }


        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Optional");
        imports.add("java.util.List");
        imports.add("java.util.Collections");
        imports.add("java.util.Objects");
        imports.add("java.util.Set");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ODataEntityType");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.entity.EntityUtil");
        imports.add("io.github.akbarhusain.odata.runtime.query.*");

        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            String elementClassName = Names.simpleNameFromFullName(Names.unwrapCollectionType(nav.type()));
            if (!isBuiltinType(elementClassName)) {
                imports.add(basePackage + Names.packageNameSuffixEntity() + "." + elementClassName);
            }
        }

        for (PropertyModel prop : entityType.properties()) {
            addPropertyImports(prop, imports, schema);
        }
        if (base != null) {
            imports.add(pkg + "." + baseSimpleName);
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Class declaration
        if (entityType.abstractType()) {
            sb.append("public abstract class ").append(className);
        } else if (base != null || isBase) {
            sb.append("public class ").append(className);
        } else {
            sb.append("public final class ").append(className);
        }
        if (base != null) {
            sb.append(" extends ").append(baseSimpleName);
        }
        sb.append(" implements ODataEntityType {\n\n");

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

        // Fields (protected so subclasses can copy inherited state via with*/constructors)
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append("    protected final ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }
        if (base == null) {
            sb.append("    protected final ContextPath contextPath;\n");
            sb.append("    protected final String etag;\n");
            sb.append("    protected final java.util.Map<String, Object> unmappedFields;\n");
            sb.append("    protected final Set<String> changedFields;\n");
        }
        sb.append("\n");

        // Constructor with Jackson annotations for deserialization
        sb.append("    @com.fasterxml.jackson.annotation.JsonCreator\n");
        sb.append("    public ").append(className).append("(\n");
        sb.append("            @com.fasterxml.jackson.annotation.JsonProperty(\"@odata.etag\") String etag");
        for (PropertyModel prop : allProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append(",\n            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(prop.name()).append("\") ")
              .append(javaType).append(" ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(") {\n");
        if (base != null) {
            sb.append("        super(etag");
            for (PropertyModel prop : inheritedProps) {
                sb.append(", ").append(Names.toJavaFieldName(prop.name()));
            }
            sb.append(");\n");
        } else {
            sb.append("        this.contextPath = null;\n");
            sb.append("        this.etag = etag;\n");
        }
        for (PropertyModel prop : ownProps) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fieldInit(prop)).append(";\n");
        }
        if (base == null) {
            sb.append("        this.unmappedFields = java.util.Map.of();\n");
            sb.append("        this.changedFields = Set.of();\n");
        }
        sb.append("    }\n\n");

        // Internal constructor for builder/with methods
        sb.append("    protected ").append(className).append("(ContextPath contextPath, String etag");
        for (PropertyModel prop : allProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append(", ").append(javaType).append(" ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(", java.util.Map<String, Object> unmappedFields, Set<String> changedFields) {\n");
        if (base != null) {
            sb.append("        super(contextPath, etag");
            for (PropertyModel prop : inheritedProps) {
                sb.append(", ").append(Names.toJavaFieldName(prop.name()));
            }
            sb.append(", unmappedFields, changedFields);\n");
        } else {
            sb.append("        this.contextPath = contextPath;\n");
            sb.append("        this.etag = etag;\n");
        }
        for (PropertyModel prop : ownProps) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        this.").append(fn).append(" = ").append(fieldInit(prop)).append(";\n");
        }
        if (base == null) {
            sb.append("        this.unmappedFields = unmappedFields != null ? unmappedFields : java.util.Map.of();\n");
            sb.append("        this.changedFields = changedFields != null ? changedFields : Set.of();\n");
        }
        sb.append("    }\n\n");

        // Getters
        for (PropertyModel prop : ownProps) {
            sb.append(generateGetter(prop, schema));
        }

        // Navigation property accessor methods
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            sb.append(generateNavAccessor(nav, schema));
        }

        // Builder (only for concrete top-level entities; subtypes reuse base builder or with* methods)
        if (base == null && !entityType.abstractType()) {
            sb.append(generateBuilder(allProps, className, schema, keys));
        }

        // with*() methods
        for (PropertyModel prop : allProps) {
            sb.append(generateWithMethod(prop, allProps, ownPropNames, className, schema));
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
        if (!keys.isEmpty()) {
            List<String> refs = keys.get(0).propertyRefs();
            if (refs.size() == 1) {
                sb.append("        return ").append(getterCall(refs.get(0), allProps)).append(";\n");
            } else {
                sb.append("        return java.util.Map.of(\n");
                for (int i = 0; i < refs.size(); i++) {
                    sb.append("            \"").append(refs.get(i)).append("\", ").append(getterCall(refs.get(i), allProps));
                    if (i < refs.size() - 1) {
                        sb.append(",\n");
                    } else {
                        sb.append("\n");
                    }
                }
                sb.append("        );\n");
            }
        } else {
            sb.append("        return null;\n");
        }
        sb.append("    }\n\n");

        // toString
        sb.append("    @Override\n    public String toString() {\n");
        sb.append("        return \"").append(className).append("{\" +\n");
        boolean first = true;
        for (PropertyModel prop : allProps) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("            ").append(first ? "\"" : "\", ").append(prop.name()).append("=\" + ").append(fn).append(" +\n");
            first = false;
        }
        sb.append("            \"}\";\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private EntityTypeModel findBase(EntityTypeModel entityType, SchemaModel schema) {
        String bt = entityType.baseType();
        if (bt == null || bt.isBlank()) {
            return null;
        }
        String baseSimple = Names.entityClassName(Names.simpleNameFromFullName(bt));
        for (EntityTypeModel et : schema.entityTypes()) {
            if (Names.entityClassName(et.name()).equals(baseSimple)) {
                return et;
            }
        }
        return null;
    }

    private List<PropertyModel> inheritedProperties(EntityTypeModel entityType, SchemaModel schema) {
        EntityTypeModel base = findBase(entityType, schema);
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

    private List<NavigationPropertyModel> inheritedNavProperties(EntityTypeModel entityType, SchemaModel schema) {
        EntityTypeModel base = findBase(entityType, schema);
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

    private List<KeyModel> resolvedKeys(EntityTypeModel entityType, SchemaModel schema) {
        if (!entityType.keys().isEmpty()) {
            return entityType.keys();
        }
        EntityTypeModel base = findBase(entityType, schema);
        if (base == null) {
            return List.of();
        }
        return resolvedKeys(base, schema);
    }

    private String getterCall(String propName, List<PropertyModel> props) {
        for (PropertyModel p : props) {
            if (p.name().equals(propName)) {
                return "this." + Names.toJavaFieldName(p.name());
            }
        }
        return "this." + Names.toJavaFieldName(propName);
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

    private String fieldInit(PropertyModel prop) {
        String fn = Names.toJavaFieldName(prop.name());
        if (Names.isCollectionType(prop.edmType())) {
            return fn + " == null ? List.of() : List.copyOf(" + fn + ")";
        }
        return fn;
    }

    private String generateGetter(PropertyModel prop, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema, prop.nullable());
        String fn = Names.toJavaFieldName(prop.name());
        StringBuilder sb = new StringBuilder();

        if (Names.isCollectionType(prop.edmType())) {
            sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
            sb.append("        return Collections.unmodifiableList(").append(fn).append(");\n");
            sb.append("    }\n\n");
            return sb.toString();
        }

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

    private String generateBuilder(List<PropertyModel> props, String className, SchemaModel schema, List<KeyModel> keys) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public static Builder builder() {\n        return new Builder();\n    }\n\n");

        sb.append("    public static final class Builder {\n");
        sb.append("        private final java.util.Set<String> changed = new java.util.HashSet<>();\n");
        sb.append("        private ContextPath contextPath;\n");
        sb.append("        private String etag;\n");
        for (PropertyModel prop : props) {
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

        for (PropertyModel prop : props) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            sb.append("            changed.add(\"").append(prop.name()).append("\");\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        sb.append("        public ").append(className).append(" build() {\n");
        for (var key : keys) {
            for (String keyProp : key.propertyRefs()) {
                sb.append("            Objects.requireNonNull(").append(Names.toJavaFieldName(keyProp))
                  .append(", \"").append(keyProp).append(" is required (key)\");\n");
            }
        }
        sb.append("            return new ").append(className).append("(contextPath, etag");
        for (PropertyModel prop : props) {
            sb.append(", ").append(Names.toJavaFieldName(prop.name()));
        }
        sb.append(", unmappedFields, changed);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateWithMethod(PropertyModel prop, List<PropertyModel> allProps, Set<String> ownPropNames, String className, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema, true);
        String fn = Names.toJavaFieldName(prop.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(Names.withMethod(prop))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        return new ").append(className).append("(contextPath, etag");
        for (PropertyModel p : allProps) {
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
}
