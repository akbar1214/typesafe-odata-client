package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SchemaInfoGenerator {

    private final String basePackage;

    public SchemaInfoGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(SchemaModel schema) {
        return generate(List.of(schema));
    }

    /**
     * Generates one aggregate registry covering every schema mapped to this
     * generator's package. Schemas sharing an output package (the normal case
     * when the Maven plugin passes a single basePackage) must all appear in the
     * same SchemaInfo, or each schema's file silently overwrites the
     * previous one and the registry loses all but the last schema's types.
     */
    public String generate(List<SchemaModel> schemas) {
        String pkg = basePackage + Names.packageNameSuffixSchema();
        String className = Names.schemaInfoClassName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        Set<String> imports = new TreeSet<>();
        imports.add("java.util.Map");
        imports.add("java.util.HashMap");

        for (SchemaModel schema : schemas) {
            for (var entityType : schema.entityTypes()) {
                imports.add(basePackage + Names.packageNameSuffixEntity() + "." + Names.entityClassName(entityType.name()));
            }
            for (var complexType : schema.complexTypes()) {
                imports.add(basePackage + Names.packageNameSuffixComplexType() + "." + Names.complexTypeClassName(complexType.name()));
            }
            for (var enumType : schema.enumTypes()) {
                imports.add(basePackage + Names.packageNameSuffixEnum() + "." + Names.enumClassName(enumType.name()));
            }
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        sb.append("public class ").append(className)
          .append(" implements io.github.akbarhusain.odata.runtime.entity.SchemaInfo {\n\n");
        sb.append("    public static final ").append(className).append(" INSTANCE = new ").append(className).append("();\n\n");

        sb.append("    private final Map<String, Class<?>> classes = new HashMap<>();\n\n");

        sb.append("    private ").append(className).append("() {\n");
        for (SchemaModel schema : schemas) {
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
