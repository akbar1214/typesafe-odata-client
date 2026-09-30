package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ActionImportModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ContainerModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.EntitySetModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.FunctionImportModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SingletonModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ContainerGenerator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ContainerGenerator.class);

    private final String basePackage;
    private final Map<String, String> schemaPackages;
    private final String defaultBasePackage;
    private List<CsdlModel.SchemaModel> allSchemas;
    private OperationGenerator sharedOperationGenerator;
    private RequestGenerator sharedRequestGenerator;
    private CsdlModel.SchemaModel cachedSingleSchema;

    public ContainerGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null);
    }

    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages,
                              String defaultBasePackage) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
    }

    /** Cross-schema-aware constructor — required to resolve operations declared in other schemas. */
    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages,
                              String defaultBasePackage, List<CsdlModel.SchemaModel> allSchemas) {
        this(basePackage, schemaPackages, defaultBasePackage, allSchemas, null, null);
    }

    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages,
                              String defaultBasePackage, List<CsdlModel.SchemaModel> allSchemas,
                              OperationGenerator operationGenerator, RequestGenerator requestGenerator) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
        this.allSchemas = allSchemas;
        this.sharedOperationGenerator = operationGenerator;
        this.sharedRequestGenerator = requestGenerator;
    }

    private void refreshSharedStateForSchema(CsdlModel.SchemaModel schema) {
        // SchemaModel is a record: membership and cache validity are decided by VALUE, so an
        // equal-valued (but non-identical) schema instance neither rebuilds the shared
        // generators nor silently degrades to single-schema mode. Namespaces are unique per
        // document, so value-equal schemas are interchangeable.
        boolean hasCompleteSchemaList = allSchemas != null && !allSchemas.isEmpty();
        boolean schemaInList = hasCompleteSchemaList && allSchemas.contains(schema);
        if (!hasCompleteSchemaList || !schemaInList) {
            if (cachedSingleSchema != null && !cachedSingleSchema.equals(schema)) {
                sharedOperationGenerator = null;
                sharedRequestGenerator = null;
            }
            cachedSingleSchema = schema;
        }
    }

    private List<CsdlModel.SchemaModel> schemasFor(CsdlModel.SchemaModel schema) {
        if (allSchemas == null || allSchemas.isEmpty()) {
            return List.of(schema);
        }
        return allSchemas.contains(schema) ? allSchemas : List.of(schema);
    }

    private OperationGenerator operationGenerator(CsdlModel.SchemaModel schema) {
        refreshSharedStateForSchema(schema);
        if (sharedOperationGenerator != null) return sharedOperationGenerator;
        sharedOperationGenerator = new OperationGenerator(basePackage, schemaPackages, defaultBasePackage,
                schemasFor(schema));
        return sharedOperationGenerator;
    }

    private RequestGenerator requestGenerator(CsdlModel.SchemaModel schema) {
        // Build the operation generator first: refreshSharedStateForSchema may have just
        // nulled both shared generators, and RequestGenerator needs a non-null op-gen.
        // (Relying on generate() to call operationGenerator() first was an ordering trap.)
        OperationGenerator ops = operationGenerator(schema);
        if (sharedRequestGenerator != null) return sharedRequestGenerator;
        sharedRequestGenerator = new RequestGenerator(basePackage, schemaPackages, defaultBasePackage,
                schemasFor(schema), ops);
        return sharedRequestGenerator;
    }

    public String generate(ContainerModel container, SchemaModel schema) {
        generatingNamespace = schema.namespace();
        String pkg = basePackage + Names.packageNameSuffixContainer();
        String className = Names.containerClassName(container.name());
        String reservedClassFqn = pkg + "." + className;
        OperationGenerator ops = operationGenerator(schema);
        RequestGenerator reqGen = requestGenerator(schema);

        // Accessor methods derive from member names; two members folding onto one
        // method (e.g. an EntitySet and a Singleton both named 'People') previously
        // emitted duplicate methods that don't compile — fail loudly instead.
        // Function/action imports join the same registry so an import named like a
        // set/singleton cannot silently shadow (or be shadowed by) another accessor.
        Map<String, String> accessors = new java.util.HashMap<>();
        for (EntitySetModel es : container.entitySets()) {
            checkAccessorCollision(accessors, Names.toJavaFieldName(es.name()), "EntitySet '" + es.name() + "'", className);
        }
        for (SingletonModel singleton : container.singletons()) {
            checkAccessorCollision(accessors, Names.toJavaFieldName(singleton.name()), "Singleton '" + singleton.name() + "'", className);
        }
        // Function/action imports join the same registry, one entry PER OVERLOAD accessor
        // (an overloaded import emits suffixed accessors like isSiteAdminByUserId — the
        // unsuffixed import name itself never becomes a method).
        for (FunctionImportModel fi : container.functionImports()) {
            for (String accessorName : ops.functionImportAccessorNames(fi, schema)) {
                checkAccessorCollision(accessors, accessorName,
                        "FunctionImport '" + fi.name() + "'", className);
            }
        }
        for (ActionImportModel ai : container.actionImports()) {
            checkAccessorCollision(accessors, Names.toJavaFieldName(ai.name()),
                    "ActionImport '" + ai.name() + "'", className);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");

        // Two schemas may declare same-named entities mapped to different output
        // packages; contested simple names are referenced fully-qualified, never imported.
        // The container's own class is a claimant of its simple name: a legal container
        // name may equal a generated request/operation class, and importing it into the
        // unit that declares it does not compile (lesson 201).
        List<String> refCandidates = new ArrayList<>();
        refCandidates.add(reservedClassFqn);
        for (EntitySetModel es : container.entitySets()) {
            reqGen.requireKnownTypeForGeneration(es.entityType(), schema, "container '" + container.name() + "'",
                    "entity set '" + es.name() + "'");
            String resolvedType = reqGen.resolvedTypeForGeneration(es.entityType(), schema);
            String entityClassName = reqGen.entityClassNameForType(es.entityType(), schema);
            refCandidates.add(basePackageForType(resolvedType, schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            CsdlModel.EntityTypeModel importType = reqGen.resolveEntityType(resolvedType, schema);
            if (importType != null && !reqGen.keyParamSpecs(importType, schema).isEmpty()) {
                refCandidates.add(basePackageForType(resolvedType, schema)
                        + Names.packageNameSuffixEntityRequest() + "."
                        + Names.entityRequestClassName(entityClassName));
            }
        }
        for (SingletonModel singleton : container.singletons()) {
            reqGen.requireKnownTypeForGeneration(singleton.type(), schema, "container '" + container.name() + "'",
                    "singleton '" + singleton.name() + "'");
            String resolvedType = reqGen.resolvedTypeForGeneration(singleton.type(), schema);
            String entityClassName = reqGen.entityClassNameForType(singleton.type(), schema);
            refCandidates.add(basePackageForType(resolvedType, schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
        }
        // Operation request classes and their parameter types are referenced from the
        // container's accessors; they join the SAME resolution so a simple name contested
        // across set/singleton/import references becomes fully-qualified instead of being
        // imported twice.
        for (FunctionImportModel fi : container.functionImports()) {
            refCandidates.addAll(ops.functionImportClassFqns(fi, schema));
            refCandidates.addAll(ops.functionImportParameterImports(fi, schema, reservedClassFqn));
        }
        for (ActionImportModel ai : container.actionImports()) {
            refCandidates.add(ops.actionImportClassFqn(ai, schema));
            refCandidates.addAll(ops.actionImportParameterImports(ai, schema, reservedClassFqn));
        }
        java.util.Map<String, String> refs = TypeRefs.resolve(refCandidates);

        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = reqGen.entityClassNameForType(es.entityType(), schema);
            String collRef = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            if (!collRef.contains(".")) {
                imports.add(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                        + Names.packageNameSuffixCollectionRequest() + "." + collRef);
            }
            CsdlModel.EntityTypeModel importType = reqGen.resolveEntityType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema);
            if (importType != null && !reqGen.keyParamSpecs(importType, schema).isEmpty()) {
                String entRef = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                        + Names.packageNameSuffixEntityRequest() + "."
                        + Names.entityRequestClassName(entityClassName));
                if (!entRef.contains(".")) {
                    imports.add(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                            + Names.packageNameSuffixEntityRequest() + "." + entRef);
                }
            }
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = reqGen.entityClassNameForType(singleton.type(), schema);
            String entRef = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(singleton.type(), schema), schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
            if (!entRef.contains(".")) {
                imports.add(basePackageForType(reqGen.resolvedTypeForGeneration(singleton.type(), schema), schema)
                        + Names.packageNameSuffixEntityRequest() + "." + entRef);
            }
        }

        List<String> importAccessorMethods = new ArrayList<>();
        for (FunctionImportModel fi : container.functionImports()) {
            // resolveValidationThrowsUnknownOrBound — resolution happens here so failures surface at generation
            importAccessorMethods.addAll(ops.functionImportAccessorMethods(fi, schema, reservedClassFqn, refs));
            for (String fqn : ops.functionImportClassFqns(fi, schema)) {
                addImportIfUncontested(imports, refs, fqn);
            }
            // accessors reference structured/enum parameter types from other packages
            for (String fqn : ops.functionImportParameterImports(fi, schema, reservedClassFqn)) {
                addImportIfUncontested(imports, refs, fqn);
            }
        }
        for (ActionImportModel ai : container.actionImports()) {
            importAccessorMethods.add(ops.actionImportAccessorMethod(ai, schema, reservedClassFqn, refs));
            addImportIfUncontested(imports, refs, ops.actionImportClassFqn(ai, schema));
            for (String fqn : ops.actionImportParameterImports(ai, schema, reservedClassFqn)) {
                addImportIfUncontested(imports, refs, fqn);
            }
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    private final Context context;\n\n");

        sb.append("    public ").append(className).append("(Context context) {\n");
        sb.append("        this.context = context;\n");
        sb.append("    }\n\n");

        // Entity set accessors
        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = reqGen.entityClassNameForType(es.entityType(), schema);
            String collReqClassName = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            String methodName = Names.toJavaFieldName(es.name());

            sb.append("    public ").append(collReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(collReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(Names.escapeJavaString(es.name())).append("\"));\n");
            sb.append("    }\n\n");

            // Keyed overload (decision 95): <set>(key...) → entity request, so
            // client.people("russellwhyte") keys the first segment directly
            CsdlModel.EntityTypeModel setType = reqGen.resolveEntityType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema);
            if (setType != null) {
                java.util.List<RequestGenerator.KeyParamSpec> keySpecs =
                        reqGen.keyParamSpecs(setType, schema);
                if (!keySpecs.isEmpty()) {
                    String entRef = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(es.entityType(), schema), schema)
                            + Names.packageNameSuffixEntityRequest() + "."
                            + Names.entityRequestClassName(entityClassName));
                    appendKeyedOverload(sb, es.name(), methodName, entRef, keySpecs);
                }
            }
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = reqGen.entityClassNameForType(singleton.type(), schema);
            String entityReqClassName = refs.get(basePackageForType(reqGen.resolvedTypeForGeneration(singleton.type(), schema), schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
            String methodName = Names.toJavaFieldName(singleton.name());

            sb.append("    public ").append(entityReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(entityReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(Names.escapeJavaString(singleton.name())).append("\"));\n");
            sb.append("    }\n\n");
        }

        // Function/action import accessors (resolved + validated in the import loop above)
        for (String accessor : importAccessorMethods) {
            sb.append(accessor);
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Emits {@code public FooEntityRequest foo(keyParams...) } — the keyed container
     * overload returning the entity request. Single keys take one parameter; composite
     * keys chain one {@code addKey} per {@code PropertyRef} in CSDL order.
     */
    private static void appendKeyedOverload(StringBuilder sb, String rawSetName, String methodName,
                                            String entityReqClass,
                                            java.util.List<RequestGenerator.KeyParamSpec> keySpecs) {
        StringBuilder params = new StringBuilder();
        StringBuilder args = new StringBuilder("context.basePath().addSegment(\"")
                .append(Names.escapeJavaString(rawSetName)).append("\")");
        for (RequestGenerator.KeyParamSpec k : keySpecs) {
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

    private static void checkAccessorCollision(Map<String, String> accessors, String methodName,
                                                String memberDescription, String className) {
        if (Names.isObjectMethodName(methodName)) {
            throw new IllegalStateException("Cannot generate container " + className + ": "
                    + memberDescription + " maps to Object method '" + methodName + "()'");
        }
        String previous = accessors.putIfAbsent(methodName, memberDescription);
        if (previous != null) {
            throw new IllegalStateException("Cannot generate container " + className + ": " + previous
                    + " and " + memberDescription + " both map to accessor '" + methodName + "()'. "
                    + "Rename one of them in the metadata.");
        }
    }

    /**
     * The namespace of the container's own schema — the schema whose files this generator
     * writes. Mirrors {@code AbstractTypeGenerator.generatingNamespace}, which
     * {@code ContainerGenerator} does not extend; set in {@link #generate}.
     */
    private String generatingNamespace;

    // P0-3: Look up the base package for a cross-namespace type reference.
    // Same rule as AbstractTypeGenerator.basePackageForType: a type in the schema being
    // generated belongs to `basePackage`; any other namespace resolves through the package
    // map, so split-merge metadata does not resolve cross-package references to the
    // generating package.
    private String basePackageForType(String edmType, SchemaModel schema) {
        String namespace = Names.namespaceFromFullName(edmType);
        if (namespace.isEmpty()) {
            return basePackage;
        }
        if (namespace.equals(generatingNamespace)) {
            return basePackage;
        }
        return schemaPackages.getOrDefault(namespace,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(namespace));
    }

    /**
     * Imports {@code fqn} only when the container-wide resolution references it by simple
     * name. A contested simple name is emitted fully-qualified at every use site, so
     * importing it too would either be a redundant import or a same-simple-name collision
     * with the other claimant (lesson 201).
     */
    private static void addImportIfUncontested(Set<String> imports, java.util.Map<String, String> refs,
                                                String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        String reference = refs.getOrDefault(fqn, simple);
        if (!reference.contains(".")) {
            imports.add(fqn);
        }
    }
}
