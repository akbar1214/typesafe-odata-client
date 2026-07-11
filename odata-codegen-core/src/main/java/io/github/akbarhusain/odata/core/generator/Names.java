package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.List;

public final class Names {

    private Names() {}

    public static String packageName(String namespace, String suffix) {
        return toPackageName(namespace) + suffix;
    }

    public static String toPackageName(String namespace) {
        return namespace.toLowerCase().replace(".", "_").replace("-", "_");
    }

    public static String entityClassName(String edmTypeName) {
        return sanitizeClassName(edmTypeName);
    }

    public static String enumClassName(String edmTypeName) {
        return sanitizeClassName(edmTypeName);
    }

    public static String complexTypeClassName(String edmTypeName) {
        return sanitizeClassName(edmTypeName);
    }

    public static String entityRequestClassName(String edmTypeName) {
        return sanitizeClassName(edmTypeName) + "EntityRequest";
    }

    public static String collectionRequestClassName(String edmTypeName) {
        return sanitizeClassName(edmTypeName) + "CollectionRequest";
    }

    public static String containerClassName(String containerName) {
        return sanitizeClassName(containerName);
    }

    public static String schemaInfoClassName() {
        return "ServiceSchemaInfo";
    }

    public static String packageNameSuffixEntity() {
        return ".entity";
    }

    public static String packageNameSuffixEnum() {
        return ".enums";
    }

    public static String packageNameSuffixComplexType() {
        return ".complex";
    }

    public static String packageNameSuffixEntityRequest() {
        return ".entity.request";
    }

    public static String packageNameSuffixCollectionRequest() {
        return ".collection.request";
    }

    public static String packageNameSuffixContainer() {
        return ".container";
    }

    public static String packageNameSuffixSchema() {
        return ".schema";
    }

    public static String toJavaFieldName(String edmName) {
        String name = sanitizeIdentifier(edmName);
        String result = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        if (isReservedWord(result)) result = result + "_";
        return result;
    }

