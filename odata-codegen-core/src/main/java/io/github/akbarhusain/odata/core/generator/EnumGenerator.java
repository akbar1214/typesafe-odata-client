package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EnumTypeModel;

public class EnumGenerator {

    private final String basePackage;

    public EnumGenerator(String basePackage) {
        this.basePackage = basePackage;
    }

    public String generate(EnumTypeModel enumType) {
        String pkg = basePackage + Names.packageNameSuffixEnum();
        String className = Names.enumClassName(enumType.name());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        sb.append("public enum ").append(className).append(" {\n\n");

        for (int i = 0; i < enumType.members().size(); i++) {
            var member = enumType.members().get(i);
            sb.append("    ").append(member.name()).append("(").append(member.value()).append(")");
            if (i < enumType.members().size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("    ;\n\n");

        sb.append("    private final int value;\n\n");

        sb.append("    ").append(className).append("(int value) {\n");
        sb.append("        this.value = value;\n");
        sb.append("    }\n\n");

        sb.append("    public int getValue() {\n");
        sb.append("        return value;\n");
        sb.append("    }\n\n");

        sb.append("    public static ").append(className).append(" fromValue(int value) {\n");
        sb.append("        for (").append(className).append(" v : values()) {\n");
        sb.append("            if (v.value == value) return v;\n");
        sb.append("        }\n");
        sb.append("        throw new IllegalArgumentException(\"Unknown value: \" + value);\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }
}
