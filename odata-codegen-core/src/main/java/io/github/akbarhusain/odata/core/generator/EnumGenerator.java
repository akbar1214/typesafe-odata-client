package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EnumTypeModel;

import java.util.ArrayList;
import java.util.List;

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

        // ODataEnumValue exposes each member's CSDL wire name to the runtime literal
        // formatters — sanitized Java identifiers must never reach a URL
        sb.append("public enum ").append(className)
          .append(" implements io.github.akbarhusain.odata.runtime.entity.ODataEnumValue {\n\n");

        // members whose CSDL name differs from the sanitized Java constant need a
        // wire-name map so JSON round-trips keep using the original member names
        // Also dedupe collisions: A-B -> A_B collides with verbatim A_B
        List<String[]> renamed = new ArrayList<>();
        java.util.Map<String, Integer> usedCount = new java.util.HashMap<>();
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        List<String> constants = new ArrayList<>();
        for (var member : enumType.members()) {
            String base = enumConstantName(member.name());
            String constant = base;
            if (usedNames.contains(constant)) {
                int suffix = usedCount.getOrDefault(base, 1) + 1;
                while (usedNames.contains(base + "_" + suffix)) suffix++;
                constant = base + "_" + suffix;
                usedCount.put(base, suffix);
            } else {
                usedCount.putIfAbsent(base, 1);
            }
            usedNames.add(constant);
            constants.add(constant);
            if (!constant.equals(member.name())) {
                renamed.add(new String[]{member.name(), constant});
            }
        }
        for (int i = 0; i < enumType.members().size(); i++) {
            var member = enumType.members().get(i);
            String constant = constants.get(i);
            // each constant records its CSDL member name — the wire name for URL literals
            sb.append("    ").append(constant).append("(").append(member.value()).append("L, \"")
              .append(escapeJava(member.name())).append("\")");
            if (i < enumType.members().size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("    ;\n\n");

        boolean hasRenamed = !renamed.isEmpty();
        if (hasRenamed) {
            sb.append("    // CSDL member name -> sanitized constant (JSON wire names)\n");
            sb.append("    private static final java.util.Map<String, ").append(className).append("> BY_NAME =\n");
            sb.append("            java.util.Map.ofEntries(\n");
            for (int i = 0; i < renamed.size(); i++) {
                sb.append("                    java.util.Map.entry(\"").append(renamed.get(i)[0]).append("\", ")
                  .append(renamed.get(i)[1]).append(")").append(i < renamed.size() - 1 ? "," : "").append("\n");
            }
            sb.append("            );\n\n");
        }

        sb.append("    private final long value;\n\n");
        sb.append("    private final String wireName;\n\n");

        sb.append("    ").append(className).append("(long value, String wireName) {\n");
        sb.append("        this.value = value;\n");
        sb.append("        this.wireName = wireName;\n");
        sb.append("    }\n\n");

        sb.append("    public long getValue() {\n");
        sb.append("        return value;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public String wireName() {\n");
        sb.append("        return wireName;\n");
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
        sb.append("        String name = quote >= 0 ? s.substring(quote + 1) : s;\n");
        if (hasRenamed) {
            sb.append("        try {\n");
            sb.append("            return ").append(className).append(".valueOf(name);\n");
            sb.append("        } catch (IllegalArgumentException notFound) {\n");
            sb.append("            // sanitized members: the JSON wire name is the CSDL member name\n");
            sb.append("            ").append(className).append(" mapped = BY_NAME.get(name);\n");
            sb.append("            if (mapped != null) return mapped;\n");
            sb.append("            throw notFound;\n");
            sb.append("        }\n");
        } else {
            sb.append("        return ").append(className).append(".valueOf(name);\n");
        }
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

    /** Escapes a CSDL name for a Java string literal (wire names go into generated source). */
    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
