package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared infrastructure for {@link EntityGenerator} and {@link ComplexTypeGenerator}.
 * Extracts duplicated helpers for type resolution, import collection, navigation
 * property handling, and the typed {@code Filterable} inner class used by collection
 * lambda operators ({@code any}/{@code all}).
 */
public abstract class AbstractTypeGenerator {

    protected final String basePackage;
    protected final Map<String, String> schemaPackages;
    protected final String defaultBasePackage;
    protected final List<SchemaModel> allSchemas;
    protected List<SchemaModel> effectiveSchemas;
    private boolean effectiveSchemasInitialized;
    protected boolean generateWithMethods;

    /**
     * Per-file generated-class references (FQN → simple name, or the FQN itself when two
     * schemas contribute the same simple name to one file — then the type is referenced
     * fully-qualified and never imported). Populated by each generator's generate()
     * before emission; identity behavior when left empty.
     */
    protected java.util.Map<String, String> typeRefs = java.util.Map.of();

    /** The full import-candidate FQN for a resolved type reference (never a primitive). */
    protected String typeFqnOf(String resolvedType, SchemaModel schema) {
        return basePackageForType(resolvedType, schema) + Names.resolvedSuffix(resolvedType, effectiveSchemas)
                + "." + Names.resolvedClassName(resolvedType, effectiveSchemas);
    }

    /** True when the resolved reference for this type is its fully-qualified name (contested simple name). */
    protected boolean isContested(String resolvedType, SchemaModel schema) {
        String ref = typeRefs.get(typeFqnOf(resolvedType, schema));
        return ref != null && ref.contains(".");
    }

    /** The reference to print for a resolved type: simple name, or FQN when contested. */
    protected String refFor(String resolvedType, SchemaModel schema) {
        String simple = Names.resolvedClassName(resolvedType, effectiveSchemas);
        return typeRefs.getOrDefault(typeFqnOf(resolvedType, schema), simple);
    }

    protected AbstractTypeGenerator(String basePackage, Map<String, String> schemaPackages,
                                    String defaultBasePackage, List<SchemaModel> allSchemas) {
        this(basePackage, schemaPackages, defaultBasePackage, allSchemas, false);
    }

