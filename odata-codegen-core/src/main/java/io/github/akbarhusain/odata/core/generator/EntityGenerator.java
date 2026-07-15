package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel;
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

public class EntityGenerator extends AbstractTypeGenerator {

    private Map<String, EntityTypeModel> entityTypeMap;
    private Map<String, EntityTypeModel> entityTypeByQualifiedName;
    // Keyed by entity class name (String, not EntityTypeModel record) to avoid
    // expensive record hashCode() that iterates all properties.
    private java.util.Map<String, String> entityNamespace;
    private java.util.Map<String, Set<String>> schemaExtendedBases;
    private java.util.Map<String, Set<String>> schemaOpenRootNames;

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null, List.of());
    }

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage) {
        this(basePackage, schemaPackages, defaultBasePackage, List.of());
    }

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage, List<SchemaModel> allSchemas) {
        this(basePackage, schemaPackages, defaultBasePackage, allSchemas, false);
    }

    public EntityGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage, List<SchemaModel> allSchemas, boolean generateWithMethods) {
        super(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
    }

    public EntityGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public String generate(EntityTypeModel entityType, SchemaModel schema) {
        initEffectiveSchemas(schema);
        ensureSchemaCache(schema);
        String pkg = basePackage + Names.packageNameSuffixEntity();
        String className = Names.entityClassName(entityType.name());
        EntityTypeModel base = findBase(entityType);
        String baseSimpleName = base != null ? Names.entityClassName(base.name()) : null;

        boolean isBase = extendedBasesForSchema(schema).contains(className);

        List<PropertyModel> ownProps = entityType.properties();
        List<PropertyModel> inheritedProps = inheritedProperties(entityType);
        List<PropertyModel> allProps = new ArrayList<>(inheritedProps);
        allProps.addAll(ownProps);

        List<NavigationPropertyModel> inheritedNavs = inheritedNavProperties(entityType);
        List<NavigationPropertyModel> allNavs = new ArrayList<>(inheritedNavs);
        allNavs.addAll(entityType.navigationProperties());
        List<NavigationPropertyModel> ownNavs = entityType.navigationProperties();

        List<KeyModel> keys = resolvedKeys(entityType);

        // OpenType dynamic-property support: capture undeclared JSON fields into unmappedFields.
        boolean openType = openTypeResolved(entityType);
        boolean firstOpen = openType && (base == null || !openTypeResolved(base));
        boolean rootMutableMap = base == null && subtreeHasOpen(entityType);

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
                Names.TypeKind kind = Names.resolveTypeKind(elementType, effectiveSchemas);
                String suffix = Names.resolvedSuffix(elementType, effectiveSchemas);
                String navTargetClass = Names.resolvedClassName(elementType, effectiveSchemas);
                imports.add(basePackageForType(elementType, schema) + suffix + "." + navTargetClass);
            }
        }

        for (PropertyModel prop : allProps) {
            addPropertyImports(prop, imports, schema);
        }
        if (base != null) {
            imports.add(basePackageForType(entityType.baseType(), schema) + Names.packageNameSuffixEntity() + "." + baseSimpleName);
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

        // Typed filterable for collection lambda operators (any/all)
        sb.append(generateFilterableClass(allProps, allNavs, className, schema));

        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            sb.append("    protected ").append(javaType).append(" ")
              .append(Names.toJavaFieldName(prop.name())).append(";\n");
        }

        // Navigation-property fields hold expanded ($expand) data deserialized from JSON.
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append("    protected ").append(navJavaType(nav, schema)).append(" ")
              .append(Names.toJavaFieldName(nav.name())).append(";\n");
        }
        if (base == null) {
            sb.append("    protected String etag;\n");
            sb.append("    protected ContextPath contextPath;\n");
            sb.append("    protected java.util.Map<String, Object> unmappedFields;\n");
            sb.append("    protected Set<String> changedFields;\n");
        }
        sb.append("\n");

        // No-args constructor for Jackson, Builder, and with*() copy-on-write
        sb.append("    ").append(entityType.abstractType() ? "protected" : "public").append(" ").append(className).append("() {\n");
        if (base != null) {
            sb.append("        super();\n");
        } else {
            sb.append("        this.unmappedFields = ").append(rootMutableMap ? "new java.util.HashMap<>()" : "java.util.Map.of()").append(";\n");
            sb.append("        this.changedFields = new java.util.HashSet<>();\n");
        }
        sb.append("    }\n\n");

        // Setters annotated with @JsonProperty for Jackson deserialization (also used by Builder/with*)
        if (!entityType.abstractType()) {
            for (PropertyModel prop : ownProps) {
                String javaType = resolvePropertyJavaType(prop, schema, true);
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

        // ETag setter (root class only)
        if (base == null) {
            sb.append("    @com.fasterxml.jackson.annotation.JsonProperty(\"@odata.etag\")\n");
            sb.append("    public void setEtag(String etag) {\n");
            sb.append("        this.etag = etag;\n");
            sb.append("    }\n\n");
        }

        // Getters
        for (PropertyModel prop : ownProps) {
            sb.append(generateGetter(prop, schema));
        }

        // Navigation property getters — materialized expanded ($expand) data
        for (NavigationPropertyModel nav : ownNavs) {
            sb.append(generateNavGetter(nav, schema));
        }

        // Builder (only for concrete top-level entities; subtypes use with* methods)
        if (base == null && !entityType.abstractType()) {
            sb.append(generateBuilder(allProps, ownNavs, className, schema, keys, rootMutableMap));
        }

        // with*() methods — skipped for abstract types and when generateWithMethods is false
        if (!entityType.abstractType() && generateWithMethods) {
            for (PropertyModel prop : allProps) {
                sb.append(generateWithMethod(prop, allProps, allNavs, className, schema));
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
        sb.append("        return Collections.unmodifiableMap(unmappedFields);\n");
        sb.append("    }\n\n");

        // OpenType: @JsonAnySetter captures undeclared JSON fields (dynamic properties) into unmappedFields.
        // Generated only at the topmost open type in the chain to avoid duplicate any-setters.
        // Setters annotated with @JsonProperty handle known properties; @JsonAnySetter handles unknown ones.
        if (firstOpen) {
            sb.append("    @com.fasterxml.jackson.annotation.JsonAnySetter\n");
            sb.append("    public void setDynamicProperty(String name, Object value) {\n");
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

    private void ensureSchemaCache(SchemaModel schema) {
        if (entityTypeMap != null) return;
        entityTypeMap = new HashMap<>();
        Map<String, EntityTypeModel> crossSchemaMap = new HashMap<>();
        java.util.Map<String, String> nsMap = new java.util.HashMap<>();
        for (SchemaModel s : effectiveSchemas) {
            for (EntityTypeModel et : s.entityTypes()) {
                String qn = s.namespace() + "." + et.name();
                crossSchemaMap.put(qn, et);
                nsMap.put(Names.entityClassName(et.name()), s.namespace());
                if (s.namespace().equals(schema.namespace())) {
                    entityTypeMap.put(Names.entityClassName(et.name()), et);
                }
            }
        }
        entityTypeByQualifiedName = crossSchemaMap;
        entityNamespace = nsMap;
        schemaExtendedBases = new java.util.HashMap<>();
        schemaOpenRootNames = new java.util.HashMap<>();
    }

    private Set<String> extendedBasesForSchema(SchemaModel schema) {
        return schemaExtendedBases.computeIfAbsent(schema.namespace(), ns -> {
            Set<String> bases = new HashSet<>();
            for (SchemaModel s : effectiveSchemas) {
                for (EntityTypeModel et : s.entityTypes()) {
                    String bt = et.baseType();
                    if (bt != null && !bt.isBlank()) {
                        String baseNs = Names.namespaceFromFullName(bt);
                        if (baseNs.isEmpty()) baseNs = s.namespace();
                        if (baseNs.equals(ns)) {
                            bases.add(Names.entityClassName(Names.simpleNameFromFullName(bt)));
                        }
                    }
                }
            }
            return bases;
        });
    }

    private Set<String> openRootNamesForSchema(String namespace) {
        return schemaOpenRootNames.computeIfAbsent(namespace, ns -> {
            Set<String> roots = new HashSet<>();
            for (SchemaModel s : effectiveSchemas) {
                for (EntityTypeModel et : s.entityTypes()) {
                    if (openTypeResolved(et)) {
                        EntityTypeModel root = rootOf(et);
                        String rootNs = entityNamespace.get(Names.entityClassName(root.name()));
                        if (rootNs != null && rootNs.equals(ns)) {
                            roots.add(Names.entityClassName(root.name()));
                        }
                    }
                }
            }
            return roots;
        });
    }

    // True if this type or any ancestor declares OpenType="true" (OpenType propagates to subtypes).
    private boolean openTypeResolved(EntityTypeModel entityType) {
        if (entityType.openType()) {
            return true;
        }
        EntityTypeModel base = findBase(entityType);
        return base != null && openTypeResolved(base);
    }

    private EntityTypeModel rootOf(EntityTypeModel entityType) {
        EntityTypeModel base = findBase(entityType);
        return base == null ? entityType : rootOf(base);
    }

    // True if any type in the hierarchy rooted at this type is open (so the root must hold a
    // mutable unmappedFields map that @JsonAnySetter can populate for the open subtype).
    private boolean subtreeHasOpen(EntityTypeModel root) {
        return openRootNamesForSchema(entityNamespace.getOrDefault(Names.entityClassName(root.name()), "")).contains(Names.entityClassName(root.name()));
    }

    private EntityTypeModel findBase(EntityTypeModel entityType) {
        String bt = entityType.baseType();
        if (bt == null || bt.isBlank()) {
            return null;
        }
        // Prefer qualified-name lookup (cross-schema)
        EntityTypeModel base = entityTypeByQualifiedName.get(bt);
        if (base != null) return base;
        // Fallback: same-schema by simple name
        return entityTypeMap.get(Names.entityClassName(Names.simpleNameFromFullName(bt)));
    }

    private List<PropertyModel> inheritedProperties(EntityTypeModel entityType) {
        EntityTypeModel base = findBase(entityType);
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

    private List<NavigationPropertyModel> inheritedNavProperties(EntityTypeModel entityType) {
        EntityTypeModel base = findBase(entityType);
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

    private List<KeyModel> resolvedKeys(EntityTypeModel entityType) {
        if (!entityType.keys().isEmpty()) {
            return entityType.keys();
        }
        EntityTypeModel base = findBase(entityType);
        if (base == null) {
            return List.of();
        }
        return resolvedKeys(base);
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
            Names.TypeKind kind = Names.resolveTypeKind(elementType, effectiveSchemas);
            if (kind == Names.TypeKind.ENTITY || kind == Names.TypeKind.COMPLEX) {
                return "    public static final CollectionProperty<" + className + ", " + elementClassName
                        + ", " + elementClassName + ".Filterable> " + constantName
                        + " = new CollectionProperty<>(\"" + prop.name() + "\", " + className + ".class, "
                        + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
            } else {
                return "    public static final CollectionProperty<" + className + ", " + elementClassName
                        + ", CollectionProperty.FilterableElement<" + elementClassName + ">> " + constantName
                        + " = new CollectionProperty<>(\"" + prop.name() + "\", " + className + ".class, "
                        + elementClassName + ".class, CollectionProperty.FilterableElement::new);\n";
            }
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
        String elementClassName = Names.resolvedClassName(unwrapped, effectiveSchemas);
        String constantName = Names.toConstantName(nav.name());

        if (isCollection) {
            return "    public static final CollectionProperty<" + className + ", "
                    + elementClassName + ", " + elementClassName + ".Filterable> " + constantName
                    + " = new CollectionProperty<>(\"" + nav.name() + "\", " + className + ".class, "
                    + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
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
        StringBuilder sb = new StringBuilder();

        if (Names.isCollectionType(prop.edmType())) {
            sb.append("    public ").append(javaType).append(" ").append(Names.getterMethod(prop)).append("() {\n");
            sb.append("        return ").append(fn).append(" == null ? List.of() : Collections.unmodifiableList(").append(fn).append(");\n");
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

    private String generateNavWithMethod(NavigationPropertyModel nav, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(navWithMethod(nav))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        ").append(className).append(" e = new ").append(className).append("();\n");
        sb.append("        e.contextPath = contextPath;\n");
        sb.append("        e.etag = etag;\n");
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
        sb.append("        e.unmappedFields = unmappedFields == null ? null : new java.util.HashMap<>(unmappedFields);\n");
        sb.append("        e.changedFields = EntityUtil.mergeChanged(changedFields, \"").append(nav.name()).append("\");\n");
        sb.append("        return e;\n");
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
        sb.append("            ").append(className).append(" e = new ").append(className).append("();\n");
        sb.append("            e.contextPath = contextPath;\n");
        sb.append("            e.etag = etag;\n");
        for (PropertyModel prop : props) {
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("            e.").append(fn).append(" = ").append(fn).append(";\n");
        }
        for (NavigationPropertyModel nav : navs) {
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("            e.").append(fn).append(" = ").append(fn).append(";\n");
        }
        sb.append("            e.unmappedFields = unmappedFields;\n");
        sb.append("            e.changedFields = new java.util.HashSet<>(changed);\n");
        sb.append("            return e;\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateWithMethod(PropertyModel prop, List<PropertyModel> allProps, List<NavigationPropertyModel> allNavs, String className, SchemaModel schema) {
        String javaType = resolvePropertyJavaType(prop, schema, true);
        String fn = Names.toJavaFieldName(prop.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append(" ").append(Names.withMethod(prop))
          .append("(").append(javaType).append(" value) {\n");
        sb.append("        ").append(className).append(" e = new ").append(className).append("();\n");
        sb.append("        e.contextPath = contextPath;\n");
        sb.append("        e.etag = etag;\n");
        for (PropertyModel p : allProps) {
            String pfn = Names.toJavaFieldName(p.name());
            if (p.name().equals(prop.name())) {
                sb.append("        e.").append(pfn).append(" = value;\n");
            } else if (Names.isCollectionType(p.edmType())) {
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
        sb.append("        e.unmappedFields = unmappedFields == null ? null : new java.util.HashMap<>(unmappedFields);\n");
        sb.append("        e.changedFields = EntityUtil.mergeChanged(changedFields, \"").append(prop.name()).append("\");\n");
        sb.append("        return e;\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private boolean isBuiltinType(String name) {
        return switch (name) {
            case "String", "Boolean", "Integer", "Long", "Float", "Double", "Byte", "Short" -> true;
            default -> false;
        };
    }
}
