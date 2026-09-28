package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RequestGenerator extends AbstractTypeGenerator {

    private Map<String, EntityTypeModel> entityTypeMap;
    private Map<String, EntityTypeModel> entityTypeByQualifiedName;
    private Map<String, List<EntityTypeModel>> entitySimpleNameIndex;
    private SchemaModel cachedLocalSchema;
    private List<SchemaModel> cachedEffectiveSchemas;
    private java.util.IdentityHashMap<EntityTypeModel, SchemaModel> entityOwners;
    /**
     * Shared bound-operation resolver: boundOperationsFor caches per instance
     * (ancestor chains, bound index, entity index), so constructing one per
     * entity request rebuilds them per entity — O(entities) redundant work that
     * profiling put near the top of generation cost. Reused only when this
     * generator was constructed with the full schema list (constant across
     * calls); single-schema test constructions keep per-call behavior.
     */
    private OperationGenerator sharedBoundGen;
    private final Map<SchemaModel, OperationGenerator> singleSchemaBoundGenerators = new IdentityHashMap<>();

    public RequestGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public RequestGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null, List.of());
    }

    public RequestGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage) {
        this(basePackage, schemaPackages, defaultBasePackage, List.of());
    }

    public RequestGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage, List<SchemaModel> allSchemas) {
        this(basePackage, schemaPackages, defaultBasePackage, allSchemas, null);
    }

    public RequestGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage,
                            List<SchemaModel> allSchemas, OperationGenerator operationGenerator) {
        super(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        this.sharedBoundGen = operationGenerator;
    }

    public String generateEntityRequest(EntityTypeModel entityType, SchemaModel schema) {
        initEffectiveSchemas(schema);
        ensureSchemaCache(schema);
        validateKeyProperties("entity request for '" + entityType.name() + "'", resolvedKeys(entityType, schema),
                resolvedProperties(entityType), schema);
        Map<PropertyModel, StreamMethodNames> streamMethodNames = streamMethodNames(entityType);
        checkEntityRequestMethodCollisions(entityType, resolvedNavs(entityType), schema, streamMethodNames);
        String pkg = basePackage + Names.packageNameSuffixEntityRequest();
        String className = Names.entityRequestClassName(entityType.name());
        String entityClassName = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.client.EntityOperations");
        imports.add(basePackage + Names.packageNameSuffixSchema() + "." + Names.schemaInfoClassName());
        imports.add("io.github.akbarhusain.odata.runtime.exception.ODataException");
        imports.add("io.github.akbarhusain.odata.runtime.query.*");
        imports.add("io.github.akbarhusain.odata.runtime.batch.BatchOperation");
        imports.add("java.io.InputStream");
        imports.add(basePackage + Names.packageNameSuffixEntity() + "." + entityClassName);

        // Two schemas may declare same-named entities in different output packages;
        // contested request-class simple names are referenced fully-qualified, never imported
        List<String> refCandidates = new ArrayList<>();
        List<String[]> navFqns = new ArrayList<>();
        for (NavigationPropertyModel nav : resolvedNavs(entityType)) {
            if (isNonEntityNav(nav, schema)) continue;
            // Resolve TypeDefinition chains: the typedef itself has no generated request
            // class — references must use the underlying type's name
            SchemaModel owner = schemaForNavigation(nav, schema);
            String elementType = resolveTypeDefinition(Names.unwrapCollectionType(nav.type()), owner);
            requireKnownType(Names.unwrapCollectionType(nav.type()), owner,
                    "entity request for '" + entityType.name() + "'", "navigation property '" + nav.name() + "'");
            String elementClassName = generatedEntityClassName(elementType, owner);
            String collFqn = basePackageForType(elementType, owner)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(elementClassName);
            String entFqn = basePackageForType(elementType, owner)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(elementClassName);
            boolean keyable = false;
            if (Names.isCollectionType(nav.type())) {
                EntityTypeModel navTarget = resolveEntityType(elementType, owner);
                keyable = navTarget != null && !keyParamSpecs(navTarget, owner).isEmpty();
            }
            navFqns.add(new String[]{nav.name(), String.valueOf(Names.isCollectionType(nav.type())),
                    collFqn, entFqn, String.valueOf(keyable)});
            refCandidates.add(Names.isCollectionType(nav.type()) ? collFqn : entFqn);
            if (keyable) {
                refCandidates.add(entFqn);
            }
        }
        this.typeRefs = TypeRefs.resolve(refCandidates);
        for (String[] navFqn : navFqns) {
            boolean isCollection = Boolean.parseBoolean(navFqn[1]);
            boolean keyable = Boolean.parseBoolean(navFqn[4]);
            if (isCollection) {
                if (!isContestedFqn(navFqn[2])) {
                    imports.add(navFqn[2]);
                }
                if (keyable && !isContestedFqn(navFqn[3])) {
                    imports.add(navFqn[3]);
                }
            } else if (!isContestedFqn(navFqn[3])) {
                imports.add(navFqn[3]);
            }
        }

        // Bound operations (decision 96): accessors embed on the entity request; the op
        // request classes live in the owning schema's .operation package
        OperationGenerator boundGen = boundGeneratorFor(schema);
        List<OperationGenerator.BoundOp> boundOps = boundGen.boundOperationsFor(entityType, schema);
        List<String> boundAccessors = new ArrayList<>();
        for (OperationGenerator.BoundOp b : boundOps) {
            boundAccessors.add(boundGen.boundAccessorMethod(b));
            String boundClassReference = boundGen.boundClassReference(b);
            if (!boundClassReference.contains(".")) {
                imports.add(boundGen.boundClassImportLine(b));
            }
            boundGen.collectParameterImports(b.parameters(), b.owner(), imports);
        }
        checkBoundRequestMethodCollisions(entityType, boundOps, schema, streamMethodNames);

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private final Context context;\n");
        sb.append("    private final ContextPath contextPath;\n");
        sb.append("    private final java.util.List<String> selects = new java.util.ArrayList<>();\n");
        sb.append("    private final java.util.List<String> expands = new java.util.ArrayList<>();\n\n");
        sb.append("    public ").append(className).append("(Context context, ContextPath contextPath) {\n");
        sb.append("        this.context = context;\n");
        sb.append("        this.contextPath = contextPath;\n");
        sb.append("    }\n\n");

        // Read-shaping options for the single-entity GET: $select/$expand are the query
        // options valid there (filter/top/skip/orderby apply to collections only).
        // Chaining mirrors the collection request: copy() snapshots state, methods
        // mutate the copy — the source request is untouched
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" select(PropertyExpression<? super ").append(entityClassName).append(", ?>... properties) {\n");
        sb.append("        java.util.Objects.requireNonNull(properties, \"select properties must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (int i = 0; i < properties.length; i++) {\n");
        sb.append("            var p = java.util.Objects.requireNonNull(properties[i], \"select properties[\" + i + \"] must not be null\");\n");
        sb.append("            String name = p.getEdmName();\n");
        sb.append("            if (name.contains(\"(\")) {\n");
        sb.append("                throw new IllegalArgumentException(\"'\" + name + \"' is not a selectable property \"\n");
        sb.append("                        + \"(select accepts property paths only; function transformations belong in filter or compute)\");\n");
        sb.append("            }\n");
        sb.append("            next.selects.add(name);\n");
        sb.append("        }\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" select(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends PropertyExpression<? super ").append(entityClassName).append(", ?>>... selectors) {\n");
        sb.append("        java.util.Objects.requireNonNull(selectors, \"select selectors must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        PropertyExpression<? super ").append(entityClassName).append(", ?>[] resolved = new PropertyExpression[selectors.length];\n");
        sb.append("        for (int i = 0; i < selectors.length; i++) {\n");
        sb.append("            java.util.Objects.requireNonNull(selectors[i], \"select selectors[\" + i + \"] must not be null\");\n");
        sb.append("            resolved[i] = java.util.Objects.requireNonNull(selectors[i].apply(s), \"select selectors[\" + i + \"] must not return null\");\n");
        sb.append("        }\n");
        sb.append("        return select(resolved);\n");
        sb.append("    }\n\n");

        // zero-arg bridge: select() matched one varargs overload before the lambda form
        // existed; with both present it would be ambiguous
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    public final ").append(className).append(" select() {\n");
        sb.append("        return select(new PropertyExpression[0]);\n");
        sb.append("    }\n\n");

        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" expand(Expandable<? super ").append(entityClassName).append(">... expandables) {\n");
        sb.append("        java.util.Objects.requireNonNull(expandables, \"expand expandables must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (int i = 0; i < expandables.length; i++) {\n");
        sb.append("            var e = java.util.Objects.requireNonNull(expandables[i], \"expand expandables[\" + i + \"] must not be null\");\n");
        sb.append("            String rendered = e.toODataExpand();\n");
        sb.append("            if (!next.expands.contains(rendered)) next.expands.add(rendered);\n");
        sb.append("        }\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public final ").append(className).append(" expand(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends Expandable<? super ").append(entityClassName).append(">> query) {\n");
        sb.append("        java.util.Objects.requireNonNull(query, \"expand query must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        return expand(java.util.Objects.requireNonNull(query.apply(s), \"expand query must not return null\"));\n");
        sb.append("    }\n\n");

        sb.append("    public ContextPath buildContext() {\n");
        sb.append("        ContextPath ctx = contextPath;\n");
        sb.append("        if (!selects.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$select\", String.join(\",\", selects));\n");
        sb.append("        }\n");
        sb.append("        if (!expands.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$expand\", String.join(\",\", expands));\n");
        sb.append("        }\n");
        sb.append("        return ctx;\n");
        sb.append("    }\n\n");

        sb.append("    private ").append(className).append(" copy() {\n");
        sb.append("        ").append(className).append(" c = new ").append(className).append("(context, contextPath);\n");
        sb.append("        c.selects.addAll(selects);\n");
        sb.append("        c.expands.addAll(expands);\n");
        sb.append("        return c;\n");
        sb.append("    }\n\n");

        // Navigation property methods — only for entity nav targets (complex types are inline data, not navigable).
        // Inherited navs included: request classes don't extend each other, so the base's
        // nav methods must be emitted on the subtype's request too
        for (NavigationPropertyModel nav : resolvedNavs(entityType)) {
            if (isNonEntityNav(nav, schema)) continue;
            sb.append(generateNavMethod(nav, schema));
        }

        // Bound operation accessors (decision 96)
        for (String accessor : boundAccessors) {
            sb.append(accessor);
        }

        // $ref methods for collection navigation properties — only for entity nav targets
        // (inherited included); containment navs (ContainsTarget) manage contained entities
        // through the containment path, and $ref link operations are not defined for them
        for (NavigationPropertyModel nav : resolvedNavs(entityType)) {
            if (isNonEntityNav(nav, schema)) continue;
            if (nav.containsTarget()) continue;
            if (Names.isCollectionType(nav.type())) {
                String refBase = Names.toJavaFieldName(nav.name());
                sb.append("    public void add").append(Names.capitalize(refBase)).append("Ref(String targetEntityUrl) {\n");
                sb.append("        EntityOperations.addRef(context, contextPath.addSegment(\"").append(Names.escapeJavaString(nav.name())).append("\"), targetEntityUrl);\n");
                sb.append("    }\n\n");

                sb.append("    public void remove").append(Names.capitalize(refBase)).append("Ref(String targetKey) {\n");
                sb.append("        EntityOperations.removeRef(context, contextPath.addSegment(\"").append(Names.escapeJavaString(nav.name())).append("\"), targetKey);\n");
                sb.append("    }\n\n");
            }
        }

        // CRUD operations
        sb.append("    public ").append(entityClassName).append(" get() {\n");
        sb.append("        return EntityOperations.executeAndGetEntity(context, buildContext(), ").append(entityClassName).append(".class, " + Names.schemaInfoClassName() + ".INSTANCE);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" patch(").append(entityClassName).append(" entity) {\n");
        sb.append("        return EntityOperations.executePatchEntity(context, contextPath, entity, ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" patchWithETag(").append(entityClassName).append(" entity, String etag) {\n");
        sb.append("        return EntityOperations.executePatchEntityWithETag(context, contextPath, entity, ").append(entityClassName).append(".class, etag);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" put(").append(entityClassName).append(" entity) {\n");
        sb.append("        return EntityOperations.executePutEntity(context, contextPath, entity, ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" putWithETag(").append(entityClassName).append(" entity, String etag) {\n");
        sb.append("        return EntityOperations.executePutEntityWithETag(context, contextPath, entity, ").append(entityClassName).append(".class, etag);\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation putToBatchOperation(").append(entityClassName).append(" entity) {\n");
        sb.append("        byte[] body = context.serializer().serialize(entity, ").append(entityClassName).append(".class);\n");
        sb.append("        return BatchOperation.put(contextPath.toRelativeUrl(), body);\n");
        sb.append("    }\n\n");

        sb.append("    public void delete() {\n");
        sb.append("        EntityOperations.executeDelete(context, contextPath);\n");
        sb.append("    }\n\n");

        sb.append("    public void deleteWithETag(String etag) {\n");
        sb.append("        EntityOperations.executeDeleteWithETag(context, contextPath, etag);\n");
        sb.append("    }\n\n");

        // Media stream access — the entity itself is a media stream (HasStream="true") at $value
        if (resolvedHasStream(entityType)) {
            sb.append("    public java.io.InputStream streamMedia() {\n");
            sb.append("        return EntityOperations.streamMedia(context, contextPath.addSegment(\"$value\"));\n");
            sb.append("    }\n\n");

            sb.append("    public void setMedia(java.io.InputStream content) {\n");
            sb.append("        setMedia(content, null);\n");
            sb.append("    }\n\n");

            sb.append("    public void setMedia(java.io.InputStream content, String etag) {\n");
            sb.append("        try {\n");
            sb.append("            byte[] bytes = content.readAllBytes();\n");
            sb.append("            EntityOperations.putMedia(context, contextPath.addSegment(\"$value\"), bytes, \"application/octet-stream\", etag);\n");
            sb.append("        } catch (java.io.IOException e) {\n");
            sb.append("            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(\"Failed to read media stream: \" + e.getMessage(), e);\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }

        // Named stream properties (Edm.Stream) — stream lives at <property>/$value
        for (PropertyModel prop : resolvedStreamProps(entityType)) {
            if ("Edm.Stream".equals(prop.edmType())) {
                StreamMethodNames names = streamMethodNames.get(prop);
                String streamMethod = names.stream();
                String setMethod = names.set();
                sb.append("    public java.io.InputStream ").append(streamMethod).append("() {\n");
                sb.append("        return EntityOperations.streamMedia(context, contextPath.addSegment(\"")
                  .append(Names.escapeJavaString(prop.name())).append("\"));\n");
                sb.append("    }\n\n");

                sb.append("    public void ").append(setMethod).append("(java.io.InputStream content) {\n");
                sb.append("        ").append(setMethod).append("(content, null);\n");
                sb.append("    }\n\n");

                sb.append("    public void ").append(setMethod).append("(java.io.InputStream content, String etag) {\n");
                sb.append("        try {\n");
                sb.append("            byte[] bytes = content.readAllBytes();\n");
                sb.append("            EntityOperations.putMedia(context, contextPath.addSegment(\"")
                  .append(Names.escapeJavaString(prop.name())).append("\"), bytes, \"application/octet-stream\", etag);\n");
                sb.append("        } catch (java.io.IOException e) {\n");
                sb.append("            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(\"Failed to read media stream: \" + e.getMessage(), e);\n");
                sb.append("        }\n");
                sb.append("    }\n\n");
            }
        }

        // Batch methods — a batch view must mean the same thing as the direct call:
        // GET carries the request's $select/$expand (buildContext, like get()), and
        // PATCH sends only the tracked changes (decision 51, like patch())
        sb.append("    public BatchOperation toBatchOperation() {\n");
        sb.append("        return BatchOperation.get(buildContext().toRelativeUrl());\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation patchToBatchOperation(").append(entityClassName).append(" entity) {\n");
        sb.append("        return patchToBatchOperation(entity, null);\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation patchToBatchOperation(").append(entityClassName).append(" entity, String etag) {\n");
        sb.append("        java.util.Set<String> changed = entity.getChangedFields();\n");
        sb.append("        byte[] body = changed != null && !changed.isEmpty()\n");
        sb.append("                ? context.serializer().serialize(entity, ").append(entityClassName).append(".class, changed)\n");
        sb.append("                : context.serializer().serialize(entity, ").append(entityClassName).append(".class);\n");
        sb.append("        return BatchOperation.patch(contextPath.toRelativeUrl(), body, etag);\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation deleteToBatchOperation() {\n");
        sb.append("        return BatchOperation.delete(contextPath.toRelativeUrl());\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    public String generateCollectionRequest(EntityTypeModel entityType, SchemaModel schema) {
        initEffectiveSchemas(schema);
        ensureSchemaCache(schema);
        validateKeyProperties("collection request for '" + entityType.name() + "'", resolvedKeys(entityType, schema),
                resolvedProperties(entityType), schema);
        String pkg = basePackage + Names.packageNameSuffixCollectionRequest();
        String className = Names.collectionRequestClassName(entityType.name());
        String entityClassName = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.List");
        imports.add("java.util.stream.Stream");
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.client.EntityOperations");
        imports.add(basePackage + Names.packageNameSuffixSchema() + "." + Names.schemaInfoClassName());
        imports.add("io.github.akbarhusain.odata.runtime.query.*");
        imports.add("io.github.akbarhusain.odata.runtime.paging.CollectionPage");
        imports.add("io.github.akbarhusain.odata.runtime.batch.BatchOperation");
        imports.add(basePackage + Names.packageNameSuffixEntity() + "." + entityClassName);
        imports.add(basePackage + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(entityType.name()));

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private final Context context;\n");
        sb.append("    private final ContextPath contextPath;\n");
        sb.append("    private final java.util.List<String> filters = new java.util.ArrayList<>();\n");
        sb.append("    private final java.util.List<String> selects = new java.util.ArrayList<>();\n");
        sb.append("    private final java.util.List<String> expands = new java.util.ArrayList<>();\n");
        sb.append("    private final java.util.List<String> orderings = new java.util.ArrayList<>();\n");
        sb.append("    private Integer topValue;\n");
        sb.append("    private Integer skipValue;\n");
        sb.append("    private boolean countRequested;\n");
        sb.append("    private String searchTerm;\n");
        sb.append("    private String applyExpr;\n\n");

        sb.append("    public ").append(className).append("(Context context, ContextPath contextPath) {\n");
        sb.append("        this.context = context;\n");
        sb.append("        this.contextPath = contextPath;\n");
        sb.append("    }\n\n");

        // Type-safe filter
        sb.append("    public ").append(className).append(" filter(FilterExpression<? super ").append(entityClassName).append("> predicate) {\n");
        sb.append("        java.util.Objects.requireNonNull(predicate, \"filter predicate must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.filters.add(predicate.toODataExpression());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" filter(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends FilterExpression<? super ").append(entityClassName).append(">> predicate) {\n");
        sb.append("        java.util.Objects.requireNonNull(predicate, \"filter function must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        return filter(java.util.Objects.requireNonNull(predicate.apply(s), \"filter function must not return null\"));\n");
        sb.append("    }\n\n");

        // Type-safe select
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" select(PropertyExpression<? super ").append(entityClassName).append(", ?>... properties) {\n");
        sb.append("        java.util.Objects.requireNonNull(properties, \"select properties must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (int i = 0; i < properties.length; i++) {\n");
        sb.append("            var p = java.util.Objects.requireNonNull(properties[i], \"select properties[\" + i + \"] must not be null\");\n");
        sb.append("            String name = p.getEdmName();\n");
        sb.append("            if (name.indexOf('(') >= 0) {\n");
        sb.append("                throw new IllegalArgumentException(\"'\" + name + \"' is not a selectable property \"\n");
        sb.append("                        + \"(select accepts property paths only; function transformations belong in filter or compute)\");\n");
        sb.append("            }\n");
        sb.append("            next.selects.add(name);\n");
        sb.append("        }\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" select(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends PropertyExpression<? super ").append(entityClassName).append(", ?>>... selectors) {\n");
        sb.append("        java.util.Objects.requireNonNull(selectors, \"select selectors must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        PropertyExpression<? super ").append(entityClassName).append(", ?>[] resolved = new PropertyExpression[selectors.length];\n");
        sb.append("        for (int i = 0; i < selectors.length; i++) {\n");
        sb.append("            java.util.Objects.requireNonNull(selectors[i], \"select selectors[\" + i + \"] must not be null\");\n");
        sb.append("            resolved[i] = java.util.Objects.requireNonNull(selectors[i].apply(s), \"select selectors[\" + i + \"] must not return null\");\n");
        sb.append("        }\n");
        sb.append("        return select(resolved);\n");
        sb.append("    }\n\n");

        // zero-arg bridge: select() matched one varargs overload before the lambda form
        // existed; with both present it would be ambiguous
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    public final ").append(className).append(" select() {\n");
        sb.append("        return select(new PropertyExpression[0]);\n");
        sb.append("    }\n\n");

        // Type-safe expand: one constant form over the sealed Expandable set
        // (bare navigations render the plain segment, queries render their options)
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" expand(Expandable<? super ").append(entityClassName).append(">... expandables) {\n");
        sb.append("        java.util.Objects.requireNonNull(expandables, \"expand expandables must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (int i = 0; i < expandables.length; i++) {\n");
        sb.append("            var e = java.util.Objects.requireNonNull(expandables[i], \"expand expandables[\" + i + \"] must not be null\");\n");
        sb.append("            String rendered = e.toODataExpand();\n");
        sb.append("            if (!next.expands.contains(rendered)) next.expands.add(rendered);\n");
        sb.append("        }\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public final ").append(className).append(" expand(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends Expandable<? super ").append(entityClassName).append(">> query) {\n");
        sb.append("        java.util.Objects.requireNonNull(query, \"expand query must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        return expand(java.util.Objects.requireNonNull(query.apply(s), \"expand query must not return null\"));\n");
        sb.append("    }\n\n");

        // Type-safe orderBy
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" orderBy(OrderExpression<? super ").append(entityClassName).append(", ?>... expressions) {\n");
        sb.append("        java.util.Objects.requireNonNull(expressions, \"orderBy expressions must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (int i = 0; i < expressions.length; i++) {\n");
        sb.append("            var e = java.util.Objects.requireNonNull(expressions[i], \"orderBy expressions[\" + i + \"] must not be null\");\n");
        sb.append("            next.orderings.add(e.getODataPath());\n");
        sb.append("        }\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    @SafeVarargs\n");
        sb.append("    public final ").append(className).append(" orderBy(java.util.function.Function<").append(entityClassName).append(".Selector, ? extends OrderExpression<? super ").append(entityClassName).append(", ?>>... expressions) {\n");
        sb.append("        java.util.Objects.requireNonNull(expressions, \"orderBy expressions must not be null\");\n");
        sb.append("        ").append(entityClassName).append(".Selector s = new ").append(entityClassName).append(".Selector();\n");
        sb.append("        OrderExpression<? super ").append(entityClassName).append(", ?>[] resolved = new OrderExpression[expressions.length];\n");
        sb.append("        for (int i = 0; i < expressions.length; i++) {\n");
        sb.append("            java.util.Objects.requireNonNull(expressions[i], \"orderBy expressions[\" + i + \"] must not be null\");\n");
        sb.append("            resolved[i] = java.util.Objects.requireNonNull(expressions[i].apply(s), \"orderBy expressions[\" + i + \"] must not return null\");\n");
        sb.append("        }\n");
        sb.append("        return orderBy(resolved);\n");
        sb.append("    }\n\n");

        // zero-arg bridge (same rationale as select())
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    public final ").append(className).append(" orderBy() {\n");
        sb.append("        return orderBy(new OrderExpression[0]);\n");
        sb.append("    }\n\n");

        // top, skip, count, search
        // $top/$skip must be >= 0 — negative values render invalid OData (parity with
        // NavQuery/ApplyBuilder, which already reject them)
        sb.append("    public ").append(className).append(" top(int count) {\n");
        sb.append("        if (count < 0) {\n");
        sb.append("            throw new IllegalArgumentException(\"top must be >= 0, got: \" + count);\n");
        sb.append("        }\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.topValue = count;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" skip(int count) {\n");
        sb.append("        if (count < 0) {\n");
        sb.append("            throw new IllegalArgumentException(\"skip must be >= 0, got: \" + count);\n");
        sb.append("        }\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.skipValue = count;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" count() {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.countRequested = true;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" search(String term) {\n");
        sb.append("        java.util.Objects.requireNonNull(term, \"search term must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.searchTerm = term;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // $apply (aggregation / transformations, including $compute)
        sb.append("    public ").append(className).append(" apply(ApplyExpression expr) {\n");
        sb.append("        java.util.Objects.requireNonNull(expr, \"apply expression must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.applyExpr = expr.toODataApply();\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" apply(String raw) {\n");
        sb.append("        java.util.Objects.requireNonNull(raw, \"apply raw must not be null\");\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.applyExpr = ApplyExpression.of(raw).toODataApply();\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Execution methods
        sb.append("    public ContextPath buildContext() {\n");
        sb.append("        ContextPath ctx = contextPath;\n");
        sb.append("        if (!filters.isEmpty()) {\n");
        // Chained filter() calls are ANDed. Parenthesize each predicate (except a lone
        // one) so 'or' inside a predicate cannot bind across the implicit 'and' —
        // same rule as NavQuery.toODataExpand()
        sb.append("            String joinedFilter = filters.size() == 1\n");
        sb.append("                    ? filters.get(0)\n");
        sb.append("                    : filters.stream().map(f -> \"(\" + f + \")\").collect(java.util.stream.Collectors.joining(\" and \"));\n");
        sb.append("            ctx = ctx.addQuery(\"$filter\", joinedFilter);\n");
        sb.append("        }\n");
        sb.append("        if (!selects.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$select\", String.join(\",\", selects));\n");
        sb.append("        }\n");
        sb.append("        if (!expands.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$expand\", String.join(\",\", expands));\n");
        sb.append("        }\n");
        sb.append("        if (!orderings.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$orderby\", String.join(\",\", orderings));\n");
        sb.append("        }\n");
        sb.append("        if (topValue != null) {\n");
        sb.append("            ctx = ctx.addQuery(\"$top\", String.valueOf(topValue));\n");
        sb.append("        }\n");
        sb.append("        if (skipValue != null) {\n");
        sb.append("            ctx = ctx.addQuery(\"$skip\", String.valueOf(skipValue));\n");
        sb.append("        }\n");
        sb.append("        if (countRequested) {\n");
        sb.append("            ctx = ctx.addQuery(\"$count\", \"true\");\n");
        sb.append("        }\n");
        sb.append("        if (searchTerm != null) {\n");
        sb.append("            ctx = ctx.addQuery(\"$search\", searchTerm);\n");
        sb.append("        }\n");
        sb.append("        if (applyExpr != null) {\n");
        sb.append("            ctx = ctx.addQuery(\"$apply\", applyExpr);\n");
        sb.append("        }\n");
        sb.append("        return ctx;\n");
        sb.append("    }\n\n");

        sb.append("    public CollectionPage<").append(entityClassName).append("> get() {\n");
        sb.append("        return EntityOperations.executeAndGetCollection(context, buildContext(), ").append(entityClassName).append(".class, " + Names.schemaInfoClassName() + ".INSTANCE);\n");
        sb.append("    }\n\n");

        sb.append("    public Stream<").append(entityClassName).append("> stream() {\n");
        sb.append("        return get().stream();\n");
        sb.append("    }\n\n");

        sb.append("    public List<").append(entityClassName).append("> toList() {\n");
        sb.append("        return get().toList();\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" create(").append(entityClassName).append(" entity) {\n");
        sb.append("        return EntityOperations.executePostEntity(context, contextPath, entity, ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation toBatchOperation() {\n");
        sb.append("        return BatchOperation.get(buildContext().toRelativeUrl());\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation postToBatchOperation(").append(entityClassName).append(" entity) {\n");
        sb.append("        byte[] body = context.serializer().serialize(entity, ").append(entityClassName).append(".class);\n");
        sb.append("        return BatchOperation.post(contextPath.toRelativeUrl(), body);\n");
        sb.append("    }\n\n");

        // Pagination helper: fetch the next page from an @odata.nextLink URL
        sb.append("    public ").append(className).append(" nextPage(String nextLink) {\n");
        sb.append("        return new ").append(className).append("(context, contextPath.fromNextLink(nextLink));\n");
        sb.append("    }\n\n");

        // Direct $count endpoint helper (GET /EntitySet/$count)
        sb.append("    public long countValue() {\n");
        sb.append("        if (applyExpr != null) {\n");
        sb.append("            throw new IllegalArgumentException(\"countValue cannot be combined with $apply\");\n");
        sb.append("        }\n");
        sb.append("        ").append(className).append(" tmp = copy();\n");
        sb.append("        tmp.countRequested = false;\n");
        sb.append("        tmp.topValue = null;\n");
        sb.append("        tmp.skipValue = null;\n");
        // OData 4.01 Part 2 §5.1.2: /$count allows only $filter and $search;
        // $apply/$select/$expand/$orderby are invalid there (applyExpr is rejected above)
        sb.append("        tmp.selects.clear();\n");
        sb.append("        tmp.expands.clear();\n");
        sb.append("        tmp.orderings.clear();\n");
        sb.append("        return EntityOperations.executeCount(context, tmp.buildContext());\n");
        sb.append("    }\n\n");

        // Keying lives on keyed container overloads and keyed nav overloads
        // (decision 95, option A) — the collection-request byID/byKey accessor
        // family was removed (breaking, pre-1.0).

        // copy()
        sb.append("    private ").append(className).append(" copy() {\n");
        sb.append("        ").append(className).append(" c = new ").append(className).append("(context, contextPath);\n");
        sb.append("        c.filters.addAll(filters);\n");
        sb.append("        c.selects.addAll(selects);\n");
        sb.append("        c.expands.addAll(expands);\n");
        sb.append("        c.orderings.addAll(orderings);\n");
        sb.append("        c.topValue = topValue;\n");
        sb.append("        c.skipValue = skipValue;\n");
        sb.append("        c.countRequested = countRequested;\n");
        sb.append("        c.searchTerm = searchTerm;\n");
        sb.append("        c.applyExpr = applyExpr;\n");
        sb.append("        return c;\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private void ensureSchemaCache(SchemaModel schema) {
        if (cachedEffectiveSchemas != effectiveSchemas) {
            entityTypeByQualifiedName = null;
            entitySimpleNameIndex = null;
            entityOwners = null;
            entityTypeMap = null;
            cachedLocalSchema = null;
            cachedEffectiveSchemas = effectiveSchemas;
        }
        if (entityTypeByQualifiedName == null) {
            Map<String, EntityTypeModel> crossSchemaMap = new HashMap<>();
            entitySimpleNameIndex = new HashMap<>();
            entityOwners = new java.util.IdentityHashMap<>();
            for (SchemaModel s : effectiveSchemas) {
                for (EntityTypeModel et : s.entityTypes()) {
                    String qn = s.namespace() + "." + et.name();
                    crossSchemaMap.put(qn, et);
                    entitySimpleNameIndex.computeIfAbsent(et.name(), ignored -> new ArrayList<>()).add(et);
                    entityOwners.put(et, s);
                }
            }
            entityTypeByQualifiedName = crossSchemaMap;
        }
        if (cachedLocalSchema == schema) {
            return;
        }
        entityTypeMap = new HashMap<>();
        for (SchemaModel s : effectiveSchemas) {
            if (!s.namespace().equals(schema.namespace())) {
                continue;
            }
            for (EntityTypeModel et : s.entityTypes()) {
                entityTypeMap.put(Names.entityClassName(et.name()), et);
            }
        }
        cachedLocalSchema = schema;
    }

    public String requireKnownTypeForGeneration(String type, SchemaModel schema, String owner, String member) {
        initEffectiveSchemas(schema);
        return requireKnownType(type, schema, owner, member);
    }

    public String resolvedTypeForGeneration(String type, SchemaModel schema) {
        initEffectiveSchemas(schema);
        return resolveTypeDefinition(type, schema);
    }

    public String entityClassNameForType(String type, SchemaModel schema) {
        return generatedEntityClassName(resolvedTypeForGeneration(type, schema), schema);
    }

    public String collectionRequestClassNameForType(String type, SchemaModel schema) {
        return Names.collectionRequestClassName(entityClassNameForType(type, schema));
    }

    public String entityRequestClassNameForType(String type, SchemaModel schema) {
        return Names.entityRequestClassName(entityClassNameForType(type, schema));
    }

    /** One typed key parameter: CSDL name, Java identifier, Java type, resolved Edm type. */
    public record KeyParamSpec(String csdlName, String javaParamName, String javaType, String edmType) {}

    /** Resolves an entity-set/singleton type reference to its model (qualified, then same-schema simple name). */
    public EntityTypeModel resolveEntityType(String typeRef, SchemaModel schema) {
        initEffectiveSchemas(schema);
        ensureSchemaCache(schema);
        EntityTypeModel hit = entityTypeByQualifiedName.get(typeRef);
        if (hit != null) return hit;
        if (!typeRef.contains(".")) {
            return entityTypeMap.get(Names.entityClassName(typeRef));
        }
        return null;
    }

    /**
     * Key parameters of the entity type (own + inherited), flattened in CSDL order:
     * one entry per {@code PropertyRef}. Empty for keyless types. Shared by keyed
     * container overloads, keyed nav overloads, and (previously) the byID accessors.
     */
    public java.util.List<KeyParamSpec> keyParamSpecs(EntityTypeModel entityType, SchemaModel schema) {
        initEffectiveSchemas(schema);
        validateKeyProperties("entity '" + entityType.name() + "'", resolvedKeys(entityType, schema),
                resolvedProperties(entityType), schema);
        java.util.List<KeyParamSpec> out = new java.util.ArrayList<>();
        for (KeyModel key : resolvedKeys(entityType, schema)) {
            for (String keyProp : key.propertyRefs()) {
                out.add(new KeyParamSpec(keyProp, Names.toJavaFieldName(keyProp),
                        resolveKeyType(entityType, keyProp, schema),
                        keyEdmType(entityType, keyProp, schema)));
            }
        }
        return out;
    }

    private String resolveKeyType(EntityTypeModel entityType, String keyPropName, SchemaModel schema) {
        return resolveKeyType(entityType, keyPropName, schema, newVisiting());
    }

    private String resolveKeyType(EntityTypeModel entityType, String keyPropName, SchemaModel schema, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        for (PropertyModel prop : entityType.properties()) {
            if (prop.name().equals(keyPropName)) {
                return keyJavaType(prop.edmType(), schemaForProperty(prop, schema));
            }
        }
        EntityTypeModel base = findBase(entityType);
        if (base != null) {
            return resolveKeyType(base, keyPropName, schema, visiting);
        }
        return "Object";
    }

    // P1-6: map an OData key property to its Java parameter type (handles inherited + TypeDefinition keys)
    private String keyJavaType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isStringType(resolved)) return "String";
        if (Names.isNumericType(resolved)) return Names.edmTypeToSimpleJavaType(resolved);
        if (Names.isBooleanType(resolved)) return "Boolean";
        if (Names.isPrimitiveType(resolved)) return Names.edmTypeToSimpleJavaType(resolved);
        if (resolveTypeKind(resolved, schema) == Names.TypeKind.ENUM) return typeFqnOf(resolved, schema);
        return "Object";
    }

    /** The RESOLVED Edm type of a key property (typedefs unwrapped), for typed key literals. */
    private String keyEdmType(EntityTypeModel entityType, String keyPropName, SchemaModel schema) {
        return keyEdmType(entityType, keyPropName, schema, newVisiting());
    }

    private String keyEdmType(EntityTypeModel entityType, String keyPropName, SchemaModel schema, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        for (PropertyModel prop : entityType.properties()) {
            if (prop.name().equals(keyPropName)) {
                return resolveTypeDefinition(prop.edmType(), schemaForProperty(prop, schema));
            }
        }
        EntityTypeModel base = findBase(entityType);
        if (base != null) {
            return keyEdmType(base, keyPropName, schema, visiting);
        }
        return "Edm.String";
    }

    private java.util.List<KeyModel> resolvedKeys(EntityTypeModel entityType, SchemaModel schema) {
        return resolvedKeys(entityType, schema, newVisiting());
    }

    private java.util.List<KeyModel> resolvedKeys(EntityTypeModel entityType, SchemaModel schema, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        if (!entityType.keys().isEmpty()) {
            return entityType.keys();
        }
        EntityTypeModel base = findBase(entityType);
        if (base == null) {
            return java.util.List.of();
        }
        return resolvedKeys(base, schema, visiting);
    }

    private List<PropertyModel> resolvedProperties(EntityTypeModel entityType) {
        return resolvedProperties(entityType, newVisiting());
    }

    private List<PropertyModel> resolvedProperties(EntityTypeModel entityType, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        EntityTypeModel base = findBase(entityType);
        List<PropertyModel> inherited = base == null ? List.of() : resolvedProperties(base, visiting);
        return mergeOwnWinsProps(inherited, entityType.properties());
    }

    /** All navigation properties up the base chain (base-first), for request generation. */
    private java.util.List<NavigationPropertyModel> resolvedNavs(EntityTypeModel entityType) {
        return resolvedNavs(entityType, newVisiting());
    }

    private java.util.List<NavigationPropertyModel> resolvedNavs(EntityTypeModel entityType, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        EntityTypeModel base = findBase(entityType);
        java.util.List<NavigationPropertyModel> inherited =
                base == null ? java.util.List.of() : resolvedNavs(base, visiting);
        // Own-wins merge: a redeclared nav shadows the inherited one (single copy,
        // otherwise nav/keyed-overload/$ref methods emit duplicates)
        return mergeOwnWinsNavs(inherited, entityType.navigationProperties());
    }

    /** CSDL: HasStream="true" on a base type applies to all derived types. */
    private boolean resolvedHasStream(EntityTypeModel entityType) {
        EntityTypeModel t = entityType;
        Set<EntityTypeModel> visiting = newVisiting();
        while (t != null) {
            checkBaseCycle(visiting, t);
            if (t.hasStream()) {
                return true;
            }
            t = findBase(t);
        }
        return false;
    }

    /** Edm.Stream properties up the base chain, for named-stream request methods. */
    private java.util.List<PropertyModel> resolvedStreamProps(EntityTypeModel entityType) {
        return resolvedStreamProps(entityType, newVisiting());
    }

    private java.util.List<PropertyModel> resolvedStreamProps(EntityTypeModel entityType, Set<EntityTypeModel> visiting) {
        checkBaseCycle(visiting, entityType);
        EntityTypeModel base = findBase(entityType);
        java.util.List<PropertyModel> inherited =
                base == null ? java.util.List.of() : resolvedStreamProps(base, visiting);
        java.util.List<PropertyModel> own = new java.util.ArrayList<>();
        for (PropertyModel prop : entityType.properties()) {
            if ("Edm.Stream".equals(prop.edmType())) {
                own.add(prop);
            }
        }
        // Own-wins merge: a redeclared stream prop shadows the inherited one
        return mergeOwnWinsProps(inherited, own);
    }

    private OperationGenerator boundGeneratorFor(SchemaModel schema) {
        if (allSchemas == null || allSchemas.isEmpty()) {
            return singleSchemaBoundGenerators.computeIfAbsent(schema,
                    key -> new OperationGenerator(basePackage, schemaPackages,
                            defaultBasePackage, List.of(key)));
        }
        if (sharedBoundGen == null) {
            sharedBoundGen = new OperationGenerator(basePackage, schemaPackages,
                    defaultBasePackage, allSchemas);
        }
        return sharedBoundGen;
    }

    private EntityTypeModel findBase(EntityTypeModel entityType) {
        String bt = entityType.baseType();
        if (bt == null || bt.isBlank()) return null;
        EntityTypeModel base = entityTypeByQualifiedName.get(bt);
        if (base != null) return base;
        if (!bt.contains(".")) {
            SchemaModel owner = entityOwners.get(entityType);
            if (owner != null) {
                for (EntityTypeModel candidate : owner.entityTypes()) {
                    if (candidate.name().equals(bt)) {
                        return candidate;
                    }
                }
            }
            List<EntityTypeModel> matches = entitySimpleNameIndex.getOrDefault(bt, List.of());
            if (matches.size() == 1) return matches.get(0);
            if (matches.size() > 1) {
                throw new IllegalStateException("Cannot generate entity request for '" + entityType.name()
                        + "': ambiguous unqualified BaseType '" + bt + "'");
            }
        }
        throw new IllegalStateException("Cannot generate entity request for '" + entityType.name()
                + "': unknown BaseType '" + bt + "'");
    }

    /**
     * Identity-keyed visiting set for base-chain walks: models are records (value
     * equality), so two distinct but equal-valued types must NOT compare equal
     * here — only revisiting the SAME object is a cycle.
     */
    private static Set<EntityTypeModel> newVisiting() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void checkBaseCycle(Set<EntityTypeModel> visiting, EntityTypeModel current) {
        if (!visiting.add(current)) {
            throw new IllegalStateException("Circular BaseType chain detected involving entity type: "
                    + current.name() + " (BaseType '" + current.baseType() + "')");
        }
    }

    /** True when the reference for this exact import-candidate FQN is fully-qualified (contested). */
    private boolean isContestedFqn(String fqn) {
        String ref = typeRefs.get(fqn);
        return ref != null && ref.contains(".");
    }

    private String generateNavMethod(NavigationPropertyModel nav, SchemaModel schema) {
        boolean isCollection = Names.isCollectionType(nav.type());
        // Resolve TypeDefinition chains so the emitted request class matches the
        // underlying entity's generated class (a typedef has no request class of its own)
        SchemaModel owner = schemaForNavigation(nav, schema);
        String unwrapped = resolveTypeDefinition(Names.unwrapCollectionType(nav.type()), owner);
        String elementClassName = generatedEntityClassName(unwrapped, owner);
        String methodName = Names.toJavaFieldName(nav.name());

        String collRef = typeRefs.getOrDefault(basePackageForType(unwrapped, owner)
                + Names.packageNameSuffixCollectionRequest() + "."
                + Names.collectionRequestClassName(elementClassName),
                Names.collectionRequestClassName(elementClassName));
        String entRef = typeRefs.getOrDefault(basePackageForType(unwrapped, owner)
                + Names.packageNameSuffixEntityRequest() + "."
                + Names.entityRequestClassName(elementClassName),
                Names.entityRequestClassName(elementClassName));

        StringBuilder sb = new StringBuilder();
        sb.append("    public ");
        if (isCollection) {
            sb.append(collRef).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(collRef)
              .append("(context, contextPath.addSegment(\"").append(Names.escapeJavaString(nav.name())).append("\"));\n");
            sb.append("    }\n\n");

            // Keyed nav overload (decision 95): person.trips(2) renders
            // People('x')/Trips(2) without a tripByID() detour. Only for keyed
            // entity targets — keyless/complex/primitive navs get no overload.
            // The collection method above is fully closed already, so the
            // collection branch ALWAYS returns here — falling through to the
            // shared close would emit a stray '}' (keyless targets)
            EntityTypeModel navTarget = resolveEntityType(unwrapped, owner);
            if (navTarget != null) {
                java.util.List<KeyParamSpec> keySpecs = keyParamSpecs(navTarget, owner);
                if (!keySpecs.isEmpty()) {
                    appendKeyedNavOverload(sb, nav.name(), methodName, entRef, keySpecs);
                }
            }
            return sb.toString();
        } else {
            sb.append(entRef).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(entRef)
              .append("(context, contextPath.addSegment(\"").append(Names.escapeJavaString(nav.name())).append("\"));\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    private static void appendKeyedNavOverload(StringBuilder sb, String rawNavName, String methodName,
                                               String entityReqClass,
                                               java.util.List<KeyParamSpec> keySpecs) {
        StringBuilder params = new StringBuilder();
        StringBuilder args = new StringBuilder("contextPath.addSegment(\"").append(Names.escapeJavaString(rawNavName)).append("\")");
        for (KeyParamSpec k : keySpecs) {
            if (params.length() > 0) params.append(", ");
            params.append(k.javaType()).append(' ').append(k.javaParamName());
            args.append(".addKey(\"").append(Names.escapeJavaString(k.csdlName())).append("\", ")
                .append(k.javaParamName()).append(", \"").append(Names.escapeJavaString(k.edmType())).append("\")");
        }
        sb.append("    public ").append(entityReqClass).append(' ').append(methodName)
          .append('(').append(params).append(") {\n");
        sb.append("        return new ").append(entityReqClass).append("(context, ").append(args).append(");\n");
        sb.append("    }\n\n");
    }

    private record StreamMethodNames(String stream, String set) {}

    private Map<PropertyModel, StreamMethodNames> streamMethodNames(EntityTypeModel entityType) {
        Map<PropertyModel, StreamMethodNames> result = new java.util.LinkedHashMap<>();
        Set<String> used = new java.util.HashSet<>(List.of("select", "expand", "buildContext", "copy",
                "get", "patch", "patchWithETag", "put", "putWithETag", "putToBatchOperation", "delete",
                "deleteWithETag", "toBatchOperation", "patchToBatchOperation", "deleteToBatchOperation"));
        if (resolvedHasStream(entityType)) {
            used.add("streamMedia");
            used.add("setMedia");
        }
        for (NavigationPropertyModel nav : resolvedNavs(entityType)) {
            if (isNonEntityNav(nav, findSchemaForEntity(entityType))) {
                continue;
            }
            used.add(Names.toJavaFieldName(nav.name()));
            if (Names.isCollectionType(nav.type()) && !nav.containsTarget()) {
                used.add("add" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref");
                used.add("remove" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref");
            }
        }
        for (PropertyModel property : resolvedStreamProps(entityType)) {
            String stream = uniqueMethodName(Names.toJavaMethodName(property.name(), "stream"), used);
            String set = uniqueMethodName(Names.toJavaMethodName(property.name(), "set"), used);
            result.put(property, new StreamMethodNames(stream, set));
        }
        return result;
    }

    private String uniqueMethodName(String base, Set<String> used) {
        String candidate = base;
        int suffix = 1;
        while (used.contains(candidate) || Names.isObjectMethodName(candidate)) {
            candidate = base + "_" + suffix++;
        }
        used.add(candidate);
        return candidate;
    }

    private SchemaModel findSchemaForEntity(EntityTypeModel entityType) {
        SchemaModel owner = entityOwners == null ? null : entityOwners.get(entityType);
        return owner != null ? owner : (effectiveSchemas.isEmpty() ? null : effectiveSchemas.get(0));
    }

    private void checkBoundRequestMethodCollisions(EntityTypeModel entityType,
                                                    List<OperationGenerator.BoundOp> boundOps,
                                                    SchemaModel schema,
                                                    Map<PropertyModel, StreamMethodNames> streamNames) {
        Map<String, String> methods = new HashMap<>();
        for (String method : List.of("select", "expand", "buildContext", "copy", "get", "patch",
                "patchWithETag", "put", "putWithETag", "putToBatchOperation", "delete",
                "deleteWithETag", "toBatchOperation", "patchToBatchOperation", "deleteToBatchOperation")) {
            methods.put(method, "generated method");
        }
        if (resolvedHasStream(entityType)) {
            methods.putIfAbsent("streamMedia", "generated media method");
            methods.putIfAbsent("setMedia", "generated media method");
        }
        for (NavigationPropertyModel nav : resolvedNavs(entityType)) {
            if (isNonEntityNav(nav, schema)) continue;
            reserveRequestMethod(methods, Names.toJavaFieldName(nav.name()), nav.name());
            if (Names.isCollectionType(nav.type()) && !nav.containsTarget()) {
                reserveRequestMethod(methods, "add" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref",
                        nav.name());
                reserveRequestMethod(methods, "remove" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref",
                        nav.name());
            }
        }
        for (PropertyModel property : resolvedStreamProps(entityType)) {
            StreamMethodNames names = streamNames.get(property);
            if (names != null) {
                reserveRequestMethod(methods, names.stream(), property.name());
                reserveRequestMethod(methods, names.set(), property.name());
            }
        }
        for (OperationGenerator.BoundOp bound : boundOps) {
            reserveRequestMethod(methods, bound.accessorName(), bound.opName());
        }
    }

    private void checkEntityRequestMethodCollisions(EntityTypeModel entityType,
                                                      List<NavigationPropertyModel> navs,
                                                      SchemaModel schema,
                                                      Map<PropertyModel, StreamMethodNames> streamNames) {
        Map<String, String> methods = new HashMap<>();
        for (String method : List.of("select", "expand", "buildContext", "copy", "get", "patch",
                "patchWithETag", "put", "putWithETag", "putToBatchOperation", "delete",
                "deleteWithETag", "toBatchOperation", "patchToBatchOperation", "deleteToBatchOperation")) {
            methods.put(method, "generated method");
        }
        if (resolvedHasStream(entityType)) {
            methods.putIfAbsent("streamMedia", "generated media method");
            methods.putIfAbsent("setMedia", "generated media method");
        }
        for (NavigationPropertyModel nav : navs) {
            if (isNonEntityNav(nav, schema)) continue;
            reserveRequestMethod(methods, Names.toJavaFieldName(nav.name()), nav.name());
            if (Names.isCollectionType(nav.type()) && !nav.containsTarget()) {
                reserveRequestMethod(methods, "add" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref",
                        nav.name());
                reserveRequestMethod(methods, "remove" + Names.capitalize(Names.toJavaFieldName(nav.name())) + "Ref",
                        nav.name());
            }
        }
        for (PropertyModel property : resolvedStreamProps(entityType)) {
            StreamMethodNames names = streamNames.get(property);
            if (names != null) {
                reserveRequestMethod(methods, names.stream(), property.name());
                reserveRequestMethod(methods, names.set(), property.name());
            }
        }
    }

    private void reserveRequestMethod(Map<String, String> methods, String method, String source) {
        if (Names.isObjectMethodName(method) && !"generated method".equals(source)) {
            throw new IllegalStateException("Cannot generate entity request: " + source
                    + " maps to Object method '" + method + "()'");
        }
        String previous = methods.putIfAbsent(method, source);
        if (previous != null && !previous.equals(source)) {
            throw new IllegalStateException("Cannot generate entity request for '" + source
                    + "': method '" + method + "' collides with another generated or navigation method");
        }
    }

    /**
     * Whether a navigation property must be skipped by the REQUEST layer, because no
     * request class exists for its target. Every other consumer of this decision uses the
     * inverse test and bails on {@code != ENTITY}: EntityGenerator's nav constant and
     * Selector field, and AbstractTypeGenerator's nav-target FQN (which returns null for
     * anything that is not an entity). The request layer alone tested {@code == COMPLEX},
     * so an ENUM or Edm-primitive nav target fell through and emitted
     * {@code import ...ColorEntityRequest} / {@code List<Int32>} — classes that are never
     * generated, so the client did not compile.
     *
     * <p>CSDL §12.2 requires a NavigationProperty to target an entity or complex type, so
     * an enum or primitive target is invalid metadata and is rejected loudly rather than
     * turned into uncompilable output.
     */
    private boolean isNonEntityNav(NavigationPropertyModel nav, SchemaModel schema) {
        // Unwrap Collection(...) first: the raw collection form ("Collection(NS.Type)")
        // never matches the type-kind map, so a collection nav would fall through and emit
        // references to request classes that are only generated for entity types.
        // Also unwrap the TypeDefinition chain: MyAddr -> NS.Shared.Address (complex).
        SchemaModel owner = schemaForNavigation(nav, schema);
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String resolved = resolveTypeDefinition(unwrapped, owner);
        Names.TypeKind kind = resolveTypeKind(resolved, owner);
        // ENUM is a named type, so it resolves to its own kind; an Edm primitive resolves
        // to UNKNOWN (primitives are not in the type-kind map at all). Both are invalid
        // NavigationProperty targets under CSDL 12.2, and both previously produced a
        // reference to a request class that is never generated.
        if (kind == Names.TypeKind.ENUM || kind == Names.TypeKind.UNKNOWN) {
            throw new IllegalStateException("Navigation property '" + nav.name()
                    + "' targets non-navigable type '" + nav.type()
                    + "'; CSDL 12.2 requires an entity or complex type");
        }
        return kind != Names.TypeKind.ENTITY;
    }
}
