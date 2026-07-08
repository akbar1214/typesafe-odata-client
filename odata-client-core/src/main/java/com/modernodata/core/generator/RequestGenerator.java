package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel.EntityTypeModel;
import com.modernodata.core.model.CsdlModel.NavigationPropertyModel;
import com.modernodata.core.model.CsdlModel.PropertyModel;
import com.modernodata.core.model.CsdlModel.SchemaModel;

import java.util.Set;
import java.util.TreeSet;

public class RequestGenerator {

    private final String basePackage;

    public RequestGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generateEntityRequest(EntityTypeModel entityType, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixEntityRequest();
        String className = Names.entityRequestClassName(entityType.name());
        String entityClassName = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("com.modernodata.runtime.entity.Context");
        imports.add("com.modernodata.runtime.entity.ContextPath");
        imports.add("com.modernodata.runtime.client.EntityOperations");
        imports.add("com.modernodata.runtime.query.*");
        imports.add("com.modernodata.runtime.batch.BatchOperation");
        imports.add(basePackage + Names.packageNameSuffixEntity() + "." + entityClassName);

        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            boolean isCollection = Names.isCollectionType(nav.type());
            String unwrapped = Names.unwrapCollectionType(nav.type());
            String elementClassName = Names.simpleNameFromFullName(unwrapped);
            if (isCollection) {
                imports.add(basePackage + Names.packageNameSuffixCollectionRequest() + "." + Names.collectionRequestClassName(elementClassName));
            } else {
                imports.add(basePackage + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(elementClassName));
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

        // Navigation property methods
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
            sb.append(generateNavMethod(nav, schema));
        }

        // $ref methods for collection navigation properties
        for (NavigationPropertyModel nav : entityType.navigationProperties()) {
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
        String pkg = basePackage + Names.packageNameSuffixCollectionRequest();
        String className = Names.collectionRequestClassName(entityType.name());
        String entityClassName = Names.entityClassName(entityType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.List");
        imports.add("java.util.stream.Stream");
        imports.add("com.modernodata.runtime.entity.Context");
        imports.add("com.modernodata.runtime.entity.ContextPath");
        imports.add("com.modernodata.runtime.client.EntityOperations");
        imports.add("com.modernodata.runtime.query.*");
        imports.add("com.modernodata.runtime.paging.CollectionPage");
        imports.add("com.modernodata.runtime.batch.BatchOperation");
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
        sb.append("    private String searchTerm;\n\n");

        sb.append("    public ").append(className).append("(Context context, ContextPath contextPath) {\n");
        sb.append("        this.context = context;\n");
        sb.append("        this.contextPath = contextPath;\n");
        sb.append("    }\n\n");

        // Type-safe filter
        sb.append("    public ").append(className).append(" filter(FilterExpression predicate) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        next.filters.add(predicate.toODataExpression());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe select
        sb.append("    public ").append(className).append(" select(StringProperty<?>... properties) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var p : properties) next.selects.add(p.getEdmName());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe expand
        sb.append("    public ").append(className).append(" expand(NavProperty<?, ?>... properties) {\n");
        sb.append("        ").append(className).append(" next = copy();\n");
        sb.append("        for (var p : properties) next.expands.add(p.getEdmName());\n");
        sb.append("        return next;\n");
        sb.append("    }\n\n");

        // Type-safe orderBy
        sb.append("    public ").append(className).append(" orderBy(OrderExpression<?>... expressions) {\n");
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

        // Key-based entity accessor methods
        if (!entityType.keys().isEmpty()) {
            for (var key : entityType.keys()) {
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
        sb.append("        return c;\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String resolveKeyType(EntityTypeModel entityType, String keyPropName, SchemaModel schema) {
        for (PropertyModel prop : entityType.properties()) {
            if (prop.name().equals(keyPropName)) {
                String edmType = prop.edmType();
                if (Names.isStringType(edmType)) return "String";
                if (Names.isNumericType(edmType)) return Names.edmTypeToSimpleJavaType(edmType);
                return "Object";
            }
        }
        return "Object";
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
}
