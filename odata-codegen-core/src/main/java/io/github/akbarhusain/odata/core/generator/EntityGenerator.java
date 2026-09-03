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
    private Map<String, List<EntitySubtype>> subtypesByBase;

    private record EntitySubtype(String qualifiedName, EntityTypeModel model) {}

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
        ensureSubtypeIndex();
        String pkg = basePackage + Names.packageNameSuffixEntity();
        String className = Names.entityClassName(entityType.name());
        EntityTypeModel base = findBase(entityType);

        boolean isBase = extendedBasesForSchema(schema).contains(className);

        List<PropertyModel> ownProps = entityType.properties();
        List<PropertyModel> inheritedProps = inheritedProperties(entityType);
        List<PropertyModel> allProps = new ArrayList<>(inheritedProps);
        allProps.addAll(ownProps);

        List<NavigationPropertyModel> inheritedNavs = inheritedNavProperties(entityType);
        List<NavigationPropertyModel> allNavs = new ArrayList<>(inheritedNavs);
        allNavs.addAll(entityType.navigationProperties());
        List<NavigationPropertyModel> ownNavs = entityType.navigationProperties();

        allocateConstantNames(allProps, allNavs);
        checkMemberNameCollisions(className, allProps, allNavs);
        checkKeyPropertyRefs(entityType, className, allProps);

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

        // Reference resolution FIRST: two schemas may contribute the same simple name
        // to this file (nav targets, property types, cast subtypes, the BASE type of
        // the extends clause) — contested names are referenced fully-qualified and
        // never imported. Nav targets resolve through the typedef chain so candidates
        // key on the class actually emitted (navJavaType/refFor), never the typedef name.
        String baseQualifiedName = base == null ? null
                : baseQualifiedNameOf(base, entityType.baseType(), schema);
        List<String> refCandidates = new ArrayList<>();
        for (NavigationPropertyModel nav : allNavs) {
            if (isBuiltinType(Names.simpleNameFromFullName(Names.unwrapCollectionType(nav.type())))) {
                continue;
            }
            String fqn = navTargetFqn(nav, schema);
            if (fqn != null) {
                refCandidates.add(fqn);
            }
        }
        for (PropertyModel prop : allProps) {
            collectPropertyTypeFqns(prop, schema, refCandidates);
        }
        if (baseQualifiedName != null) {
            refCandidates.add(typeFqnOf(baseQualifiedName, schema));
        }
        SubtypeRefs subtypeRefs = subtypeReferences(entityType, className, pkg, schema);
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            for (EntitySubtype subtype : subtypesFor(nav, schema)) {
                refCandidates.add(basePackageForType(subtype.qualifiedName(), schema)
                        + Names.packageNameSuffixEntity() + "."
                        + Names.entityClassName(subtype.model().name()));
            }
        }
        this.typeRefs = TypeRefs.resolve(refCandidates);

        for (NavigationPropertyModel nav : allNavs) {
            if (isBuiltinType(Names.simpleNameFromFullName(Names.unwrapCollectionType(nav.type())))) {
                continue;
            }
            // Skip self-referencing navs: importing the class being generated is a compile error
            String fqn = navTargetFqn(nav, schema);
            if (fqn == null || fqn.equals(pkg + "." + className)) {
                continue;
            }
            String ref = typeRefs.get(fqn);
            if (ref != null && ref.contains(".")) {
                continue; // contested simple name — referenced fully-qualified, never imported
            }
            imports.add(fqn);
        }
        // Cast-constant references (decision 18a): two schemas may declare entities with
        // the SAME simple name, and a subtype may collide with the generated class itself.
        // Importing either produces ambiguous or self-colliding references, so foreign
        // same-name subtypes are referenced by fully-qualified name and never imported
        for (String qualified : subtypeRefs.importNames()) {
            String simple = Names.entityClassName(Names.simpleNameFromFullName(qualified));
            if (!Names.entityClassName(Names.simpleNameFromFullName(qualified)).equals(className)) {
                imports.add(basePackageForType(qualified, schema)
                        + Names.packageNameSuffixEntity() + "." + simple);
            }
        }

        for (PropertyModel prop : allProps) {
            addPropertyImports(prop, imports, schema);
        }
        if (baseQualifiedName != null && !isContested(baseQualifiedName, schema)) {
            imports.add(typeFqnOf(baseQualifiedName, schema));
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
            sb.append(" extends ").append(refFor(baseQualifiedName, schema));
        }
        sb.append(" implements ODataEntityType {\n\n");

        // Static property constants
        for (PropertyModel prop : entityType.properties()) {
            sb.append(generatePropertyConstant(prop, className, schema));
        }
        sb.append("\n");

        // Static navigation property constants
        Set<String> usedConstantNames = new HashSet<>();
        for (PropertyModel prop : allProps) {
            usedConstantNames.add(constantNameFor(prop.name()));
        }
        for (NavigationPropertyModel nav : allNavs) {
            usedConstantNames.add(constantNameFor(nav.name()));
        }
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            sb.append(generateNavPropertyConstant(nav, className, schema));
            for (EntitySubtype subtype : subtypesFor(nav, schema)) {
                String constantName = uniqueSubtypeConstantName(
                        constantNameFor(nav.name()), subtype.model().name(), usedConstantNames);
                sb.append(generateSubtypeNavPropertyConstant(nav, className, schema, subtype,
                        constantName, subtypeRefs.refs().get(subtype.qualifiedName())));
            }
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

        // Setters annotated with @JsonProperty for Jackson deserialization (also used by Builder/with*).
        // Emitted for abstract types too: a concrete subtype of an abstract base has no setter
        // for the base's own properties anywhere unless the base declares them, and Jackson would
        // silently drop those properties on deserialization.
        for (PropertyModel prop : ownProps) {
            String javaType = resolvePropertyJavaType(prop, schema, true);
            String fn = Names.toJavaFieldName(prop.name());
            sb.append("    @com.fasterxml.jackson.annotation.JsonProperty(\"").append(Names.escapeJavaString(prop.name())).append("\")\n");
            sb.append("    public void set").append(Names.capitalize(fn)).append("(").append(javaType).append(" value) {\n");
            sb.append("        this.").append(fn).append(" = value;\n");
            sb.append("    }\n\n");
        }
        for (NavigationPropertyModel nav : ownNavs) {
            String javaType = navJavaType(nav, schema);
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("    @com.fasterxml.jackson.annotation.JsonProperty(\"").append(Names.escapeJavaString(nav.name())).append("\")\n");
            sb.append("    public void set").append(Names.capitalize(fn)).append("(").append(javaType).append(" value) {\n");
            sb.append("        this.").append(fn).append(" = value;\n");
            sb.append("    }\n\n");
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
        sb.append("        return \"").append(Names.escapeJavaString(schema.namespace())).append(".").append(Names.escapeJavaString(entityType.name())).append("\";\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n    public Optional<String> getETag() {\n");
        sb.append("        return Optional.ofNullable(etag);\n");
        sb.append("    }\n\n");

        // Header-only etag services: the runtime captures the ETag response header onto
        // root entities (the field lives here) when the body carried no @odata.etag
        sb.append("    @Override\n    public void applyETagFromResponse(String value) {\n");
        sb.append("        if (value != null && !value.isEmpty()) {\n");
        sb.append("            this.etag = value;\n");
        sb.append("        }\n");
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
                // HashMap (not Map.of) so a null key field doesn't throw NPE at runtime
                sb.append("        java.util.Map<String, Object> key = new java.util.HashMap<>();\n");
                for (String ref : refs) {
                    sb.append("        key.put(\"").append(Names.escapeJavaString(ref)).append("\", ")
                      .append(getterCall(ref, allProps)).append(");\n");
                }
                sb.append("        return java.util.Collections.unmodifiableMap(key);\n");
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
            sb.append("            ").append(first ? "\"" : "\", ").append(Names.escapeJavaString(prop.name())).append("=\" + ").append(fn).append(" +\n");
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
                        EntityTypeModel base = findBaseGlobal(bt);
                        // The base's namespace must be resolved EXACTLY (written qualified
                        // form, else identity scan) — the simple-name-keyed entityNamespace
                        // map returns ONE namespace when two schemas declare the same
                        // simple-named base, registering the extends under the wrong schema
                        String baseNs;
                        if (base != null) {
                            baseNs = Names.namespaceFromFullName(baseQualifiedNameOf(base, bt, s));
                        } else {
                            baseNs = Names.namespaceFromFullName(bt);
                        }
                        if (baseNs == null || baseNs.isEmpty()) baseNs = s.namespace();
                        if (baseNs.equals(ns)) {
                            String baseName = base != null ? base.name() : Names.simpleNameFromFullName(bt);
                            bases.add(Names.entityClassName(baseName));
                        }
                    }
                }
            }
            return bases;
        });
    }

    private EntityTypeModel findBaseGlobal(String bt) {
        if (bt == null || bt.isBlank()) return null;
        EntityTypeModel base = entityTypeByQualifiedName.get(bt);
        if (base != null) return base;
        String simple = Names.simpleNameFromFullName(bt);
        String className = Names.entityClassName(simple);
        // Search across all schemas for simple name (handles unqualified cross-schema).
        // Ambiguous matches must fail loudly (same policy as container Extends and the
        // type-kind map): first-wins would make generation order-dependent.
        EntityTypeModel found = null;
        int matches = 0;
        for (SchemaModel s : effectiveSchemas) {
            for (EntityTypeModel et : s.entityTypes()) {
                if (Names.entityClassName(et.name()).equals(className)) {
                    found = et;
                    matches++;
                }
            }
        }
        if (matches > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous unqualified BaseType '" + bt + "': matches " + matches
                            + " entity types with that simple name across schemas; use a qualified name (Namespace.Type)");
        }
        return found;
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

    /**
     * The base type's authoritative qualified name: the written form when qualified
     * (aliases are namespace-resolved at parse), else the owning schema found by
     * IDENTITY — the simple-name-keyed namespace map cannot distinguish same-named
     * bases from different schemas (exactly the split-merge case TypeRefs exists for).
     */
    private String baseQualifiedNameOf(EntityTypeModel base, String baseType, SchemaModel schema) {
        if (baseType != null && !Names.namespaceFromFullName(baseType).isEmpty()) {
            return baseType;
        }
        for (SchemaModel s : effectiveSchemas) {
            for (EntityTypeModel et : s.entityTypes()) {
                if (et == base) {
                    return s.namespace() + "." + base.name();
                }
            }
        }
        return schema.namespace() + "." + base.name();
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
        EntityTypeModel sameSchema = entityTypeMap.get(Names.entityClassName(Names.simpleNameFromFullName(bt)));
        if (sameSchema != null) return sameSchema;
        // Cross-schema unqualified fallback: search all schemas for simple name
        return findBaseGlobal(bt);
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
        String constantName = constantNameFor(prop.name());

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String elementClassName = resolveClassNameForConstant(elementType, schema);
            Names.TypeKind kind = Names.resolveTypeKind(elementType, effectiveSchemas);
            if (kind == Names.TypeKind.ENTITY || kind == Names.TypeKind.COMPLEX) {
                return "    public static final CollectionProperty<" + className + ", " + elementClassName
                        + ", " + elementClassName + ".Filterable> " + constantName
                        + " = new CollectionProperty<>(\"" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
                        + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
            } else {
                return "    public static final CollectionProperty<" + className + ", " + elementClassName
                        + ", CollectionProperty.FilterableElement<" + elementClassName + ">> " + constantName
                        + " = new CollectionProperty<>(\"" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
                        + elementClassName + ".class, CollectionProperty.FilterableElement::new);\n";
            }
        }

        String constantType = getPropertyConstantType(edmType, schema);
        if (constantType == null) {
            return ""; // Binary, Stream, Geography, Geometry — not filterable
        }
        String typeParams = switch (constantType) {
            case "EnumProperty" -> "<" + className + ", " + resolveClassNameForConstant(edmType, schema) + ">";
            case "NumberProperty" -> "<" + className + ", " + getNumberJavaType(resolveTypeDefinition(edmType, schema)) + ">";
            default -> "<" + className + ">";
        };

        String extra = "";
        if (constantType.equals("EnumProperty")) {
            extra = ", " + resolveClassNameForConstant(edmType, schema) + ".class, \"" + Names.escapeJavaString(qualifiedEdmName(resolveTypeDefinition(edmType, schema), schema)) + "\"";
        } else if (constantType.equals("NumberProperty")) {
            extra = ", \"" + Names.escapeJavaString(resolveTypeDefinition(edmType, schema)) + "\"";
        }
        return "    public static final " + constantType + typeParams + " "
                + constantName
                + " = new " + constantType + "<>(\"" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class"
                + extra
                + ");\n";
    }

    private String generateNavPropertyConstant(NavigationPropertyModel nav, String className, SchemaModel schema) {
        boolean isCollection = Names.isCollectionType(nav.type());
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = refFor(resolveTypeDefinition(unwrapped, schema), schema);
        String constantName = constantNameFor(nav.name());

        if (isCollection) {
            return "    public static final CollectionProperty<" + className + ", "
                    + elementClassName + ", " + elementClassName + ".Filterable> " + constantName
                    + " = new CollectionProperty<>(\"" + Names.escapeJavaString(nav.name()) + "\", " + className + ".class, "
                    + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
        } else {
            return "    public static final NavProperty<" + className + ", "
                    + elementClassName + "> " + constantName
                    + " = new NavProperty<>(\"" + Names.escapeJavaString(nav.name()) + "\", " + className + ".class, "
                    + elementClassName + ".class);\n";
        }
    }

    private String generateSubtypeNavPropertyConstant(NavigationPropertyModel nav, String className,
                                                       SchemaModel schema, EntitySubtype subtype,
                                                       String constantName, String javaRef) {
        return "    public static final NavProperty.NavQuery<" + className + ", "
                + javaRef + "> " + constantName + " = "
                + constantNameFor(nav.name()) + ".as(\"" + Names.escapeJavaString(subtype.qualifiedName()) + "\", "
                + javaRef + ".class);\n";
    }

    private record SubtypeRefs(java.util.Map<String, String> refs, java.util.Set<String> importNames) {}

    /**
     * Resolves each cast-subtype to a Java reference expression. The generated class
     * itself (same package + same simple name) stays an unqualified self-reference and
     * is never imported; any subtype whose simple name collides — with the generated
     * class (other package) or with another subtype from a different package — is
     * referenced by fully-qualified name and never imported.
     */
    private SubtypeRefs subtypeReferences(EntityTypeModel entityType,
                                          String className, String pkg,
                                          SchemaModel schema) {
        java.util.Map<String, String> refs = new java.util.LinkedHashMap<>();
        java.util.Set<String> importNames = new java.util.LinkedHashSet<>();
        List<EntitySubtype> all = new ArrayList<>();
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            all.addAll(subtypesFor(nav, schema));
        }
        java.util.Map<String, Set<String>> packagesBySimpleName = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> packageByQualifiedName = new java.util.LinkedHashMap<>();
        for (EntitySubtype subtype : all) {
            String simple = Names.entityClassName(subtype.model().name());
            String stPkg = basePackageForType(subtype.qualifiedName(), schema)
                    + Names.packageNameSuffixEntity();
            packagesBySimpleName.computeIfAbsent(simple, ignored -> new HashSet<>()).add(stPkg);
            packageByQualifiedName.put(subtype.qualifiedName(), stPkg);
        }
        for (EntitySubtype subtype : all) {
            String simple = Names.entityClassName(subtype.model().name());
            String stPkg = packageByQualifiedName.get(subtype.qualifiedName());
            boolean selfGenerated = simple.equals(className) && stPkg.equals(pkg);
            boolean collides = packagesBySimpleName.get(simple).size() > 1
                    || (simple.equals(className) && !selfGenerated);
            if (selfGenerated) {
                refs.put(subtype.qualifiedName(), simple);
            } else if (collides) {
                refs.put(subtype.qualifiedName(), stPkg + "." + simple);
            } else {
                refs.put(subtype.qualifiedName(), simple);
                importNames.add(subtype.qualifiedName());
            }
        }
        return new SubtypeRefs(refs, importNames);
    }

    private String uniqueSubtypeConstantName(String navConstantName, String subtypeName,
                                             Set<String> used) {
        String base = navConstantName + "_AS_" + Names.toConstantName(subtypeName);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private List<EntitySubtype> subtypesFor(NavigationPropertyModel nav, SchemaModel schema) {
        String target = resolveTypeDefinition(Names.unwrapCollectionType(nav.type()), schema);
        if (Names.resolveTypeKind(target, effectiveSchemas) != Names.TypeKind.ENTITY) {
            return List.of();
        }
        return subtypesByBase.getOrDefault(target, List.of());
    }

    private void ensureSubtypeIndex() {
        if (subtypesByBase != null) {
            return;
        }
        // Identity-keyed qualified names resolved once: chain walking below must stay
        // O(1) per lookup or large-metadata generation degrades quadratically (lesson 178)
        java.util.IdentityHashMap<EntityTypeModel, String> qualifiedNames = new java.util.IdentityHashMap<>();
        subtypesByBase = new HashMap<>();
        for (SchemaModel schema : effectiveSchemas) {
            for (EntityTypeModel et : schema.entityTypes()) {
                qualifiedNames.put(et, schema.namespace() + "." + et.name());
            }
        }
        for (SchemaModel schema : effectiveSchemas) {
            for (EntityTypeModel candidate : schema.entityTypes()) {
                String candidateQualifiedName = qualifiedNames.get(candidate);
                EntityTypeModel current = candidate;
                Set<String> visited = new HashSet<>();
                while (true) {
                    EntityTypeModel base = findBase(current);
                    if (base == null) {
                        break;
                    }
                    String baseQualifiedName = qualifiedNames.get(base);
                    if (baseQualifiedName == null || !visited.add(baseQualifiedName)) {
                        break;
                    }
                    subtypesByBase.computeIfAbsent(baseQualifiedName, ignored -> new ArrayList<>())
                            .add(new EntitySubtype(candidateQualifiedName, candidate));
                    current = base;
                }
            }
        }
    }

    private String generateGetter(PropertyModel prop, SchemaModel schema) {
        // Always boxed: the setter stores whatever Jackson deserialized (which can be
        // null even for Nullable="false" properties on lenient services), and a primitive
        // getter would NPE on unboxing at the call site
        String javaType = resolvePropertyJavaType(prop, schema, true);
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
        sb.append("        e.changedFields = EntityUtil.mergeChanged(changedFields, \"").append(Names.escapeJavaString(nav.name())).append("\");\n");
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
            sb.append("            changed.add(\"").append(Names.escapeJavaString(prop.name())).append("\");\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        for (NavigationPropertyModel nav : navs) {
            String javaType = navJavaType(nav, schema);
            String fn = Names.toJavaFieldName(nav.name());
            sb.append("        public Builder ").append(fn).append("(").append(javaType).append(" value) {\n");
            sb.append("            this.").append(fn).append(" = value;\n");
            // nav changes must be tracked like property changes, or partial PATCH drops them
            sb.append("            changed.add(\"").append(Names.escapeJavaString(nav.name())).append("\");\n");
            sb.append("            return this;\n");
            sb.append("        }\n\n");
        }

        sb.append("        public ").append(className).append(" build() {\n");
        for (var key : keys) {
            for (String keyProp : key.propertyRefs()) {
                sb.append("            Objects.requireNonNull(").append(Names.toJavaFieldName(keyProp))
                  .append(", \"").append(Names.escapeJavaString(keyProp)).append(" is required (key)\");\n");
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
        sb.append("        e.changedFields = EntityUtil.mergeChanged(changedFields, \"").append(Names.escapeJavaString(prop.name())).append("\");\n");
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

    /**
     * Key property refs that name no existing (own or inherited) property previously
     * produced Object-typed key accessors that only failed at URL-build time; fail at
     * generation with the entity and the offending ref named.
     */
    private static void checkKeyPropertyRefs(EntityTypeModel entityType, String className,
                                             List<PropertyModel> allProps) {
        for (var key : entityType.keys()) {
            for (String ref : key.propertyRefs()) {
                boolean found = allProps.stream().anyMatch(pr -> pr.name().equals(ref));
                if (!found) {
                    throw new IllegalStateException("Entity " + className + ": key PropertyRef '"
                            + ref + "' does not match any property (own or inherited)");
                }
            }
        }
    }
}
