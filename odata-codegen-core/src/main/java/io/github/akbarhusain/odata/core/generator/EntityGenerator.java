package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class EntityGenerator {

    private final String basePackage;
    private final Map<String, String> schemaPackages;
    private final String defaultBasePackage;

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null);
    }

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
    }

    public EntityGenerator(String basePackage) {
        this(basePackage, Map.of());
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
        List<NavigationPropertyModel> ownNavs = entityType.navigationProperties();
        List<NavigationPropertyModel> inheritedNavs = inheritedNavProperties(entityType, schema);

        List<KeyModel> keys = resolvedKeys(entityType, schema);

        // OpenType dynamic-property support: capture undeclared JSON fields into unmappedFields.
        boolean openType = openTypeResolved(entityType, schema);
        boolean firstOpen = openType && (base == null || !openTypeResolved(base, schema));
        boolean rootMutableMap = base == null && subtreeHasOpen(entityType, schema);

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
        imports.add("io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter");
        imports.add("io.github.akbarhusain.odata.runtime.query.*");

        for (NavigationPropertyModel nav : allNavs) {
            String elementType = Names.unwrapCollectionType(nav.type());
            String edmSimpleName = Names.simpleNameFromFullName(elementType);
            if (!isBuiltinType(edmSimpleName)) {
                String navTargetClass;
                String suffix;
                if (schema.complexTypes().stream().anyMatch(c -> c.name().equals(edmSimpleName))) {
                    suffix = Names.packageNameSuffixComplexType();
                    navTargetClass = Names.complexTypeClassName(edmSimpleName);
                } else {
                    suffix = Names.packageNameSuffixEntity();
                    navTargetClass = Names.entityClassName(edmSimpleName);
                }
                imports.add(basePackageForType(elementType, schema) + suffix + "." + navTargetClass);
            }
        }

        for (PropertyModel prop : allProps) {
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

        // Navigation-property fields hold expanded ($expand) data deserialized from JSON.
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append("    protected final ").append(navJavaType(nav, schema)).append(" ")
              .append(Names.toJavaFieldName(nav.name())).append(";\n");
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
        for (NavigationPropertyModel nav : allNavs) {
            sb.append(",\n            @com.fasterxml.jackson.annotation.JsonProperty(\"").append(nav.name()).append("\") ")
              .append(navJavaType(nav, schema)).append(" ").append(Names.toJavaFieldName(nav.name()));
        }
        sb.append(") {\n");
        if (base != null) {
            sb.append("        super(etag");
            for (PropertyModel prop : inheritedProps) {
                sb.append(", ").append(Names.toJavaFieldName(prop.name()));
            }
            for (NavigationPropertyModel nav : inheritedNavs) {
                sb.append(", ").append(Names.toJavaFieldName(nav.name()));
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
        for (NavigationPropertyModel nav : ownNavs) {
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        this.").append(fn).append(" = ").append(navFieldInit(nav)).append(";\n");
        }
        if (base == null) {
            sb.append("        this.unmappedFields = ")
              .append(rootMutableMap ? "new java.util.HashMap<>()" : "java.util.Map.of()")
              .append(";\n");
            sb.append("        this.changedFields = Set.of();\n");
        }
        sb.append("    }\n\n");

        // Internal constructor for builder/with methods
        sb.append("    protected ").append(className).append("(ContextPath contextPath, String etag");
        for (PropertyModel prop : allProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append(", ").append(javaType).append(" ").append(Names.toJavaFieldName(prop.name()));
        }
        for (NavigationPropertyModel nav : allNavs) {
            sb.append(", ").append(navJavaType(nav, schema)).append(" ").append(Names.toJavaFieldName(nav.name()));
        }
        sb.append(", java.util.Map<String, Object> unmappedFields, Set<String> changedFields) {\n");
        if (base != null) {
            sb.append("        super(contextPath, etag");
            for (PropertyModel prop : inheritedProps) {
                sb.append(", ").append(Names.toJavaFieldName(prop.name()));
            }
            for (NavigationPropertyModel nav : inheritedNavs) {
                sb.append(", ").append(Names.toJavaFieldName(nav.name()));
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
        for (NavigationPropertyModel nav : ownNavs) {
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        this.").append(fn).append(" = ").append(navFieldInit(nav)).append(";\n");
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

        // Navigation property getters — materialized expanded ($expand) data
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append(generateNavGetter(nav, schema));
        }

        // Builder (only for concrete top-level entities; subtypes reuse base builder or with* methods)
        if (base == null && !entityType.abstractType()) {
            sb.append(generateBuilder(allProps, ownNavs, className, schema, keys, rootMutableMap));
        }

        // with*() methods — skipped for abstract types, which cannot be instantiated
        // (a with* would otherwise emit `new AbstractX(...)`, a compile error). Concrete
        // subtypes generate their own with* that reconstruct the subtype via super().
        if (!entityType.abstractType()) {
            for (PropertyModel prop : allProps) {
                sb.append(generateWithMethod(prop, allProps, allNavs, ownPropNames, className, schema));
            }
            for (NavigationPropertyModel nav : allNavs) {
                sb.append(generateNavWithMethod(nav, allProps, allNavs, className, schema));
            }
        }

        // Interface methods
        sb.append("    @Override\n    public String odataTypeName() {\n");
        sb.append("        return \"").append(schema.namespace()).append(".").append(entityType.name()).append("\";\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public Optional<String> getETag() {\n");
        sb.append("        return Optional.ofNullable(etag);\n");
        sb.append("    }\n\n");

        if (openType) {
            sb.append("    @com.fasterxml.jackson.annotation.JsonAnyGetter\n");
        }
        sb.append("    @Override\n    public java.util.Map<String, Object> getUnmappedFields() {\n");
        sb.append("        return ").append(openType ? "Collections.unmodifiableMap(unmappedFields)" : "unmappedFields").append(";\n");
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

    // True if this type or any ancestor declares OpenType="true" (OpenType propagates to subtypes).
    private boolean openTypeResolved(EntityTypeModel entityType, SchemaModel schema) {
        if (entityType.openType()) {
            return true;
        }
        EntityTypeModel base = findBase(entityType, schema);
        return base != null && openTypeResolved(base, schema);
    }

    private EntityTypeModel rootOf(EntityTypeModel entityType, SchemaModel schema) {
        EntityTypeModel base = findBase(entityType, schema);
        return base == null ? entityType : rootOf(base, schema);
    }

    // True if any type in the hierarchy rooted at this type is open (so the root must hold a
    // mutable unmappedFields map that @JsonAnySetter can populate for the open subtype).
    private boolean subtreeHasOpen(EntityTypeModel root, SchemaModel schema) {
        for (EntityTypeModel et : schema.entityTypes()) {
            if (rootOf(et, schema).name().equals(root.name()) && openTypeResolved(et, schema)) {
                return true;
            }
        }
        return openTypeResolved(root, schema);
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
        if (constantType == null) {
            return ""; // Binary, Stream, Geography, Geometry — not filterable
        }
        String typeParams = switch (constantType) {
            case "EnumProperty" -> "<" + className + ", " + resolveClassNameForConstant(edmType, schema) + ">";
            case "NumberProperty" -> "<" + className + ", " + getNumberJavaType(edmType) + ">";
            default -> "<" + className + ">";
        };

        return "    public static final " + constantType + typeParams + " "
                + constantName
                + " = new " + constantType + "<>(\"" + prop.name() + "\", " + className + ".class"
                + (constantType.equals("EnumProperty") ? ", " + resolveClassNameForConstant(edmType, schema) + ".class, \"" + edmType + "\"" : "")
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

    private String generateNavWithMethod(NavigationPropertyModel nav, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(navWithMethod(nav))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        return new ").append(className).append("(contextPath, etag");
        for (PropertyModel p : allProps) {
            sb.append(", ").append(Names.toJavaFieldName(p.name()));
        }
        for (NavigationPropertyModel n : allNavs) {
            sb.append(", ");
            if (n.name().equals(nav.name())) {
                sb.append("value");
            } else {
                sb.append(Names.toJavaFieldName(n.name()));
            }
        }
        sb.append(", unmappedFields, EntityUtil.mergeChanged(changedFields, \"").append(nav.name()).append("\"));\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String generateBuilder(List<PropertyModel> props, List<NavigationPropertyModel> navs, String className, SchemaModel schema, List<KeyModel> keys, boolean mutableUnmappedFields) {
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
        for (NavigationPropertyModel nav : navs) {
            sb.append("        private ").append(navJavaType(nav, schema)).append(" ").append(Names.toJavaFieldName(nav.name())).append(";\n");
        }
        sb.append("        private java.util.Map<String, Object> unmappedFields = ")
          .append(mutableUnmappedFields ? "new java.util.HashMap<>()" : "java.util.Map.of()")
          .append(";\n\n");

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

        for (NavigationPropertyModel nav : navs) {
            String javaType = navJavaType(nav, schema);
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
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
        for (NavigationPropertyModel nav : navs) {
            sb.append(", ").append(Names.toJavaFieldName(nav.name()));
        }
        sb.append(", unmappedFields, changed);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateWithMethod(PropertyModel prop, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, Set<String> ownPropNames, String className, SchemaModel schema) {
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
        for (NavigationPropertyModel nav : allNavs) {
            sb.append(", ").append(Names.toJavaFieldName(nav.name()));
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
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            String javaType = Names.edmTypeToSimpleJavaType(resolved);
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
        if (isEnumType(resolved, schema)) {
            return Names.enumClassName(Names.simpleNameFromFullName(resolved));
        }
        return Names.complexTypeClassName(Names.simpleNameFromFullName(resolved));
    }

    private String resolveClassNameForConstant(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return Names.simpleNameFromFullName(resolved);
    }

    private String getNumberJavaType(String edmType) {
        return Names.edmTypeToSimpleJavaType(edmType);
    }

    private String getPropertyConstantType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isStringType(resolved)) return "StringProperty";
        if (Names.isBooleanType(resolved)) return "BooleanProperty";
        if (Names.isDateTimeType(resolved)) return "DateTimeProperty";
        if (isEnumType(resolved, schema)) return "EnumProperty";
        if (Names.isNumericType(resolved)) return "NumberProperty";
        return null; // Binary, Stream, Geography, Geometry — not filterable, no constant
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
        return schemaPackages.getOrDefault(namespace,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(namespace));
    }

    private boolean isBuiltinType(String name) {
        return switch (name) {
            case "String", "Boolean", "Integer", "Long", "Float", "Double", "Byte", "Short" -> true;
            default -> false;
        };
    }

    private void addPropertyImports(PropertyModel prop, Set<String> imports, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            imports.add("java.util.List");
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
            imports.add(pkg + Names.packageNameSuffixEnum() + "." + Names.enumClassName(Names.simpleNameFromFullName(edmType)));
        } else {
            String pkg = basePackageForType(edmType, schema);
            imports.add(pkg + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(Names.simpleNameFromFullName(edmType)));
        }
    }
}
