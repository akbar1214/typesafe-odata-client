package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.NavigationPropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared infrastructure for {@link EntityGenerator} and {@link ComplexTypeGenerator}.
 * Extracts duplicated helpers for type resolution, import collection, navigation
 * property handling, and the typed {@code Filterable} inner class used by collection
 * lambda operators ({@code any}/{@code all}).
 */
public abstract class AbstractTypeGenerator {

    protected final String basePackage;
    protected final Map<String, String> schemaPackages;
    protected final String defaultBasePackage;
    protected final List<SchemaModel> allSchemas;
    protected List<SchemaModel> effectiveSchemas;
    private boolean effectiveSchemasInitialized;

    protected AbstractTypeGenerator(String basePackage, Map<String, String> schemaPackages,
                                    String defaultBasePackage, List<SchemaModel> allSchemas) {
        this.basePackage = basePackage;
        this.schemaPackages = schemaPackages;
        this.defaultBasePackage = defaultBasePackage;
        this.allSchemas = allSchemas;
    }

    /**
     * Initializes {@code effectiveSchemas} once with a stable reference so the
     * {@code Names.resolveTypeKind} cache key (the list object identity) stays
     * consistent across all types within the same schema.
     */
    protected void initEffectiveSchemas(SchemaModel schema) {
        if (!effectiveSchemasInitialized) {
            effectiveSchemasInitialized = true;
            effectiveSchemas = allSchemas.isEmpty() ? List.of(schema) : allSchemas;
        }
    }

