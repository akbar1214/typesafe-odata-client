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
            // Sanitize hostile member names (reserved words, leading digits, dashes);
            // valid identifiers are kept verbatim so existing API names don't change.
            sb.append("    ").append(enumConstantName(member.name())).append("(").append(member.value()).append("L)");
            if (i < enumType.members().size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("    ;\n\n");

        sb.append("    private final long value;\n\n");

        sb.append("    ").append(className).append("(long value) {\n");
        sb.append("        this.value = value;\n");
        sb.append("    }\n\n");

        sb.append("    public long getValue() {\n");
        sb.append("        return value;\n");
        sb.append("    }\n\n");

        sb.append("    public static ").append(className).append(" fromValue(long value) {\n");
        sb.append("        for (").append(className).append(" v : values()) {\n");
        sb.append("            if (v.value == value) return v;\n");
        sb.append("        }\n");
        sb.append("        throw new IllegalArgumentException(\"Unknown value: \" + value);\n");
        sb.append("    }\n\n");

        // Jackson mapping: member-name strings (the OData v4 JSON form) map by name, but
        // NUMERIC payloads must map by CSDL value — Jackson's default maps numbers by
        // ORDINAL, which is wrong whenever member values are not 0..n-1 in declaration order
        sb.append("    @com.fasterxml.jackson.annotation.JsonCreator\n");
        sb.append("    public static ").append(className).append(" fromJson(Object value) {\n");
        sb.append("        if (value instanceof Number n) {\n");
        sb.append("            return fromValue(n.longValue());\n");
        sb.append("        }\n");
        sb.append("        String s = value.toString();\n");
        sb.append("        // tolerate the qualified form Namespace.Enum'Member'\n");
        sb.append("        int quote = s.lastIndexOf('\\'');\n");
        sb.append("        return ").append(className).append(".valueOf(quote >= 0 ? s.substring(quote + 1) : s);\n");
        sb.append("    }\n");

        if (enumType.isFlags()) {
            sb.append("\n");
            sb.append("    public static java.util.Set<").append(className).append("> fromFlags(long value) {\n");
            sb.append("        java.util.Set<").append(className).append("> result = new java.util.HashSet<>();\n");
            sb.append("        for (").append(className).append(" v : values()) {\n");
            sb.append("            if (v.value != 0 && (value & v.value) == v.value) {\n");
            sb.append("                result.add(v);\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return result;\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Returns the member name verbatim when it is already a valid Java identifier
     * (preserving existing generated API), otherwise falls back to the UPPER_SNAKE
     * sanitized form.
     */
    private static String enumConstantName(String memberName) {
        if (!memberName.isEmpty() && Character.isJavaIdentifierStart(memberName.charAt(0))) {
            boolean valid = true;
            for (int i = 1; i < memberName.length(); i++) {
                if (!Character.isJavaIdentifierPart(memberName.charAt(i))) {
                    valid = false;
                    break;
                }
            }
            if (valid && !Names.isJavaKeyword(memberName)) {
                return memberName;
            }
        }
        return Names.toConstantName(memberName);
    }
}
