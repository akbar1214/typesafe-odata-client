package com.modernodata.core.generator;

import com.modernodata.core.model.CsdlModel.SchemaModel;

import java.util.Set;
import java.util.TreeSet;

public class SchemaInfoGenerator {

    private final String basePackage;

    public SchemaInfoGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(SchemaModel schema) {
        String pkg = basePackage + Names.packageNameSuffixSchema();
        String className = Names.schemaInfoClassName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Map");
        imports.add("java.util.HashMap");

        for (var entityType : schema.entityTypes()) {
            imports.add(basePackage + Names.packageNameSuffixEntity() + "." + Names.entityClassName(entityType.name()));
        }
        for (var complexType : schema.complexTypes()) {
            imports.add(basePackage + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(complexType.name()));
        }
        for (var enumType : schema.enumTypes()) {
            imports.add(basePackage + Names.packageNameSuffixEnum() + "." + Names.enumClassName(enumType.name()));
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public class ").append(className)
          .append(" implements com.modernodata.runtime.entity.SchemaInfo {\n\n");
        sb.append("    public static final ").append(className).append(" INSTANCE = new ").append(className).append("();\n\n");

        sb.append("    private final Map<String, Class<?>> classes = new HashMap<>();\n\n");

        sb.append("    private ").append(className).append("() {\n");
        for (var entityType : schema.entityTypes()) {
            String fqn = schema.namespace() + "." + entityType.name();
            sb.append("        classes.put(\"").append(fqn).append("\", ").append(Names.entityClassName(entityType.name())).append(".class);\n");
        }
        for (var complexType : schema.complexTypes()) {
            String fqn = schema.namespace() + "." + complexType.name();
            sb.append("        classes.put(\"").append(fqn).append("\", ").append(Names.complexTypeClassName(complexType.name())).append(".class);\n");
        }
        for (var enumType : schema.enumTypes()) {
            String fqn = schema.namespace() + "." + enumType.name();
            sb.append("        classes.put(\"").append(fqn).append("\", ").append(Names.enumClassName(enumType.name())).append(".class);\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public Class<?> getClassFromTypeWithNamespace(String name) {\n");
        sb.append("        return classes.get(name);\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }
}