    protected AbstractTypeGenerator(String basePackage, Map<String, String> schemaPackages,
                                    String defaultBasePackage, List<SchemaModel> allSchemas,
                                    boolean generateWithMethods) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
        this.allSchemas = allSchemas;
        this.generateWithMethods = generateWithMethods;
    }

    final void setGenerateWithMethods(boolean generateWithMethods) {
        this.generateWithMethods = generateWithMethods;
    }

    /**
     * Initializes {@code effectiveSchemas} once with a stable reference so the
     * {@code Names.resolveTypeKind} cache key (the list object identity) stays
     * consistent across all types within the same schema.
     */
    protected void initEffectiveSchemas(SchemaModel schema) {
        if (!effectiveSchemasInitialized) {
            effectiveSchemasInitialized = true;
            effectiveSchemas = allSchemas.isEmpty() ? List.of(schema) : allSchemas;
        }
    }

    // ------------------------------------------------------------------
    // Member-name collision detection
    // ------------------------------------------------------------------

    // Per-type allocation of property-constant names: case collisions (budget vs Budget
    // both folding to BUDGET) get a deterministic _2, _3 suffix instead of duplicate
    // constants that don't compile
    private final java.util.Map<String, String> constantNames = new java.util.HashMap<>();

    protected void allocateConstantNames(List<PropertyModel> props, List<NavigationPropertyModel> navs) {
        java.util.Set<String> used = new java.util.HashSet<>();
        // Constants must also dodge the generated FIELD names: case-less CSDL names
        // (a member named '_') fold field and constant onto the SAME identifier
        // ('__'), which javac rejects as a duplicate — so fields seed the used set.
        // For ordinary corpora constants are upper-case and fields camel-case, so
        // seeding changes no existing allocation.
        for (PropertyModel prop : props) {
            used.add(Names.toJavaFieldName(prop.name()));
        }
        for (NavigationPropertyModel nav : navs) {
            used.add(Names.toJavaFieldName(nav.name()));
        }
        for (PropertyModel prop : props) {
            allocateConstantName(prop.name(), used);
        }
        for (NavigationPropertyModel nav : navs) {
            allocateConstantName(nav.name(), used);
        }
    }

    private void allocateConstantName(String memberName, java.util.Set<String> used) {
        String base = Names.toConstantName(memberName);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix++;
        }
        constantNames.put(memberName, candidate);
    }

    protected String constantNameFor(String memberName) {
        String allocated = constantNames.get(memberName);
        return allocated != null ? allocated : Names.toConstantName(memberName);
    }

    /**
     * Fails with a clear error when two members of a type map to the same generated
     * Java identifier — e.g. properties {@code Name} and {@code name} both fold to field
     * {@code name}, or {@code budget} and {@code Budget} both map to constant
     * {@code BUDGET}. Without this check the generated class simply doesn't compile,
     * with duplicate-member errors far from the cause.
     */
    protected void checkMemberNameCollisions(String className, List<PropertyModel> props,
                                             List<NavigationPropertyModel> navs) {
        // constant collisions are auto-deduped via allocateConstantNames; field-level
        // folding (Name vs name -> field 'name') still fails loudly
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (PropertyModel prop : props) {
            checkCollision(fields, Names.toJavaFieldName(prop.name()), "field", prop.name(), className);
        }
        for (NavigationPropertyModel nav : navs) {
            checkCollision(fields, Names.toJavaFieldName(nav.name()), "field", nav.name(), className);
        }
    }

    /**
     * Merges an inherited member list with the type's own members: a derived type
     * may redeclare a base member (same CSDL name), in which case the OWN
     * declaration wins and appears exactly once. The collision check tolerates
     * exact same-name redeclaration, so without this merge both copies would flow
     * into Filterable/Builder/with* emission as duplicates. First-seen position
     * is kept (base-chain order), so member ordering is stable.
     */
    protected static List<PropertyModel> mergeOwnWinsProps(List<PropertyModel> inherited,
                                                           List<PropertyModel> own) {
        java.util.LinkedHashMap<String, PropertyModel> merged = new java.util.LinkedHashMap<>();
        for (PropertyModel p : inherited) {
            merged.putIfAbsent(p.name(), p);
        }
        for (PropertyModel p : own) {
            merged.put(p.name(), p);
        }
        return List.copyOf(merged.values());
    }

    /**
     * Navigation-property counterpart of {@link #mergeOwnWinsProps}: own navs win
     * over same-named inherited navs, each name emitted exactly once.
     */
    protected static List<NavigationPropertyModel> mergeOwnWinsNavs(List<NavigationPropertyModel> inherited,
                                                                    List<NavigationPropertyModel> own) {
        java.util.LinkedHashMap<String, NavigationPropertyModel> merged = new java.util.LinkedHashMap<>();
        for (NavigationPropertyModel n : inherited) {
            merged.putIfAbsent(n.name(), n);
        }
        for (NavigationPropertyModel n : own) {
            merged.put(n.name(), n);
        }
        return List.copyOf(merged.values());
    }

    /**
     * Fails loudly when an own member redeclares an inherited member with an
     * INCOMPATIBLE shape. Same-name same-shape redeclaration merges silently via
     * {@link #mergeOwnWinsProps} (harmless shadowing), but a different type (or,
     * for properties, different nullability — both change the getter signature)
     * is unrepresentable in Java inheritance: the subclass getter cannot override
     * the base getter, and request methods cannot overload. Fail here naming both
     * sides instead of emitting output that does not compile.
     */
    protected void checkRedeclarationConflicts(String className,
                                               List<PropertyModel> inheritedProps,
                                               List<PropertyModel> ownProps,
                                               List<NavigationPropertyModel> inheritedNavs,
                                               List<NavigationPropertyModel> ownNavs,
                                               SchemaModel schema) {
        Map<String, PropertyModel> inheritedByName = new HashMap<>();
        for (PropertyModel p : inheritedProps) {
            inheritedByName.putIfAbsent(p.name(), p);
        }
        for (PropertyModel p : ownProps) {
            PropertyModel base = inheritedByName.get(p.name());
            if (base == null) {
                continue;
            }
            String baseType = resolveTypeDefinition(base.edmType(), schema);
            String ownType = resolveTypeDefinition(p.edmType(), schema);
            if (!baseType.equals(ownType) || base.nullable() != p.nullable()) {
                throw new IllegalStateException("Cannot generate " + className + ": property '" + p.name()
                        + "' redeclares an inherited property with a different type (base: " + base.edmType()
                        + (base.nullable() ? " nullable" : "") + ", derived: " + p.edmType()
                        + (p.nullable() ? " nullable" : "") + "). The subclass getter cannot override "
                        + "the base getter — rename one of them in the metadata.");
            }
        }
        Map<String, NavigationPropertyModel> inheritedNavByName = new HashMap<>();
        for (NavigationPropertyModel n : inheritedNavs) {
            inheritedNavByName.putIfAbsent(n.name(), n);
        }
        for (NavigationPropertyModel n : ownNavs) {
            NavigationPropertyModel base = inheritedNavByName.get(n.name());
            if (base == null) {
                continue;
            }
            String baseTarget = resolveTypeDefinition(Names.unwrapCollectionType(base.type()), schema);
            String ownTarget = resolveTypeDefinition(Names.unwrapCollectionType(n.type()), schema);
            if (!baseTarget.equals(ownTarget)
                    || Names.isCollectionType(base.type()) != Names.isCollectionType(n.type())) {
                throw new IllegalStateException("Cannot generate " + className + ": navigation property '"
                        + n.name() + "' redeclares an inherited navigation with a different type (base: "
                        + base.type() + ", derived: " + n.type() + "). The subclass request methods cannot "
                        + "overload the base methods — rename one of them in the metadata.");
            }
        }
    }

    private static void checkCollision(java.util.Map<String, String> seen, String mapped,
                                       String kind, String sourceName, String className) {
        String previous = seen.putIfAbsent(mapped, sourceName);
        // Exact same-name redeclaration across an inheritance chain is tolerated by the
        // generators (the inherited declaration is ignored) — only DIFFERENT names that
        // collapse onto one identifier are real collisions
        if (previous != null && !previous.equals(sourceName)) {
            throw new IllegalStateException("Cannot generate " + className + ": members '" + previous
                    + "' and '" + sourceName + "' both map to " + kind + " '" + mapped
                    + "'. Rename one of them in the metadata.");
        }
    }

    // ------------------------------------------------------------------
    // Type resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a property's Java type. Collection elements are always boxed.
     * The {@code boxed} flag controls scalar primitive types only.
     */
    protected String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema, boolean boxed) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema, true) + ">";
        }
        return resolveSingleJavaType(edmType, schema, boxed);
    }

    /**
     * Resolves a property's Java type with scalar primitives boxed.
     * Used by complex-type generation where fields and builders use reference types.
     */
    protected String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema) + ">";
        }
        return resolveSingleJavaType(edmType, schema);
    }

    protected String resolveSingleJavaType(String edmType, SchemaModel schema, boolean boxed) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            String javaType = Names.edmTypeToSimpleJavaType(resolved);
            if (boxed) {
                return javaType;
            }
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
        // Contested simple names (same-named types from different output packages)
        // must be referenced fully-qualified and never imported — route through the
        // per-file TypeRefs resolution like navJavaType/resolveClassNameForConstant.
        // When typeRefs is empty (not yet populated) refFor falls back to the simple name.
        return refFor(resolved, schema);
    }

    protected String resolveSingleJavaType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return refFor(resolved, schema);
    }

    private java.util.Map<String, String> typeDefCache;

    // Resolve TypeDefinition to its underlying Edm type (recursively) across all schemas.
    // The cache is keyed by NAMESPACE-QUALIFIED name so a TypeDefinition named 'Foo' in
    // schema A cannot shadow a type named 'Foo' in schema B; unqualified references fall
    // back to simple-name lookup.
    protected String resolveTypeDefinition(String edmType, SchemaModel schema) {
        if (Names.isPrimitiveType(edmType)) return edmType;
        if (typeDefCache == null) {
            typeDefCache = new java.util.HashMap<>();
            java.util.Map<String, String> simpleDef = new java.util.HashMap<>();
            java.util.Set<String> ambiguous = new java.util.HashSet<>();
            for (SchemaModel s : effectiveSchemas) {
                for (var td : s.typeDefinitions()) {
                    String qualified = s.namespace() + "." + td.name();
                    if (!typeDefCache.containsKey(qualified)) {
                        String resolved = resolveTypeDefinitionChain(qualified, new java.util.HashSet<>());
                        typeDefCache.put(qualified, resolved);
                        if (!ambiguous.contains(td.name())) {
                            String existing = simpleDef.get(td.name());
                            if (existing == null) {
                                simpleDef.put(td.name(), resolved);
                            } else if (!existing.equals(resolved)) {
                                simpleDef.remove(td.name());
                                ambiguous.add(td.name());
                            }
                        }
                    }
                }
            }
            for (var e : simpleDef.entrySet()) {
                if (!ambiguous.contains(e.getKey())) {
                    typeDefCache.put(e.getKey(), e.getValue());
                }
            }
        }
        String resolved = typeDefCache.get(edmType);
        if (resolved == null) {
            resolved = typeDefCache.get(Names.simpleNameFromFullName(edmType));
        }
        return resolved != null ? resolved : edmType;
    }

    private String resolveTypeDefinitionChain(String typeName, java.util.Set<String> visiting) {
        if (!visiting.add(typeName)) {
            throw new IllegalStateException("Circular TypeDefinition chain detected involving: " + typeName);
        }
        String simpleName = Names.simpleNameFromFullName(typeName);
        for (SchemaModel s : effectiveSchemas) {
            for (var td : s.typeDefinitions()) {
                if (td.name().equals(simpleName)
                        && (typeName.equals(td.name())
                            || typeName.equals(s.namespace() + "." + td.name()))) {
                    String underlying = td.underlyingType();
                    if (Names.isPrimitiveType(underlying)) return underlying;
                    return resolveTypeDefinitionChain(underlying, visiting);
                }
            }
        }
        return typeName;
    }

    /**
     * Enum filter literals must use the fully qualified name (NS.Enum'Member'). CSDL type
     * references are normally qualified (and aliases resolve at parse time), but lenient
     * metadata may use bare names — qualify them with the owning schema's namespace.
     */
    protected static String qualifiedEdmName(String edmType, SchemaModel schema) {
        if (edmType.indexOf('.') >= 0) {
            return edmType;
        }
        return schema.namespace() + "." + edmType;
    }

    // ------------------------------------------------------------------
    // Imports
    // ------------------------------------------------------------------

    protected void addPropertyImports(PropertyModel prop, Set<String> imports, SchemaModel schema) {

        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String resolvedElement = resolveTypeDefinition(elementType, schema);
            if (Names.isPrimitiveType(resolvedElement)) {
                String javaType = Names.edmTypeToSimpleJavaType(resolvedElement);
                if (javaType.startsWith("java.")) imports.add(javaType);
            } else if (!isContested(resolvedElement, schema)) {
                String pkg = basePackageForType(resolvedElement, schema);
                imports.add(pkg + Names.resolvedSuffix(resolvedElement, effectiveSchemas) + "."
                        + Names.resolvedClassName(resolvedElement, effectiveSchemas));
            }
        } else if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (javaType.startsWith("java.")) imports.add(javaType);
        } else if (!isContested(edmType, schema)) {
            String pkg = basePackageForType(edmType, schema);
            imports.add(pkg + Names.resolvedSuffix(edmType, effectiveSchemas) + "."
                    + Names.resolvedClassName(edmType, effectiveSchemas));
        }
    }

    protected void addNavImports(NavigationPropertyModel nav, Set<String> imports, SchemaModel schema) {
        String fqn = navTargetFqn(nav, schema);
        if (fqn == null) {
            return;
        }
        String ref = typeRefs.get(fqn);
        if (ref != null && ref.contains(".")) {
            return; // contested simple name — referenced fully-qualified, never imported
        }
        imports.add(fqn);
    }

    /**
     * Import-candidate FQN for a navigation target — the typedef chain is RESOLVED
     * first so the candidate keys match the {@code navJavaType()}/{@code refFor()}
     * emission (a typedef has no generated class of its own; the file references the
     * underlying entity/complex/enum). Null when the target resolves to an Edm
     * primitive: no generated class, no import, no candidate.
     */
    protected String navTargetFqn(NavigationPropertyModel nav, SchemaModel schema) {
        String resolved = resolveTypeDefinition(Names.unwrapCollectionType(nav.type()), schema);
        if (Names.isPrimitiveType(resolved)) {
            return null;
        }
        return typeFqnOf(resolved, schema);
    }

    /** Mirrors addPropertyImports: the generated-class FQNs a property contributes to the file. */
    protected void collectPropertyTypeFqns(PropertyModel prop, SchemaModel schema, List<String> out) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String resolvedElement = resolveTypeDefinition(Names.unwrapCollectionType(edmType), schema);
            if (!Names.isPrimitiveType(resolvedElement)) {
                out.add(typeFqnOf(resolvedElement, schema));
            }
        } else if (!Names.isPrimitiveType(edmType)) {
            out.add(typeFqnOf(edmType, schema));
        }
    }

    // Look up the base package for a cross-namespace type reference
    protected String basePackageForType(String edmType, SchemaModel schema) {
        String namespace = Names.namespaceFromFullName(edmType);
        if (namespace.isEmpty() || namespace.equals(schema.namespace())) {
            return basePackage;
        }
        return schemaPackages.getOrDefault(namespace,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(namespace));
    }

    // ------------------------------------------------------------------
    // Navigation properties
    // ------------------------------------------------------------------

    protected String navJavaType(NavigationPropertyModel nav, SchemaModel schema) {
        String unwrapped = Names.unwrapCollectionType(nav.type());
        // Resolve TypeDefinition chains so the Java type references the UNDERLYING
        // entity/complex/enum class (a typedef has no generated class of its own)
        String resolved = resolveTypeDefinition(unwrapped, schema);
        String elementClassName = refFor(resolved, schema);
        if (Names.isCollectionType(nav.type())) {
            return "List<" + elementClassName + ">";
        }
        return elementClassName;
    }

    protected String navGetterName(NavigationPropertyModel nav) {
        return Names.navGetterMethod(nav.name());
    }

    protected String navWithMethod(NavigationPropertyModel nav) {
        return Names.navWithMethod(nav.name());
    }

    protected String generateNavGetter(NavigationPropertyModel nav, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        if (Names.isCollectionType(nav.type())) {
            sb.append("    public ").append(javaType).append(" ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return ").append(fn).append(" == null ? List.of() : Collections.unmodifiableList(").append(fn).append(");\n");
            sb.append("    }\n\n");
        } else {
            sb.append("    public Optional<").append(javaType).append("> ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Filterable inner class for collection lambdas (any/all)
    // ------------------------------------------------------------------

    protected String generateFilterableClass(List<PropertyModel> allProps,
                                             List<NavigationPropertyModel> allNavs,
                                             String className,
                                             SchemaModel schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public static class Filterable {\n");
        for (PropertyModel prop : allProps) {
            sb.append(generateFilterablePropertyField(prop, className, schema));
        }
        for (NavigationPropertyModel nav : allNavs) {
            if (Names.isCollectionType(nav.type())) {
                sb.append(generateFilterableNavPropertyField(nav, className, schema));
            }
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    protected String generateFilterablePropertyField(PropertyModel prop, String className, SchemaModel schema) {
        String edmType = prop.edmType();
        String constantName = constantNameFor(prop.name());

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String elementClassName = resolveClassNameForConstant(elementType, schema);
            Names.TypeKind kind = Names.resolveTypeKind(elementType, effectiveSchemas);
            if (kind == Names.TypeKind.ENTITY || kind == Names.TypeKind.COMPLEX) {
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", " + elementClassName + ".Filterable> " + constantName
                        + " = new CollectionProperty<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
                        + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
            } else {
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", CollectionProperty.FilterableElement<" + elementClassName + ">> " + constantName
                        + " = new CollectionProperty<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
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
        return "    public final " + constantType + typeParams + " " + constantName
                + " = new " + constantType + "<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class"
                + extra
                + ");\n";
    }

    protected String generateFilterableNavPropertyField(NavigationPropertyModel nav, String className, SchemaModel schema) {
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = refFor(resolveTypeDefinition(unwrapped, schema), schema);
        // must go through the per-type allocation like every other emission site —
        // the raw name collides with a property constant when e.g. prop BUDGET + nav budget
        String constantName = constantNameFor(nav.name());
        return "    public final CollectionProperty<" + className + ", "
                + elementClassName + ", " + elementClassName + ".Filterable> " + constantName
                + " = new CollectionProperty<>(\"x/" + Names.escapeJavaString(nav.name()) + "\", " + className + ".class, "
                + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
    }

    protected String resolveClassNameForConstant(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (isContested(resolved, schema)) {
            return typeRefs.get(typeFqnOf(resolved, schema));
        }
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return Names.resolvedClassName(resolved, effectiveSchemas);
    }

    protected String getNumberJavaType(String edmType) {
        return Names.edmTypeToSimpleJavaType(edmType);
    }

    protected String getPropertyConstantType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        // Edm.Guid literals are unquoted bare values in $filter (quoted strings are a type
        // error), so Guid gets its own property class before the String mapping
        if ("Edm.Guid".equals(resolved)) return "GuidProperty";
        if (Names.isStringType(resolved)) return "StringProperty";
        if (Names.isBooleanType(resolved)) return "BooleanProperty";
        if (Names.isDateTimeType(resolved)) return "DateTimeProperty";
        if (isEnumType(resolved, schema)) return "EnumProperty";
        if (Names.isNumericType(resolved)) return "NumberProperty";
        return null; // Binary, Stream, Geography, Geometry — not filterable, no constant
    }

    protected boolean isEnumType(String edmType, SchemaModel schema) {
        return Names.resolveTypeKind(edmType, effectiveSchemas) == Names.TypeKind.ENUM;
    }
}
