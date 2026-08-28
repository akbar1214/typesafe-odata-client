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
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
        this.allSchemas = allSchemas;
    }

    private OperationGenerator operationGenerator(CsdlModel.SchemaModel schema) {
        return new OperationGenerator(basePackage, schemaPackages, defaultBasePackage,
                allSchemas == null || allSchemas.isEmpty() ? List.of(schema) : allSchemas);
    }

    private RequestGenerator requestGenerator(CsdlModel.SchemaModel schema) {
        return new RequestGenerator(basePackage, schemaPackages, defaultBasePackage,
                allSchemas == null || allSchemas.isEmpty() ? List.of(schema) : allSchemas);
    }

    public String generate(ContainerModel container, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixContainer();
        String className = Names.containerClassName(container.name());
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
        // packages; contested simple names are referenced fully-qualified, never imported
        List<String> refCandidates = new ArrayList<>();
        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = Names.simpleNameFromFullName(es.entityType());
            refCandidates.add(basePackageForType(es.entityType(), schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            CsdlModel.EntityTypeModel importType = reqGen.resolveEntityType(es.entityType(), schema);
            if (importType != null && !reqGen.keyParamSpecs(importType, schema).isEmpty()) {
                refCandidates.add(basePackageForType(es.entityType(), schema)
                        + Names.packageNameSuffixEntityRequest() + "."
                        + Names.entityRequestClassName(entityClassName));
            }
        }
        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            refCandidates.add(basePackageForType(singleton.type(), schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
        }
        java.util.Map<String, String> refs = TypeRefs.resolve(refCandidates);

        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = Names.simpleNameFromFullName(es.entityType());
            String collRef = refs.get(basePackageForType(es.entityType(), schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            if (!collRef.contains(".")) {
                imports.add(basePackageForType(es.entityType(), schema)
                        + Names.packageNameSuffixCollectionRequest() + "." + collRef);
            }
            CsdlModel.EntityTypeModel importType = reqGen.resolveEntityType(es.entityType(), schema);
            if (importType != null && !reqGen.keyParamSpecs(importType, schema).isEmpty()) {
                String entRef = refs.get(basePackageForType(es.entityType(), schema)
                        + Names.packageNameSuffixEntityRequest() + "."
                        + Names.entityRequestClassName(entityClassName));
                if (!entRef.contains(".")) {
                    imports.add(basePackageForType(es.entityType(), schema)
                            + Names.packageNameSuffixEntityRequest() + "." + entRef);
                }
            }
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            String entRef = refs.get(basePackageForType(singleton.type(), schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
            if (!entRef.contains(".")) {
                imports.add(basePackageForType(singleton.type(), schema)
                        + Names.packageNameSuffixEntityRequest() + "." + entRef);
            }
        }

        List<String> importAccessorMethods = new ArrayList<>();
        for (FunctionImportModel fi : container.functionImports()) {
            // resolveValidationThrowsUnknownOrBound — resolution happens here so failures surface at generation
            importAccessorMethods.addAll(ops.functionImportAccessorMethods(fi, schema));
            imports.addAll(ops.functionImportClassImportLines(fi, schema));
            // accessors reference structured/enum parameter types from other packages
            imports.addAll(ops.functionImportParameterImports(fi, schema));
        }
        for (ActionImportModel ai : container.actionImports()) {
            importAccessorMethods.add(ops.actionImportAccessorMethod(ai, schema));
            imports.add(ops.actionImportClassImportLine(ai, schema));
            imports.addAll(ops.actionImportParameterImports(ai, schema));
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
            String entityClassName = Names.simpleNameFromFullName(es.entityType());
            String collReqClassName = refs.get(basePackageForType(es.entityType(), schema)
                    + Names.packageNameSuffixCollectionRequest() + "."
                    + Names.collectionRequestClassName(entityClassName));
            String methodName = Names.toJavaFieldName(es.name());

            sb.append("    public ").append(collReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(collReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(es.name()).append("\"));\n");
            sb.append("    }\n\n");

            // Keyed overload (decision 95): <set>(key...) → entity request, so
            // client.people("russellwhyte") keys the first segment directly
            CsdlModel.EntityTypeModel setType = reqGen.resolveEntityType(es.entityType(), schema);
            if (setType != null) {
                java.util.List<RequestGenerator.KeyParamSpec> keySpecs =
                        reqGen.keyParamSpecs(setType, schema);
                if (!keySpecs.isEmpty()) {
                    String entRef = refs.get(basePackageForType(es.entityType(), schema)
                            + Names.packageNameSuffixEntityRequest() + "."
                            + Names.entityRequestClassName(entityClassName));
                    appendKeyedOverload(sb, es.name(), methodName, entRef, keySpecs);
                }
            }
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            String entityReqClassName = refs.get(basePackageForType(singleton.type(), schema)
                    + Names.packageNameSuffixEntityRequest() + "."
                    + Names.entityRequestClassName(entityClassName));
            String methodName = Names.toJavaFieldName(singleton.name());

            sb.append("    public ").append(entityReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(entityReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(singleton.name()).append("\"));\n");
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
                .append(rawSetName).append("\")");
        for (RequestGenerator.KeyParamSpec k : keySpecs) {
            if (params.length() > 0) params.append(", ");
            params.append(k.javaType()).append(' ').append(k.javaParamName());
            args.append(".addKey(\"").append(k.csdlName()).append("\", ")
                .append(k.javaParamName()).append(", \"").append(k.edmType()).append("\")");
        }
        sb.append("    public ").append(entityReqClass).append(' ').append(methodName)
          .append('(').append(params).append(") {\n");
        sb.append("        return new ").append(entityReqClass).append("(context, ").append(args).append(");\n");
        sb.append("    }\n\n");
    }

    private static void checkAccessorCollision(Map<String, String> accessors, String methodName,
                                                String memberDescription, String className) {        String previous = accessors.putIfAbsent(methodName, memberDescription);
        if (previous != null) {
            throw new IllegalStateException("Cannot generate container " + className + ": " + previous
                    + " and " + memberDescription + " both map to accessor '" + methodName + "()'. "
                    + "Rename one of them in the metadata.");
        }
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
}