    // ------------------------------------------------------------------
    // Type resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a property's Java type. Collection elements are always boxed.
     * The {@code boxed} flag controls scalar primitive types only.
     */
    protected String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema, boolean boxed) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema, true) + ">";
        }
        return resolveSingleJavaType(edmType, schema, boxed);
    }

    /**
     * Resolves a property's Java type with scalar primitives boxed.
     * Used by complex-type generation where fields and builders use reference types.
     */
    protected String resolvePropertyJavaType(PropertyModel prop, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            return "List<" + resolveSingleJavaType(elementType, schema) + ">";
        }
        return resolveSingleJavaType(edmType, schema);
    }

    protected String resolveSingleJavaType(String edmType, SchemaModel schema, boolean boxed) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            String javaType = Names.edmTypeToSimpleJavaType(resolved);
            if (boxed) {
                return javaType;
            }
            return switch (javaType) {
                case "Boolean" -> "boolean";
                case "Integer" -> "int";
                case "Long" -> "long";
                case "Float" -> "float";
                case "Double" -> "double";
                case "Byte" -> "byte";
                case "Short" -> "short";
                default -> javaType;
            };
        }
        return Names.resolvedClassName(resolved, effectiveSchemas);
    }

    protected String resolveSingleJavaType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return Names.resolvedClassName(resolved, effectiveSchemas);
    }

    private java.util.Map<String, String> typeDefCache;

    // Resolve TypeDefinition to its underlying Edm type (recursively)
    protected String resolveTypeDefinition(String edmType, SchemaModel schema) {
        if (Names.isPrimitiveType(edmType)) return edmType;
        if (typeDefCache == null) {
            typeDefCache = new java.util.HashMap<>();
            for (var td : schema.typeDefinitions()) {
                typeDefCache.put(td.name(), resolveTypeDefinition(td.underlyingType(), schema));
            }
        }
        String simpleName = Names.simpleNameFromFullName(edmType);
        String resolved = typeDefCache.get(simpleName);
        return resolved != null ? resolved : edmType;
    }

    // ------------------------------------------------------------------
    // Imports
    // ------------------------------------------------------------------

    protected void addPropertyImports(PropertyModel prop, Set<String> imports, SchemaModel schema) {
        String edmType = resolveTypeDefinition(prop.edmType(), schema);
        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String resolvedElement = resolveTypeDefinition(elementType, schema);
            if (Names.isPrimitiveType(resolvedElement)) {
                String javaType = Names.edmTypeToSimpleJavaType(resolvedElement);
                if (javaType.startsWith("java.")) imports.add(javaType);
            } else {
                String pkg = basePackageForType(resolvedElement, schema);
                imports.add(pkg + Names.resolvedSuffix(resolvedElement, effectiveSchemas) + "."
                        + Names.resolvedClassName(resolvedElement, effectiveSchemas));
            }
        } else if (Names.isPrimitiveType(edmType)) {
            String javaType = Names.edmTypeToSimpleJavaType(edmType);
            if (javaType.startsWith("java.")) imports.add(javaType);
        } else {
            String pkg = basePackageForType(edmType, schema);
            imports.add(pkg + Names.resolvedSuffix(edmType, effectiveSchemas) + "."
                    + Names.resolvedClassName(edmType, effectiveSchemas));
        }
    }

    protected void addNavImports(NavigationPropertyModel nav, Set<String> imports, SchemaModel schema) {
        String edmType = Names.unwrapCollectionType(nav.type());
        String suffix = Names.resolvedSuffix(edmType, effectiveSchemas);
        String className = Names.resolvedClassName(edmType, effectiveSchemas);
        imports.add(basePackageForType(edmType, schema) + suffix + "." + className);
    }

    // Look up the base package for a cross-namespace type reference
    protected String basePackageForType(String edmType, SchemaModel schema) {
        String namespace = Names.namespaceFromFullName(edmType);
        if (namespace.isEmpty() || namespace.equals(schema.namespace())) {
            return basePackage;
        }
        return schemaPackages.getOrDefault(namespace,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(namespace));
    }

    // ------------------------------------------------------------------
    // Navigation properties
    // ------------------------------------------------------------------

    protected String navJavaType(NavigationPropertyModel nav, SchemaModel schema) {
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.resolvedClassName(unwrapped, effectiveSchemas);
        if (Names.isCollectionType(nav.type())) {
            return "List<" + elementClassName + ">";
        }
        return elementClassName;
    }

    protected String navGetterName(NavigationPropertyModel nav) {
        return Names.navGetterMethod(nav.name());
    }

    protected String navWithMethod(NavigationPropertyModel nav) {
        return Names.navWithMethod(nav.name());
    }

    protected String generateNavGetter(NavigationPropertyModel nav, SchemaModel schema) {
        String javaType = navJavaType(nav, schema);
        String fn = Names.toJavaFieldName(nav.name());
        StringBuilder sb = new StringBuilder();
        if (Names.isCollectionType(nav.type())) {
            sb.append("    public ").append(javaType).append(" ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return ").append(fn).append(" == null ? List.of() : Collections.unmodifiableList(").append(fn).append(");\n");
            sb.append("    }\n\n");
        } else {
            sb.append("    public Optional<").append(javaType).append("> ").append(navGetterName(nav)).append("() {\n");
            sb.append("        return Optional.ofNullable(").append(fn).append(");\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Filterable inner class for collection lambdas (any/all)
    // ------------------------------------------------------------------

    protected String generateFilterableClass(List<PropertyModel> allProps,
                                             List<NavigationPropertyModel> allNavs,
                                             String className,
                                             SchemaModel schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public static class Filterable {\n");
        for (PropertyModel prop : allProps) {
            sb.append(generateFilterablePropertyField(prop, className, schema));
        }
        for (NavigationPropertyModel nav : allNavs) {
            if (Names.isCollectionType(nav.type())) {
                sb.append(generateFilterableNavPropertyField(nav, className, schema));
            }
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    protected String generateFilterablePropertyField(PropertyModel prop, String className, SchemaModel schema) {
        String edmType = prop.edmType();
        String constantName = Names.toConstantName(prop.name());

        if (Names.isCollectionType(edmType)) {
            String elementType = Names.unwrapCollectionType(edmType);
            String elementClassName = resolveClassNameForConstant(elementType, schema);
            Names.TypeKind kind = Names.resolveTypeKind(elementType, effectiveSchemas);
            if (kind == Names.TypeKind.ENTITY || kind == Names.TypeKind.COMPLEX) {
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", " + elementClassName + ".Filterable> " + constantName
                        + " = new CollectionProperty<>(\"x/" + prop.name() + "\", " + className + ".class, "
                        + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
            } else {
                return "    public final CollectionProperty<" + className + ", " + elementClassName
                        + ", CollectionProperty.FilterableElement<" + elementClassName + ">> " + constantName
                        + " = new CollectionProperty<>(\"x/" + prop.name() + "\", " + className + ".class, "
                        + elementClassName + ".class, CollectionProperty.FilterableElement::new);\n";
            }
        }

        String constantType = getPropertyConstantType(edmType, schema);
        if (constantType == null) {
            return ""; // Binary, Stream, Geography, Geometry — not filterable
        }
        String typeParams = switch (constantType) {
            case "EnumProperty" -> "<" + className + ", " + resolveClassNameForConstant(edmType, schema) + ">";
            case "NumberProperty" -> "<" + className + ", " + getNumberJavaType(edmType) + ">";
            default -> "<" + className + ">";
        };

        return "    public final " + constantType + typeParams + " " + constantName
                + " = new " + constantType + "<>(\"x/" + prop.name() + "\", " + className + ".class"
                + (constantType.equals("EnumProperty") ? ", " + resolveClassNameForConstant(edmType, schema) + ".class, \"" + edmType + "\"" : "")
                + ");\n";
    }

    protected String generateFilterableNavPropertyField(NavigationPropertyModel nav, String className, SchemaModel schema) {
        String unwrapped = Names.unwrapCollectionType(nav.type());
        String elementClassName = Names.resolvedClassName(unwrapped, effectiveSchemas);
        String constantName = Names.toConstantName(nav.name());
        return "    public final CollectionProperty<" + className + ", "
                + elementClassName + ", " + elementClassName + ".Filterable> " + constantName
                + " = new CollectionProperty<>(\"x/" + nav.name() + "\", " + className + ".class, "
                + elementClassName + ".class, " + elementClassName + ".Filterable::new);\n";
    }

    protected String resolveClassNameForConstant(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isPrimitiveType(resolved)) {
            return Names.edmTypeToSimpleJavaType(resolved);
        }
        return Names.resolvedClassName(resolved, effectiveSchemas);
    }

    protected String getNumberJavaType(String edmType) {
        return Names.edmTypeToSimpleJavaType(edmType);
    }

    protected String getPropertyConstantType(String edmType, SchemaModel schema) {
        String resolved = resolveTypeDefinition(edmType, schema);
        if (Names.isStringType(resolved)) return "StringProperty";
        if (Names.isBooleanType(resolved)) return "BooleanProperty";
        if (Names.isDateTimeType(resolved)) return "DateTimeProperty";
        if (isEnumType(resolved, schema)) return "EnumProperty";
        if (Names.isNumericType(resolved)) return "NumberProperty";
        return null; // Binary, Stream, Geography, Geometry — not filterable, no constant
    }

    protected boolean isEnumType(String edmType, SchemaModel schema) {
        return Names.resolveTypeKind(edmType, effectiveSchemas) == Names.TypeKind.ENUM;
    }
}
