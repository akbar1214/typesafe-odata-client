package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel.ContainerModel;
import com.modernodata.core.model.CsdlModel.EntitySetModel;
import com.modernodata.core.model.CsdlModel.SchemaModel;
import com.modernodata.core.model.CsdlModel.SingletonModel;

import java.util.Set;
import java.util.TreeSet;

public class ContainerGenerator {

    private final String basePackage;

    public ContainerGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(ContainerModel container, SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixContainer();
        String className = Names.containerClassName(container.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("com.modernodata.runtime.entity.Context");

        for (EntitySetModel es : container.entitySets()) {
            String entityClassName = Names.simpleNameFromFullName(es.entityType());
            imports.add(basePackage + Names.packageNameSuffixCollectionRequest() + "." + Names.collectionRequestClassName(entityClassName));
        }

        for (SingletonModel singleton : container.singletons()) {
            String entityClassName = Names.simpleNameFromFullName(singleton.type());
            imports.add(basePackage + Names.packageNameSuffixEntityRequest() + "." + Names.entityRequestClassName(entityClassName));
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
}
