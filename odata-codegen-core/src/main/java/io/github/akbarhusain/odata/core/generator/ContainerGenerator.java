package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.ContainerModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.EntitySetModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SingletonModel;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ContainerGenerator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ContainerGenerator.class);

    private final String basePackage;
    private final Map<String, String> schemaPackages;
    private final String defaultBasePackage;

    public ContainerGenerator(String basePackage) {
        this(basePackage, Map.of());
    }

    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages) {
        this(basePackage, schemaPackages, null);
    }

    public ContainerGenerator(String basePackage, Map<String, String> schemaPackages, String defaultBasePackage) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
    }

    public String generate(ContainerModel container, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixContainer();
        String className = Names.containerClassName(container.name());

        // Accessor methods derive from member names; two members folding onto one
        // method (e.g. an EntitySet and a Singleton both named 'People') previously
        // emitted duplicate methods that don't compile — fail loudly instead
        Map<String, String> accessors = new java.util.HashMap<>();
        for (EntitySetModel es : container.entitySets()) {
            checkAccessorCollision(accessors, Names.toJavaFieldName(es.name()), "EntitySet '" + es.name() + "'", className);
        }
        for (SingletonModel singleton : container.singletons()) {
            checkAccessorCollision(accessors, Names.toJavaFieldName(singleton.name()), "Singleton '" + singleton.name() + "'", className);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");

        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = Names.simpleNameFromFullName(es.entityType());
            imports.add(basePackageForType(es.entityType(), schema) + Names.packageNameSuffixCollectionRequest() + "." + Names.collectionRequestClassName(entityClassName));
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            imports.add(basePackageForType(singleton.type(), schema) + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(entityClassName));
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
            String collReqClassName = Names.collectionRequestClassName(entityClassName);
            String methodName = Names.toJavaFieldName(es.name());

            sb.append("    public ").append(collReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(collReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(es.name()).append("\"));\n");
            sb.append("    }\n\n");
        }

        for (var functionImport : container.functionImports()) {
            log.warn("FunctionImport '{}' is not yet supported by the generator; skipping",
                    functionImport.name());
        }
        for (var actionImport : container.actionImports()) {
            log.warn("ActionImport '{}' is not yet supported by the generator; skipping",
                    actionImport.name());
        }

        // Singleton accessors
        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            String entityReqClassName = Names.entityRequestClassName(entityClassName);
            String methodName = Names.toJavaFieldName(singleton.name());

            sb.append("    public ").append(entityReqClassName).append(" ").append(methodName).append("() {\n");
            sb.append("        return new ").append(entityReqClassName)
              .append("(context, context.basePath().addSegment(\"").append(singleton.name()).append("\"));\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static void checkAccessorCollision(Map<String, String> accessors, String methodName,
                                                String memberDescription, String className) {
        String previous = accessors.putIfAbsent(methodName, memberDescription);
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
