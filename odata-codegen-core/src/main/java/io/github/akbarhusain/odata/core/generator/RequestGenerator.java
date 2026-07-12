package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.KeyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RequestGenerator {

    private final String basePackage;
    private final Map<String, String> schemaPackages;
    private final String defaultBasePackage;
    private final List<SchemaModel> allSchemas;
    private List<SchemaModel> effectiveSchemas;

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
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
        this.allSchemas = allSchemas;
    }

    public String generateEntityRequest(EntityTypeModel entityType, SchemaModel schema) {
        effectiveSchemas = allSchemas.isEmpty() ? List.of(schema) : allSchemas;
        String pkg = basePackage + Names.packageNameSuffixEntityRequest();
        String className = Names.entityRequestClassName(entityType.name());
        String entityClassName = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.client.EntityOperations");
        imports.add("io.github.akbarhusain.odata.runtime.exception.ODataException");
        imports.add("io.github.akbarhusain.odata.runtime.query.*");
        imports.add("io.github.akbarhusain.odata.runtime.batch.BatchOperation");
        imports.add("java.io.InputStream");
        imports.add(basePackage + Names.packageNameSuffixEntity() + "." + entityClassName);

        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            if (isComplexTypeNav(nav, schema)) continue;
            boolean isCollection = Names.isCollectionType(nav.type());
            String elementType = Names.unwrapCollectionType(nav.type());
            String elementClassName = Names.simpleNameFromFullName(elementType);
            if (isCollection) {
                imports.add(basePackageForType(elementType, schema) + Names.packageNameSuffixCollectionRequest() + "." + Names.collectionRequestClassName(elementClassName));
            } else {
                imports.add(basePackageForType(elementType, schema) + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(elementClassName));
            }
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    protected final Context context;\n");
        sb.append("    protected final ContextPath contextPath;\n\n");
        sb.append("    public ").append(className).append("(Context context, ContextPath contextPath) {\n");
        sb.append("        this.context = context;\n");
        sb.append("        this.contextPath = contextPath;\n");
        sb.append("    }\n\n");

        // Navigation property methods — only for entity nav targets (complex types are inline data, not navigable)
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            if (isComplexTypeNav(nav, schema)) continue;
            sb.append(generateNavMethod(nav, schema));
        }

        // $ref methods for collection navigation properties — only for entity nav targets
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            if (isComplexTypeNav(nav, schema)) continue;
            if (Names.isCollectionType(nav.type())) {
                String collReqClass = Names.collectionRequestClassName(Names.simpleNameFromFullName(Names.unwrapCollectionType(nav.type())));
                sb.append("    public void add").append(Names.capitalize(nav.name())).append("Ref(String targetEntityUrl) {\n");
                sb.append("        EntityOperations.addRef(context, contextPath.addSegment(\"").append(nav.name()).append("\"), targetEntityUrl);\n");
                sb.append("    }\n\n");

                sb.append("    public void remove").append(Names.capitalize(nav.name())).append("Ref(String targetKey) {\n");
                sb.append("        EntityOperations.removeRef(context, contextPath.addSegment(\"").append(nav.name()).append("\"), targetKey);\n");
                sb.append("    }\n\n");
            }
        }

        // CRUD operations
        sb.append("    public ").append(entityClassName).append(" get() {\n");
        sb.append("        return EntityOperations.executeAndGetEntity(context, contextPath, ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" patch(").append(entityClassName).append(" entity) {\n");
        sb.append("        return EntityOperations.executePatchEntity(context, contextPath, entity, ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(entityClassName).append(" patchWithETag(").append(entityClassName).append(" entity, String etag) {\n");
        sb.append("        return EntityOperations.executePatchEntityWithETag(context, contextPath, entity, ").append(entityClassName).append(".class, etag);\n");
        sb.append("    }\n\n");

        sb.append("    public void delete() {\n");
        sb.append("        EntityOperations.executeDelete(context, contextPath);\n");
        sb.append("    }\n\n");

        sb.append("    public void deleteWithETag(String etag) {\n");
        sb.append("        EntityOperations.executeDeleteWithETag(context, contextPath, etag);\n");
        sb.append("    }\n\n");

        // Media stream access — the entity itself is a media stream (HasStream="true") at $value
        if (entityType.hasStream()) {
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
        for (PropertyModel prop : entityType.properties()) {
            if ("Edm.Stream".equals(prop.edmType())) {
                String streamMethod = Names.toJavaMethodName(prop.name(), "stream");
                String setMethod = Names.toJavaMethodName(prop.name(), "set");
                sb.append("    public java.io.InputStream ").append(streamMethod).append("() {\n");
                sb.append("        return EntityOperations.streamMedia(context, contextPath.addSegment(\"")
                  .append(prop.name()).append("\"));\n");
                sb.append("    }\n\n");

                sb.append("    public void ").append(setMethod).append("(java.io.InputStream content) {\n");
                sb.append("        ").append(setMethod).append("(content, null);\n");
                sb.append("    }\n\n");

                sb.append("    public void ").append(setMethod).append("(java.io.InputStream content, String etag) {\n");
                sb.append("        try {\n");
                sb.append("            byte[] bytes = content.readAllBytes();\n");
                sb.append("            EntityOperations.putMedia(context, contextPath.addSegment(\"")
                  .append(prop.name()).append("\"), bytes, \"application/octet-stream\", etag);\n");
                sb.append("        } catch (java.io.IOException e) {\n");
                sb.append("            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(\"Failed to read media stream: \" + e.getMessage(), e);\n");
                sb.append("        }\n");
                sb.append("    }\n\n");
            }
        }

        // Batch methods
        sb.append("    public BatchOperation toBatchOperation() {\n");
        sb.append("        return BatchOperation.get(contextPath.toRelativeUrl());\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation patchToBatchOperation(").append(entityClassName).append(" entity) {\n");
        sb.append("        byte[] body = context.serializer().serialize(entity, ").append(entityClassName).append(".class);\n");
        sb.append("        return BatchOperation.patch(contextPath.toRelativeUrl(), body);\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation deleteToBatchOperation() {\n");
        sb.append("        return BatchOperation.delete(contextPath.toRelativeUrl());\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    public String generateCollectionRequest(EntityTypeModel entityType, SchemaModel schema) {
        effectiveSchemas = allSchemas.isEmpty() ? List.of(schema) : allSchemas;
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
        imports.add("io.github.akbarhusain.odata.runtime.query.*");
        imports.add("io.github.akbarhusain.odata.runtime.paging.CollectionPage");
        imports.add("io.github.akbarhusain.odata.runtime.batch.BatchOperation");
        imports.add(basePackage + Names.packageNameSuffixEntity() + "." + entityClassName);
        imports.add(basePackage + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(entityType.name()));

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    protected final Context context;\n");
        sb.append("    protected final ContextPath contextPath;\n");
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
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.filters.add(predicate.toODataExpression());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe select
        sb.append("    public ").append(className).append(" select(PropertyExpression<? super ").append(entityClassName).append(", ?>... properties) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var p : properties) next.selects.add(p.getEdmName());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe expand
        sb.append("    public ").append(className).append(" expand(NavProperty<? super ").append(entityClassName).append(", ?>... properties) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var p : properties) next.expands.add(p.getEdmName());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Expand with nested options (select, filter, orderby, top)
        sb.append("    public ").append(className).append(" expand(NavProperty.NavQuery<? super ").append(entityClassName).append(", ?>... queries) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var q : queries) next.expands.add(q.toODataExpand());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe orderBy
        sb.append("    public ").append(className).append(" orderBy(OrderExpression<? super ").append(entityClassName).append(", ?>... expressions) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var e : expressions) next.orderings.add(e.getODataPath());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // top, skip, count, search
        sb.append("    public ").append(className).append(" top(int count) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.topValue = count;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" skip(int count) {\n");
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
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.searchTerm = term;\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // $apply (aggregation / transformations, including $compute)
        sb.append("    public ").append(className).append(" apply(ApplyExpression expr) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.applyExpr = expr.toODataApply();\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append(" apply(String raw) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.applyExpr = ApplyExpression.of(raw).toODataApply();\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Execution methods
        sb.append("    public ContextPath buildContext() {\n");
        sb.append("        ContextPath ctx = contextPath;\n");
        sb.append("        if (!filters.isEmpty()) {\n");
        sb.append("            ctx = ctx.addQuery(\"$filter\", String.join(\" and \", filters));\n");
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
        sb.append("        return EntityOperations.executeAndGetCollection(context, buildContext(), ").append(entityClassName).append(".class);\n");
        sb.append("    }\n\n");

        sb.append("    public Stream<").append(entityClassName).append("> stream() {\n");
        sb.append("        return get().stream();\n");
        sb.append("    }\n\n");

        sb.append("    public List<").append(entityClassName).append("> toList() {\n");
        sb.append("        return get().toList();\n");
        sb.append("    }\n\n");

        sb.append("    public BatchOperation toBatchOperation() {\n");
        sb.append("        return BatchOperation.get(buildContext().toRelativeUrl());\n");
        sb.append("    }\n\n");

        // Pagination helper: fetch the next page from an @odata.nextLink URL
        sb.append("    public ").append(className).append(" nextPage(String nextLink) {\n");
        sb.append("        return new ").append(className).append("(context, contextPath.fromNextLink(nextLink));\n");
        sb.append("    }\n\n");

        // Direct $count endpoint helper (GET /EntitySet/$count)
        sb.append("    public long countValue() {\n");
        sb.append("        ").append(className).append(" tmp = copy();\n");
        sb.append("        tmp.countRequested = false;\n");
        sb.append("        return EntityOperations.executeCount(context, tmp.buildContext());\n");
        sb.append("    }\n\n");

        // Key-based entity accessor methods
        java.util.List<KeyModel> resolvedKeys = resolvedKeys(entityType, schema);
        if (!resolvedKeys.isEmpty()) {
            for (var key : resolvedKeys) {
                if (key.propertyRefs().size() == 1) {
                    // Single key - generate entity accessor with key parameter
                    String keyProp = key.propertyRefs().get(0);
                    String paramName = Names.toJavaFieldName(keyProp);
                    String paramType = resolveKeyType(entityType, keyProp, schema);
                    sb.append("    public ").append(Names.entityRequestClassName(entityType.name()))
                      .append(" ").append(Names.toJavaFieldName(entityType.name()))
                      .append("By").append(Names.capitalize(keyProp))
                      .append("(").append(paramType).append(" ").append(paramName).append(") {\n");
                    sb.append("        return new ").append(Names.entityRequestClassName(entityType.name()))
                      .append("(context, contextPath.addKey(\"").append(keyProp).append("\", ").append(paramName).append("));\n");
                    sb.append("    }\n\n");
                } else {
                    // Composite key - generate single accessor with all key params
                    StringBuilder params = new StringBuilder();
                    StringBuilder args = new StringBuilder();
                    for (int i = 0; i < key.propertyRefs().size(); i++) {
                        String keyProp = key.propertyRefs().get(i);
                        String paramName = Names.toJavaFieldName(keyProp);
                        String paramType = resolveKeyType(entityType, keyProp, schema);
                        if (i > 0) { params.append(", "); args.append(".addKey(\""); }
                        else { args.append("contextPath.addKey(\""); }
                        params.append(paramType).append(" ").append(paramName);
                        args.append(keyProp).append("\", ").append(paramName).append(")");
                    }
                    sb.append("    public ").append(Names.entityRequestClassName(entityType.name()))
                      .append(" ").append(Names.toJavaFieldName(entityType.name()))
                      .append("By").append(Names.capitalize(key.propertyRefs().get(0)))
                      .append("(").append(params).append(") {\n");
                    sb.append("        return new ").append(Names.entityRequestClassName(entityType.name()))
                      .append("(context, ").append(args).append(");\n");
                    sb.append("    }\n\n");
                }
            }
        }

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

    private String resolveKeyType(EntityTypeModel entityType, String keyPropName, SchemaModel schema) {
        for (PropertyModel prop : entityType.properties()) {
            if (prop.name().equals(keyPropName)) {
                return keyJavaType(prop.edmType(), schema);
            }
        }
        EntityTypeModel base = findBase(entityType, schema);
        if (base != null) {
            return resolveKeyType(base, keyPropName, schema);
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
        return "Object";
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

    private java.util.List<KeyModel> resolvedKeys(EntityTypeModel entityType, SchemaModel schema) {
        if (!entityType.keys().isEmpty()) {
            return entityType.keys();
        }
        EntityTypeModel base = findBase(entityType, schema);
        if (base == null) {
            return java.util.List.of();
        }
        return resolvedKeys(base, schema);
    }

    private EntityTypeModel findBase(EntityTypeModel entityType, SchemaModel schema) {
        if (entityType.baseType() == null) return null;
        String baseName = Names.simpleNameFromFullName(entityType.baseType());
        for (EntityTypeModel et : schema.entityTypes()) {
            if (et.name().equals(baseName)) return et;
        }
        return null;
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

    private String generateNavMethod(NavigationPropertyModel nav, SchemaModel schema) {
        boolean isCollection = Names.isCollectionType(nav.type());
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.simpleNameFromFullName(unwrapped);
        String methodName = Names.toJavaFieldName(nav.name());

        StringBuilder sb = new StringBuilder();
        sb.append("    public ");
        if (isCollection) {
            sb.append(Names.collectionRequestClassName(elementClassName)).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(Names.collectionRequestClassName(elementClassName))
              .append("(context, contextPath.addSegment(\"").append(nav.name()).append("\"));\n");
        } else {
            sb.append(Names.entityRequestClassName(elementClassName)).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(Names.entityRequestClassName(elementClassName))
              .append("(context, contextPath.addSegment(\"").append(nav.name()).append("\"));\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    private boolean isComplexTypeNav(NavigationPropertyModel nav, SchemaModel schema) {
        return Names.resolveTypeKind(nav.type(), allSchemas) == Names.TypeKind.COMPLEX;
    }
}