    public static String toConstantName(String edmName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < edmName.length(); i++) {
            char c = edmName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(edmName.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    public static String toJavaMethodName(String edmName, String prefix) {
        String name = sanitizeIdentifier(edmName);
        return prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public static String getterMethod(PropertyModel prop) {
        String name = "get" + capitalize(sanitizeIdentifier(prop.name()));
        if (isObjectMethodName(name)) name = name + "_";
        return name;
    }

    public static String withMethod(PropertyModel prop) {
        return "with" + capitalize(sanitizeIdentifier(prop.name()));
    }

    public static String builderMethodName(String entityName) {
        return "builder" + capitalize(entityName);
    }

    public static String simpleNameFromFullName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    public static String namespaceFromFullName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(0, lastDot) : "";
    }

    public static boolean isCollectionType(String edmType) {
        return edmType.startsWith("Collection(");
    }

    public static String unwrapCollectionType(String edmType) {
        if (isCollectionType(edmType)) {
            return edmType.substring("Collection(".length(), edmType.length() - 1);
        }
        return edmType;
    }

    public static boolean isPrimitiveType(String edmType) {
        return edmType.startsWith("Edm.");
    }

    public static String edmTypeToSimpleJavaType(String edmType) {
        return switch (edmType) {
            case "Edm.String", "Edm.Guid" -> "String";
            case "Edm.Boolean" -> "Boolean";
            case "Edm.Byte", "Edm.SByte" -> "Byte";
            case "Edm.Int16" -> "Short";
            case "Edm.Int32" -> "Integer";
            case "Edm.Int64" -> "Long";
            case "Edm.Single" -> "Float";
            case "Edm.Double" -> "Double";
            case "Edm.Decimal" -> "java.math.BigDecimal";
            case "Edm.Date" -> "java.time.LocalDate";
            case "Edm.DateTimeOffset" -> "java.time.OffsetDateTime";
            case "Edm.Duration" -> "java.time.Duration";
            case "Edm.TimeOfDay" -> "java.time.LocalTime";
            case "Edm.Binary" -> "byte[]";
            default -> "Object";
        };
    }

    public static boolean isStringType(String edmType) {
        return "Edm.String".equals(edmType) || "Edm.Guid".equals(edmType);
    }

    public static boolean isNumericType(String edmType) {
        return switch (edmType) {
            case "Edm.Int16", "Edm.Int32", "Edm.Int64",
                 "Edm.Single", "Edm.Double", "Edm.Decimal",
                 "Edm.Byte", "Edm.SByte" -> true;
            default -> false;
        };
    }

    public static boolean isBooleanType(String edmType) {
        return "Edm.Boolean".equals(edmType);
    }

    public static boolean isDateTimeType(String edmType) {
        return switch (edmType) {
            case "Edm.DateTimeOffset", "Edm.Date", "Edm.Duration", "Edm.TimeOfDay" -> true;
            default -> false;
        };
    }

    public static String unqualifyType(String edmType, String currentNamespace) {
        if (edmType.contains(".")) return edmType;
        return currentNamespace + "." + edmType;
    }

    private static String sanitizeClassName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0 && Character.isJavaIdentifierStart(c)) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else if (c == '.' || c == '/') {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (isReservedWord(result) || isJdkClassName(result)) result = result + "_";
        return result;
    }

    // JDK class names that would shadow java.lang.* if used as generated class names.
    private static final java.util.Set<String> JDK_CLASS_NAMES = java.util.Set.of(
            "Object", "String", "System", "Class", "Number", "Enum",
            "Record", "Void", "Math", "Thread", "Throwable", "Error",
            "Exception", "Runnable", "Comparable", "Iterable", "Override",
            "Deprecated", "SuppressWarnings", "SafeVarargs", "FunctionalInterface"
    );

    private static boolean isJdkClassName(String name) {
        return JDK_CLASS_NAMES.contains(name);
    }

    private static String sanitizeIdentifier(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0 && Character.isJavaIdentifierStart(c)) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        if (isReservedWord(result)) result = result + "_";
        return result;
    }

    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isReservedWord(String word) {
        return switch (word) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                 "char", "class", "const", "default", "do", "double", "else",
                 "enum", "extends", "final", "finally", "float", "for", "goto",
                 "if", "implements", "import", "instanceof", "int", "interface",
                 "long", "native", "new", "package", "private", "protected",
                 "public", "return", "short", "static", "strictfp", "super",
                 "switch", "synchronized", "this", "throw", "throws", "transient",
                 "try", "void", "volatile", "while", "true", "false", "null",
                 "var", "record", "sealed", "permits", "yield", "module",
                 "open", "requires", "exports", "opens", "to", "with" -> true;
            default -> false;
        };
    }

    // Object methods that cannot be overridden (final) or would break identity contracts.
    private static final java.util.Set<String> OBJECT_METHOD_NAMES = java.util.Set.of(
            "getClass", "hashCode", "equals", "toString", "clone",
            "notify", "notifyAll", "wait"
    );

    private static boolean isObjectMethodName(String name) {
        return OBJECT_METHOD_NAMES.contains(name);
    }

    public enum TypeKind { ENTITY, COMPLEX, ENUM, UNKNOWN }

    public static TypeKind resolveTypeKind(String edmType, List<SchemaModel> allSchemas) {
        String simpleName = simpleNameFromFullName(edmType);
        String namespace = namespaceFromFullName(edmType);
        for (SchemaModel s : allSchemas) {
            if (namespace.isEmpty() || s.namespace().equals(namespace)) {
                if (s.entityTypes().stream().anyMatch(e -> e.name().equals(simpleName))) return TypeKind.ENTITY;
                if (s.complexTypes().stream().anyMatch(c -> c.name().equals(simpleName))) return TypeKind.COMPLEX;
                if (s.enumTypes().stream().anyMatch(e -> e.name().equals(simpleName))) return TypeKind.ENUM;
            }
        }
        return TypeKind.UNKNOWN;
    }

    public static String resolvedClassName(String edmType, List<SchemaModel> allSchemas) {
        String simpleName = simpleNameFromFullName(edmType);
        return switch (resolveTypeKind(edmType, allSchemas)) {
            case ENTITY -> entityClassName(simpleName);
            case COMPLEX -> complexTypeClassName(simpleName);
            case ENUM -> enumClassName(simpleName);
            case UNKNOWN -> entityClassName(simpleName);
        };
    }

    public static String resolvedSuffix(String edmType, List<SchemaModel> allSchemas) {
        return switch (resolveTypeKind(edmType, allSchemas)) {
            case ENTITY -> packageNameSuffixEntity();
            case COMPLEX -> packageNameSuffixComplexType();
            case ENUM -> packageNameSuffixEnum();
            case UNKNOWN -> packageNameSuffixEntity();
        };
    }
}
