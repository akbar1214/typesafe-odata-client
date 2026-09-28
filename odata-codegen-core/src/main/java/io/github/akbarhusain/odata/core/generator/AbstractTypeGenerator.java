package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        // Every entry point that renders a file calls this with the schema being generated,
        // so it is the one place `generatingNamespace` can be recorded reliably. It must be
        // refreshed on every call, not only on first initialisation: one generator instance
        // renders many schemas.
        if (schema != null) {
            generatingNamespace = schema.namespace();
        }
        if (!effectiveSchemasInitialized) {
            effectiveSchemasInitialized = true;
            effectiveSchemas = allSchemas.isEmpty() ? List.of(schema) : allSchemas;
        } else if (allSchemas.isEmpty()
                && (effectiveSchemas == null || effectiveSchemas.size() != 1
                || effectiveSchemas.get(0) != schema)) {
            effectiveSchemas = List.of(schema);
            resetTypeResolutionState();
        }
    }

    private void resetTypeResolutionState() {
        typeDefinitionsByQualified = null;
        typeDefinitionsBySimple = null;
        definitionQualifiedNames = null;
        definitionOwners = null;
        resolvedTypeDefinitions = null;
        typeKindsByQualified = null;
        propertyOwners.clear();
        navigationOwners.clear();
        typeRefs = java.util.Map.of();
    }

    private java.util.Map<String, io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel> typeDefinitionsByQualified;
    private java.util.Map<String, List<io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel>> typeDefinitionsBySimple;
    private java.util.IdentityHashMap<io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel, String> definitionQualifiedNames;
    private java.util.IdentityHashMap<io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel, SchemaModel> definitionOwners;
    private java.util.Map<String, String> resolvedTypeDefinitions;
    private java.util.Map<String, Names.TypeKind> typeKindsByQualified;

    private void ensureTypeIndexes() {
        if (typeDefinitionsByQualified != null) return;
        typeDefinitionsByQualified = new java.util.HashMap<>();
        typeDefinitionsBySimple = new java.util.HashMap<>();
        definitionQualifiedNames = new java.util.IdentityHashMap<>();
        definitionOwners = new java.util.IdentityHashMap<>();
        resolvedTypeDefinitions = new java.util.HashMap<>();
        typeKindsByQualified = new java.util.HashMap<>();
        for (SchemaModel s : effectiveSchemas) {
            for (var entity : s.entityTypes()) {
                putTypeKind(s.namespace() + "." + entity.name(), Names.TypeKind.ENTITY);
            }
            for (var complex : s.complexTypes()) {
                putTypeKind(s.namespace() + "." + complex.name(), Names.TypeKind.COMPLEX);
            }
            for (var enumeration : s.enumTypes()) {
                putTypeKind(s.namespace() + "." + enumeration.name(), Names.TypeKind.ENUM);
            }
            for (var definition : s.typeDefinitions()) {
                String qualified = s.namespace() + "." + definition.name();
                if (typeDefinitionsByQualified.putIfAbsent(qualified, definition) != null) {
                    throw new IllegalStateException("Duplicate TypeDefinition '" + qualified + "'");
                }
                typeDefinitionsBySimple.computeIfAbsent(definition.name(), ignored -> new ArrayList<>())
                        .add(definition);
                definitionQualifiedNames.put(definition, qualified);
                definitionOwners.put(definition, s);
            }
        }
    }

    private void putTypeKind(String qualified, Names.TypeKind kind) {
        Names.TypeKind previous = typeKindsByQualified.putIfAbsent(qualified, kind);
        if (previous != null && previous != kind) {
            throw new IllegalStateException("Qualified type '" + qualified
                    + "' is declared as more than one model kind");
        }
    }

    protected String resolveTypeDefinition(String edmType, SchemaModel schema) {
        if (edmType == null || edmType.isBlank()) return edmType;
        String value = edmType.trim();
        if (Names.isCollectionType(value)) {
            return "Collection(" + resolveTypeDefinition(Names.unwrapCollectionType(value), schema) + ")";
        }
        if (Names.isPrimitiveType(value)) return value;
        ensureTypeIndexes();
        return resolveTypeReference(value, schema, new java.util.HashSet<>());
    }

    private String resolveTypeReference(String value, SchemaModel schema, Set<String> visiting) {
        if (Names.isPrimitiveType(value)) return value;
        if (value.contains(".")) {
            var definition = typeDefinitionsByQualified.get(value);
            return definition == null ? value : resolveTypeDefinitionValue(definition, visiting);
        }

        String localDefinition = uniqueLocalTypeDefinition(value, schema, "TypeDefinition");
        if (localDefinition != null) {
            return resolveTypeDefinitionValue(typeDefinitionsByQualified.get(localDefinition), visiting);
        }
        if (hasLocalModelType(value, schema)) {
            return schema.namespace() + "." + value;
        }

        List<String> globalDefinitions = new ArrayList<>();
        for (var definition : typeDefinitionsBySimple.getOrDefault(value, List.of())) {
            String qualified = qualifiedNameOfDefinition(definition);
            if (qualified != null) globalDefinitions.add(qualified);
        }
        if (globalDefinitions.size() == 1) {
            return resolveTypeDefinitionValue(typeDefinitionsByQualified.get(globalDefinitions.get(0)), visiting);
        }
        if (globalDefinitions.size() > 1) {
            throw new IllegalArgumentException("Ambiguous unqualified TypeDefinition '" + value + "'");
        }

        List<String> globalModels = new ArrayList<>();
        for (SchemaModel s : effectiveSchemas) {
            if (hasModelType(value, s)) {
                globalModels.add(s.namespace() + "." + value);
            }
        }
        if (globalModels.size() == 1) return globalModels.get(0);
        if (globalModels.size() > 1) {
            throw new IllegalArgumentException("Ambiguous unqualified type '" + value + "'");
        }
        return value;
    }

    private String uniqueLocalTypeDefinition(String value, SchemaModel schema, String kind) {
        if (schema == null) return null;
        String qualified = schema.namespace() + "." + value;
        return typeDefinitionsByQualified.containsKey(qualified) ? qualified : null;
    }

    private boolean hasLocalModelType(String value, SchemaModel schema) {
        return schema != null && hasModelType(value, schema);
    }

    private boolean hasModelType(String value, SchemaModel schema) {
        return modelKindCount(value, schema) > 0;
    }

    private int modelKindCount(String value, SchemaModel schema) {
        int count = 0;
        if (schema.entityTypes().stream().anyMatch(t -> t.name().equals(value))) count++;
        if (schema.complexTypes().stream().anyMatch(t -> t.name().equals(value))) count++;
        if (schema.enumTypes().stream().anyMatch(t -> t.name().equals(value))) count++;
        return count;
    }

    private String resolveTypeDefinitionValue(
            io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel definition,
            Set<String> visiting) {
        String qualified = findDefinitionQualifiedName(definition);
        if (qualified == null) return definition.underlyingType();
        String cached = resolvedTypeDefinitions.get(qualified);
        if (cached != null) return cached;
        if (!visiting.add(qualified)) {
            throw new IllegalStateException("Circular TypeDefinition chain detected involving: " + qualified);
        }
        try {
            SchemaModel owner = definitionOwners.get(definition);
            String resolved = resolveTypeReference(definition.underlyingType(), owner, visiting);
            resolvedTypeDefinitions.put(qualified, resolved);
            return resolved;
        } finally {
            visiting.remove(qualified);
        }
    }

    private String findDefinitionQualifiedName(
            io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel definition) {
        return qualifiedNameOfDefinition(definition);
    }

    private String qualifiedNameOfDefinition(
            io.github.akbarhusain.odata.core.model.CsdlModel.TypeDefinitionModel definition) {
        return definitionQualifiedNames.get(definition);
    }

    protected Names.TypeKind resolveTypeKind(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) return Names.TypeKind.UNKNOWN;
        ensureTypeIndexes();
        Names.TypeKind kind = typeKindsByQualified.get(resolved);
        if (kind != null) return kind;
        if (!resolved.contains(".")) {
            if (schema != null && hasLocalModelType(resolved, schema)) {
                return localTypeKind(resolved, schema);
            }
            Names.TypeKind found = null;
            int matches = 0;
            for (SchemaModel s : effectiveSchemas) {
                if (hasModelType(resolved, s)) {
                    found = localTypeKind(resolved, s);
                    matches++;
                }
            }
            if (matches > 1) throw new IllegalArgumentException("Ambiguous unqualified type '" + resolved + "'");
            return found == null ? Names.TypeKind.UNKNOWN : found;
        }
        return Names.TypeKind.UNKNOWN;
    }

    private Names.TypeKind localTypeKind(String name, SchemaModel schema) {
        int matches = modelKindCount(name, schema);
        if (matches > 1) {
            throw new IllegalArgumentException("Ambiguous unqualified type '" + name
                    + "' in schema '" + schema.namespace() + "'");
        }
        if (schema.entityTypes().stream().anyMatch(t -> t.name().equals(name))) return Names.TypeKind.ENTITY;
        if (schema.complexTypes().stream().anyMatch(t -> t.name().equals(name))) return Names.TypeKind.COMPLEX;
        return Names.TypeKind.ENUM;
    }

    protected String requireKnownType(String rawType, SchemaModel schema, String owner, String member) {
        String element = Names.isCollectionType(rawType) ? Names.unwrapCollectionType(rawType) : rawType;
        String resolved = resolveTypeDefinition(element, schema);
        if (Names.isPrimitiveType(resolved)) {
            if (!Names.isKnownEdmType(resolved)) {
                throw new IllegalStateException("Cannot generate " + owner + ": " + member
                        + " references unknown Edm type '" + rawType + "' (resolved to '"
                        + resolved + "')");
            }
            return resolved;
        }
        if (resolveTypeKind(resolved, schema) == Names.TypeKind.UNKNOWN) {
            throw new IllegalStateException("Cannot generate " + owner + ": " + member
                    + " references unknown type '" + rawType + "' (resolved to '"
                    + resolved + "')");
        }
        return resolved;
    }

    protected void validateTypeUsages(String owner, List<PropertyModel> properties,
                                      List<NavigationPropertyModel> navs, SchemaModel schema) {
        for (PropertyModel property : properties) {
            requireKnownType(property.edmType(), schemaForProperty(property, schema), owner,
                    "property '" + property.name() + "'");
        }
        for (NavigationPropertyModel nav : navs) {
            requireKnownType(nav.type(), schemaForNavigation(nav, schema), owner,
                    "navigation property '" + nav.name() + "'");
        }
    }

    protected void validateKeyProperties(String owner, List<io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel> keys,
                                         List<PropertyModel> properties, SchemaModel schema) {
        for (var key : keys) {
            for (String ref : key.propertyRefs()) {
                PropertyModel property = properties.stream()
                        .filter(candidate -> candidate.name().equals(ref)).findFirst().orElse(null);
                if (property == null) {
                    throw new IllegalStateException("Cannot generate " + owner + ": key PropertyRef '"
                            + ref + "' does not match any property (own or inherited)");
                }
                if (property.nullable()) {
                    throw new IllegalStateException("Cannot generate " + owner + ": key property '"
                            + ref + "' must be non-null");
                }
                String type = resolveTypeDefinition(property.edmType(), schemaForProperty(property, schema));
                boolean enumType = resolveTypeKind(type, schemaForProperty(property, schema)) == Names.TypeKind.ENUM;
                if (Names.isCollectionType(property.edmType()) || (!Names.isKeyScalarType(type) && !enumType)) {
                    throw new IllegalStateException("Cannot generate " + owner + ": key property '"
                            + ref + "' must be a non-null scalar key type, but is '"
                            + property.edmType() + "'");
                }
            }
        }
    }

    private final java.util.IdentityHashMap<PropertyModel, SchemaModel> propertyOwners =
            new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<NavigationPropertyModel, SchemaModel> navigationOwners =
            new java.util.IdentityHashMap<>();

    private void ensureMemberOwners(SchemaModel fallback) {
        if (!propertyOwners.isEmpty() || !navigationOwners.isEmpty()) return;
        for (SchemaModel s : effectiveSchemas) {
            for (var entity : s.entityTypes()) {
                for (var property : entity.properties()) propertyOwners.put(property, s);
                for (var nav : entity.navigationProperties()) navigationOwners.put(nav, s);
            }
            for (var complex : s.complexTypes()) {
                for (var property : complex.properties()) propertyOwners.put(property, s);
                for (var nav : complex.navigationProperties()) navigationOwners.put(nav, s);
            }
        }
    }

    protected SchemaModel schemaForProperty(PropertyModel property, SchemaModel fallback) {
        ensureMemberOwners(fallback);
        SchemaModel owner = propertyOwners.get(property);
        return owner != null ? owner : fallback;
    }

    protected SchemaModel schemaForNavigation(NavigationPropertyModel nav, SchemaModel fallback) {
        ensureMemberOwners(fallback);
        SchemaModel owner = navigationOwners.get(nav);
        return owner != null ? owner : fallback;
    }

    // ------------------------------------------------------------------
    // Member-name collision detection
    // ------------------------------------------------------------------

    // Per-type allocation of property-constant names: case collisions (budget vs Budget
    // both folding to BUDGET) get a deterministic _2, _3 suffix instead of duplicate
    // constants that don't compile
    private final java.util.Map<String, String> constantNames = new java.util.HashMap<>();

    protected void resetConstantNames() {
        constantNames.clear();
    }

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
        Set<String> propertyNames = new HashSet<>();
        for (PropertyModel prop : props) propertyNames.add(prop.name());
        for (NavigationPropertyModel nav : navs) {
            if (propertyNames.contains(nav.name())) {
                throw new IllegalStateException("Cannot generate " + className
                        + ": property and navigation property both use name '" + nav.name() + "'");
            }
        }
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (PropertyModel prop : props) {
            checkCollision(fields, Names.toJavaFieldName(prop.name()), "field", prop.name(), className);
        }
        for (NavigationPropertyModel nav : navs) {
            checkCollision(fields, Names.toJavaFieldName(nav.name()), "field", nav.name(), className);
        }
    }

    protected void checkGeneratedMethodCollisions(String className, List<PropertyModel> props,
                                                   List<NavigationPropertyModel> navs,
                                                   List<String> generatedMethods) {
        java.util.Map<String, String> methods = new java.util.HashMap<>();
        for (String method : generatedMethods) methods.putIfAbsent(method, "generated member");
        for (PropertyModel prop : props) {
            String field = Names.toJavaFieldName(prop.name());
            checkCollision(methods, Names.getterMethod(prop), "getter", prop.name(), className);
            checkCollision(methods, "set" + Names.capitalize(field), "setter", prop.name(), className);
            checkCollision(methods, Names.withMethod(prop), "with method", prop.name(), className);
        }
        for (NavigationPropertyModel nav : navs) {
            String field = Names.toJavaFieldName(nav.name());
            checkCollision(methods, Names.navGetterMethod(nav.name()), "getter", nav.name(), className);
            checkCollision(methods, "set" + Names.capitalize(field), "setter", nav.name(), className);
            checkCollision(methods, Names.navWithMethod(nav.name()), "with method", nav.name(), className);
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
     * {@link #mergeOwnWinsProps} (harmless shadowing), but anything else is
     * rejected for two independent reasons. First, CSDL permits a same-name
     * redeclare only as a narrowing to a <em>derived subtype</em> (and 4.0
     * responses forbid it entirely) — a type change like {@code Edm.String} to
     * {@code Edm.Int32} is a spec violation. Second, the generated code cannot
     * represent even facet-only differences: nullability changes the getter
     * signature ({@code Optional<String>} vs {@code String}, verified by compiling
     * the unchecked output), and nav differences break the request methods.
     * Deliberately conservative: legal narrowings (e.g. a nav narrowed to a
     * subtype entity) also fail loudly until narrowing is a supported feature —
     * a loud decision beats silently mis-shaping the hierarchy. Request-side
     * generation tolerates these deterministically (single merged method), so the
     * model-side throw covers the normal pipeline; standalone request generation
     * is the only path that stays lenient. Fail here naming both sides instead of
     * emitting output that does not compile.
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
            String baseType = resolveTypeDefinition(base.edmType(), schemaForProperty(base, schema));
            String ownType = resolveTypeDefinition(p.edmType(), schemaForProperty(p, schema));
            if (!baseType.equals(ownType) || base.nullable() != p.nullable()) {
                throw new IllegalStateException("Cannot generate " + className + ": property '" + p.name()
                        + "' redeclares an inherited property with an incompatible type (base: " + base.edmType()
                        + (base.nullable() ? " nullable" : "") + ", derived: " + p.edmType()
                        + (p.nullable() ? " nullable" : "") + "). CSDL permits same-name redeclaration only "
                        + "for narrowing to a derived subtype, and the generated subclass getter cannot "
                        + "compatibly override the base getter — rename one of them in the metadata.");
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
            String baseTarget = resolveTypeDefinition(Names.unwrapCollectionType(base.type()), schemaForNavigation(base, schema));
            String ownTarget = resolveTypeDefinition(Names.unwrapCollectionType(n.type()), schemaForNavigation(n, schema));
            if (!baseTarget.equals(ownTarget)
                    || Names.isCollectionType(base.type()) != Names.isCollectionType(n.type())) {
                throw new IllegalStateException("Cannot generate " + className + ": navigation property '"
                        + n.name() + "' redeclares an inherited navigation with an incompatible type (base: "
                        + base.type() + ", derived: " + n.type() + "). CSDL permits same-name redeclaration "
                        + "only for narrowing to a derived entity subtype, and the generated subclass request "
                        + "methods cannot overload the base methods — rename one of them in the metadata.");
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
        SchemaModel owner = schemaForProperty(prop, schema);
        String edmType = resolveTypeDefinition(prop.edmType(), owner);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, owner, true) + ">";
        }
        return resolveSingleJavaType(edmType, owner, boxed);
    }

    /**
     * Resolves a property's Java type with scalar primitives boxed.
     * Used by complex-type generation where fields and builders use reference types.
     */
    protected String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema) {
        SchemaModel owner = schemaForProperty(prop, schema);
        String edmType = resolveTypeDefinition(prop.edmType(), owner);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, owner) + ">";
        }
        return resolveSingleJavaType(edmType, owner);
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

        SchemaModel owner = schemaForProperty(prop, schema);
        String edmType = resolveTypeDefinition(prop.edmType(), owner);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String resolvedElement = resolveTypeDefinition(elementType, owner);
            if (Names.isPrimitiveType(resolvedElement)) {
                String javaType = Names.edmTypeToSimpleJavaType(resolvedElement);
                if (javaType.startsWith("java.")) imports.add(javaType);
            } else if (!isContested(resolvedElement, owner)) {
                String pkg = basePackageForType(resolvedElement, owner);
                imports.add(pkg + Names.resolvedSuffix(resolvedElement, effectiveSchemas) + "."
                        + Names.resolvedClassName(resolvedElement, effectiveSchemas));
            }
        } else if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (javaType.startsWith("java.")) imports.add(javaType);
        } else if (!isContested(edmType, owner)) {
            String pkg = basePackageForType(edmType, owner);
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
        SchemaModel owner = schemaForNavigation(nav, schema);
        String resolved = resolveTypeDefinition(Names.unwrapCollectionType(nav.type()), owner);
        if (Names.isPrimitiveType(resolved)) {
            return null;
        }
        return typeFqnOf(resolved, owner);
    }

    /** Mirrors addPropertyImports: the generated-class FQNs a property contributes to the file. */
    protected void collectPropertyTypeFqns(PropertyModel prop, SchemaModel schema, List<String> out) {
        String edmType = resolveTypeDefinition(prop.edmType(), schemaForProperty(prop, schema));
        if (Names.isCollectionType(edmType)) {
            SchemaModel owner = schemaForProperty(prop, schema);
            String resolvedElement = resolveTypeDefinition(Names.unwrapCollectionType(edmType), owner);
            if (!Names.isPrimitiveType(resolvedElement)) {
                out.add(typeFqnOf(resolvedElement, owner));
            }
        } else if (!Names.isPrimitiveType(edmType)) {
            out.add(typeFqnOf(edmType, schemaForProperty(prop, schema)));
        }
    }

    /**
     * The namespace of the schema whose files this generator is currently writing.
     * {@code basePackage} is that schema's package, so a type belonging to this namespace
     * always resolves to {@code basePackage} — the two must be compared together.
     */
    protected String generatingNamespace;

    // Look up the base package for a cross-namespace type reference
    protected String basePackageForType(String edmType, SchemaModel schema) {
        String namespace = Names.namespaceFromFullName(edmType);
        if (namespace.isEmpty()) {
            return basePackage;
        }
        // The comparison must be against the schema BEING GENERATED, not `schema` (which
        // callers pass as the DECLARING schema, so it always matched and the lookup below
        // was skipped). A type declared in the schema being generated belongs to
        // `basePackage`; anything else resolves through the package map, exactly as
        // Generator.generateSchema placed it on disk.
        if (namespace.equals(generatingNamespace)) {
            return basePackage;
        }
        return schemaPackages.getOrDefault(namespace,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(namespace));
    }

    // ------------------------------------------------------------------
    // Navigation properties
    // ------------------------------------------------------------------

    protected String navJavaType(NavigationPropertyModel nav, SchemaModel schema) {
        SchemaModel owner = schemaForNavigation(nav, schema);
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String resolved = resolveTypeDefinition(unwrapped, owner);
        String elementClassName = refFor(resolved, owner);
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
                sb.append(generateFilterableNavField(nav, className, schema));
            }
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    protected String generateFilterablePropertyField(PropertyModel prop, String className, SchemaModel schema) {
        SchemaModel owner = schemaForProperty(prop, schema);
        String edmType = prop.edmType();
        String constantName = constantNameFor(prop.name());

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String elementClassName = resolveClassNameForConstant(elementType, owner);
            Names.TypeKind kind = resolveTypeKind(elementType, owner);
            if (kind == Names.TypeKind.ENTITY || kind == Names.TypeKind.COMPLEX) {
                // wildcard Sel: Filterable fields serve any/all only, never selector lambdas
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", " + elementClassName + ".Filterable, ?> " + constantName
                        + " = new CollectionProperty<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
                        + elementClassName + ".class, " + elementClassName + ".Filterable::new, null, "
                        + collectionElementEdmTypeLiteral(prop.edmType(), owner) + ");\n";
            } else {
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", CollectionProperty.FilterableElement<" + elementClassName + ">, ?> " + constantName
                        + " = new CollectionProperty<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class, "
                        + elementClassName + ".class, CollectionProperty.FilterableElement::new, null, "
                        + collectionElementEdmTypeLiteral(prop.edmType(), owner) + ");\n";
            }
        }

        String constantType = getPropertyConstantType(edmType, owner);
        if (constantType == null) {
            return ""; // Binary, Stream, Geography, Geometry — not filterable
        }
        String typeParams = switch (constantType) {
            case "EnumProperty" -> "<" + className + ", " + resolveClassNameForConstant(edmType, owner) + ">";
            case "NumberProperty" -> "<" + className + ", " + getNumberJavaType(resolveTypeDefinition(edmType, owner)) + ">";
            default -> "<" + className + ">";
        };

        String extra = "";
        if (constantType.equals("EnumProperty")) {
            extra = ", " + resolveClassNameForConstant(edmType, owner) + ".class, \"" + Names.escapeJavaString(qualifiedEdmName(resolveTypeDefinition(edmType, owner), owner)) + "\"";
        } else if (constantType.equals("NumberProperty") || constantType.equals("DateTimeProperty")) {
            extra = ", \"" + Names.escapeJavaString(resolveTypeDefinition(edmType, owner)) + "\"";
        }
        return "    public final " + constantType + typeParams + " " + constantName
                + " = new " + constantType + "<>(\"x/" + Names.escapeJavaString(prop.name()) + "\", " + className + ".class"
                + extra
                + ");\n";
    }

    protected String generateFilterableNavField(NavigationPropertyModel nav, String className, SchemaModel schema) {
        SchemaModel owner = schemaForNavigation(nav, schema);
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = refFor(resolveTypeDefinition(unwrapped, owner), owner);
        // must go through the per-type allocation like every other emission site —
        // the raw name collides with a property constant when e.g. prop BUDGET + nav budget
        String constantName = constantNameFor(nav.name());
        // wildcard Sel: Filterable fields serve any/all only, never selector lambdas
        return "    public final NavCollectionProperty<" + className + ", "
                + elementClassName + ", " + elementClassName + ".Filterable, ?> " + constantName
                + " = new NavCollectionProperty<>(\"x/" + Names.escapeJavaString(nav.name()) + "\", " + className + ".class, "
                + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
    }

    protected String collectionElementEdmTypeLiteral(String collectionType, SchemaModel schema) {
        if (!Names.isCollectionType(collectionType)) {
            return "null";
        }
        String element = Names.unwrapCollectionType(collectionType);
        String resolved = resolveTypeDefinition(element, schema);
        if (Names.isPrimitiveType(resolved)) {
            return "\"" + Names.escapeJavaString(resolved) + "\"";
        }
        if (isEnumType(resolved, schema)) {
            return "\"" + Names.escapeJavaString(qualifiedEdmName(resolved, schema)) + "\"";
        }
        return "null";
    }

    protected String generatedClassName(String edmType, SchemaModel schema) {
        return Names.resolvedClassName(resolveTypeDefinition(edmType, schema), effectiveSchemas);
    }

    protected String generatedEntityClassName(String edmType, SchemaModel schema) {
        return generatedClassName(edmType, schema);
    }

    protected String generatedComplexClassName(String edmType, SchemaModel schema) {
        return generatedClassName(edmType, schema);
    }

    protected String generatedEnumClassName(String edmType, SchemaModel schema) {
        return generatedClassName(edmType, schema);
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
        return resolveTypeKind(edmType, schema) == Names.TypeKind.ENUM;
    }
}
