package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.ActionImportModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ActionModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ParameterModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.ReturnTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.FunctionImportModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.FunctionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates request classes for unbound container function/action imports
 * (request-object style). Each import produces a {@code final}
 * {@code <Name>FunctionRequest} / {@code <Name>ActionRequest} in the
 * {@code .operation} package. Functions invoke via GET with typed parameters
 * embedded in the URL fragment ({@code Name(p1=v1,p2=v2)}); actions invoke via
 * POST with a JSON parameter body built by
 * {@link io.github.akbarhusain.odata.runtime.client.EntityOperations#buildActionBody}.
 * Nullable return types wrap in {@code Optional<T>}; collection results render
 * {@code List<Element>} (empty on absent — never null).
 */
public class OperationGenerator extends AbstractTypeGenerator {

    private enum Kind { VOID, PRIMITIVE_SINGLE, PRIMITIVE_COLLECTION, OBJECT_SINGLE, OBJECT_COLLECTION }

    /** A model plus the schema that owns it (needed for type/package resolution). */
    private record Owned<T>(T model, SchemaModel owner) {}

    private record ResolvedOp(String name, List<ParameterModel> parameters,
                              ReturnTypeModel returnType, boolean isFunction, SchemaModel owner) {}

    /** Java type + class-literal reference + required import for structured result types. */
    private record ResultClass(String simpleName, String classRef, String importLine) {}

    public OperationGenerator(String basePackage, Map<String, String> schemaPackages,
                              String defaultBasePackage, List<SchemaModel> allSchemas) {
        super(basePackage, schemaPackages, defaultBasePackage, allSchemas);
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    public String generateFunctionImportRequest(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<FunctionModel> owned = resolveFunction(fi.function(), fi.name());
        FunctionModel fn = owned.model();
        validateFunctionParameters(fn.parameters(), fi.name(), owned.owner());
        ResolvedOp op = new ResolvedOp(fn.name(), fn.parameters(), fn.returnType(), true, owned.owner());
        return render(fi.name(), Names.functionRequestClassName(fi.name()), op);
    }

    public String generateActionImportRequest(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveAction(ai.action(), ai.name());
        ActionModel ac = owned.model();
        ResolvedOp op = new ResolvedOp(ac.name(), ac.parameters(), ac.returnType(), false, owned.owner());
        return render(ai.name(), Names.actionRequestClassName(ai.name()), op);
    }

    // ------------------------------------------------------------------
    // Cross-schema operation resolution
    // ------------------------------------------------------------------

    /**
     * Import references are alias-resolved to real namespaces at parse time. Qualified
     * references win over simple-name references; a lone simple-name match is accepted,
     * any ambiguity throws loudly (one ambiguity policy everywhere — lessons H11/M2/156).
     * A resolved function must additionally be UNBOUND.
     */
    private Owned<FunctionModel> resolveFunction(String reference, String importLabel) {
        List<Owned<FunctionModel>> qualified = new ArrayList<>();
        List<Owned<FunctionModel>> simple = new ArrayList<>();
        for (SchemaModel s : effectiveSchemas) {
            for (FunctionModel f : s.functions()) {
                classify(new Owned<>(f, s), s.namespace() + "." + f.name(), f.name(),
                        reference, qualified, simple);
            }
        }
        Owned<FunctionModel> chosen = pick(qualified, simple, importLabel, reference, "FunctionImport");
        if (chosen.model().isBound()) {
            throw new IllegalStateException("FunctionImport '" + importLabel
                    + "' references bound function '" + reference
                    + "' — function imports must reference UNBOUND operations");
        }
        return chosen;
    }

    private Owned<ActionModel> resolveAction(String reference, String importLabel) {
        List<Owned<ActionModel>> qualified = new ArrayList<>();
        List<Owned<ActionModel>> simple = new ArrayList<>();
        for (SchemaModel s : effectiveSchemas) {
            for (ActionModel a : s.actions()) {
                classify(new Owned<>(a, s), s.namespace() + "." + a.name(), a.name(),
                        reference, qualified, simple);
            }
        }
        return pick(qualified, simple, importLabel, reference, "ActionImport");
    }

    private <T> void classify(Owned<T> candidate, String fullName, String simpleName,
                              String reference,
                              List<Owned<T>> qualifiedOut, List<Owned<T>> simpleOut) {
        if (fullName.equals(reference)) {
            qualifiedOut.add(candidate);
        } else if (simpleName.equals(reference)) {
            simpleOut.add(candidate);
        }
    }

    private <T> Owned<T> pick(List<Owned<T>> qualified, List<Owned<T>> simple,
                              String importLabel, String reference, String kindLabel) {
        if (qualified.size() > 1) {
            throw ambiguous(importLabel, reference);
        }
        if (!qualified.isEmpty()) {
            return qualified.get(0);
        }
        if (simple.size() == 1) {
            return simple.get(0);
        }
        if (simple.isEmpty()) {
            throw new IllegalStateException(kindLabel + " '" + importLabel
                    + "' references unknown operation '" + reference + "'");
        }
        throw ambiguous(importLabel, reference);
    }

    private IllegalStateException ambiguous(String importLabel, String reference) {
        return new IllegalStateException("Ambiguous operation reference '" + reference
                + "' on import '" + importLabel + "' — use the namespace-qualified form");
    }

    private void validateFunctionParameters(List<ParameterModel> parameters, String importName,
                                            SchemaModel owner) {
        for (ParameterModel p : parameters) {
            String raw = Names.isCollectionType(p.type())
                    ? Names.unwrapCollectionType(p.type()) : p.type();
            String resolved = resolveTypeDefinition(raw, owner);
            boolean primitive = Names.isPrimitiveType(resolved);
            boolean enumType = !primitive
                    && Names.resolveTypeKind(resolved, effectiveSchemas) == Names.TypeKind.ENUM;
            if (!primitive && !enumType) {
                throw new IllegalStateException("FunctionImport '" + importName
                        + "': parameter '" + p.name() + "' of type '" + p.type()
                        + "' cannot be embedded in an invocation URL (only Edm primitives "
                        + "and enums are legal function-parameter literals)");
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private String render(String importName, String className, ResolvedOp op) {
        Kind kind = resultKind(op);
        boolean isAction = !op.isFunction();

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.client.EntityOperations");
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.entity.OperationPath");
        imports.add("io.github.akbarhusain.odata.runtime.http.HttpMethod");

        String verb = op.isFunction() ? "GET" : "POST";
        String bodyArg = isAction && !op.parameters().isEmpty() ? "body" : "null";
        boolean nullableResult = resultNullable(op);

        StringBuilder methods = new StringBuilder(2048);
        switch (kind) {
            case VOID -> methods.append(voidExecute(verb, bodyArg));
            case PRIMITIVE_SINGLE -> {
                String t = primitiveResultJavaType(op);
                methods.append(primitiveSingleMethods(t, nullableResult, verb, bodyArg));
            }
            case PRIMITIVE_COLLECTION -> {
                String el = primitiveElementJavaType(op);
                methods.append(primitiveCollectionMethods(el, verb, bodyArg));
                imports.add("java.util.List");
            }
            case OBJECT_SINGLE -> {
                ResultClass rc = objectResult(returnTypeOf(op), op, imports);
                methods.append(objectSingleMethods(rc, nullableResult, verb, bodyArg));
            }
            case OBJECT_COLLECTION -> {
                ResultClass rc = objectResult(collectionElementType(op), op, imports);
                methods.append(objectCollectionMethods(rc));
                imports.add("java.util.List");
            }
        }
        boolean hasReturn = kind != Kind.VOID;
        if (hasReturn) {
            imports.add("java.util.concurrent.CompletableFuture");
        }
        if (nullableResult && hasReturn) {
            imports.add("java.util.Optional");
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("package ").append(outputPackage(op)).append(";\n\n");
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n/** Invokes ")
          .append(isAction ? "action" : "function")
          .append(" import \"").append(importName).append("\".\n */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private final Context context;\n\n");
        sb.append("    private final ContextPath contextPath;\n\n");
        if (isAction) {
            sb.append("    private final byte[] body;\n\n");
        }
        sb.append("    public ").append(className).append("(Context context")
          .append(constructorParams(op)).append(") {\n");
        sb.append("        this.context = context;\n");
        sb.append(constructorBody(importName, op, isAction));
        if (isAction) {
            sb.append("        this.body = ").append(bodyArg.equals("body") ? "__body" : "null").append(";\n");
        }
        sb.append("    }\n\n");
        sb.append(methods);
        sb.append("}\n");
        return sb.toString();
    }

    private String outputPackage(ResolvedOp op) {
        String ns = op.owner().namespace();
        String base = schemaPackages.getOrDefault(ns,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(ns));
        return base + Names.packageNameSuffixOperation();
    }

    // ------------------------------------------------------------------
    // Constructors: typed URL literals (functions) / JSON body (actions)
    // ------------------------------------------------------------------

    private String constructorParams(ResolvedOp op) {
        StringBuilder sb = new StringBuilder();
        for (ParameterModel p : op.parameters()) {
            sb.append(", ").append(parameterJavaType(p, op))
              .append(' ').append(Names.toJavaFieldName(p.name()));
        }
        return sb.toString();
    }

    private String constructorBody(String importName, ResolvedOp op, boolean isAction) {
        StringBuilder b = new StringBuilder();
        if (!isAction) {
            b.append("        java.util.List<String> __pairs = new java.util.ArrayList<>();\n");
            for (ParameterModel p : op.parameters()) {
                String field = Names.toJavaFieldName(p.name());
                String literalEdmType = qualifiedEdmName(
                        resolveTypeDefinition(p.type(), op.owner()), op.owner());
                if (p.nullable()) {
                    b.append("        if (").append(field).append(" != null) {\n");
                    appendPairAdd(b, field, literalEdmType, "            ");
                    b.append("        }\n");
                } else {
                    appendRequiredGuard(b, p, field, op);
                    appendPairAdd(b, field, literalEdmType, "        ");
                }
            }
            b.append("        this.contextPath = context.basePath().addSegment(OperationPath.segment(\"")
              .append(importName).append("\", __pairs.toArray(new String[0])));\n");
            return b.toString();
        }

        for (ParameterModel p : op.parameters()) {
            appendRequiredGuard(b, p, Names.toJavaFieldName(p.name()), op);
        }
        b.append("        this.contextPath = context.basePath().addSegment(\"")
          .append(importName).append("\");\n");
        b.append("        java.util.Map<String, Object> __params = new java.util.LinkedHashMap<>();\n");
        for (ParameterModel p : op.parameters()) {
            String field = Names.toJavaFieldName(p.name());
            if (p.nullable()) {
                b.append("        if (").append(field).append(" != null) {\n")
                 .append("            __params.put(\"").append(p.name()).append("\", ").append(field).append(");\n")
                 .append("        }\n");
            } else {
                b.append("        __params.put(\"").append(p.name()).append("\", ").append(field).append(");\n");
            }
        }
        if (op.parameters().isEmpty()) {
            b.append("        byte[] __body = null;\n");
        } else {
            b.append("        byte[] __body = EntityOperations.buildActionBody(__params);\n");
        }
        return b.toString();
    }

    private static void appendPairAdd(StringBuilder b, String field, String literalEdmType,
                                      String indent) {
        b.append(indent).append("__pairs.add(\"").append(field)
          .append("=\" + OperationPath.parameter(").append(field)
          .append(", \"").append(literalEdmType).append("\"));\n");
    }

    private void appendRequiredGuard(StringBuilder b, ParameterModel p, String field, ResolvedOp op) {
        String javaType = parameterJavaType(p, op);
        if (!p.nullable() && !isJavaPrimitive(javaType)) {
            b.append("        if (").append(field)
             .append(" == null) {\n")
             .append("            throw new IllegalArgumentException(\"Parameter '")
             .append(p.name()).append("' is non-nullable and must not be null\");\n")
             .append("        }\n");
        }
    }

    private static boolean isJavaPrimitive(String javaType) {
        return switch (javaType) {
            case "boolean", "int", "long", "short", "byte", "float", "double" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Execute-method templates per result kind
    // ------------------------------------------------------------------

    private static String voidExecute(String verb, String bodyArg) {
        return "    public void execute() {\n"
             + "        EntityOperations.invokeVoidSync(context, contextPath, HttpMethod." + verb
             + ", " + bodyArg + ");\n"
             + "    }\n\n";
    }

    private String primitiveSingleMethods(String t, boolean nullable, String verb, String bodyArg) {
        String call = "EntityOperations.invokePrimitiveSync(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + t + ".class)";
        String asyncCall = "EntityOperations.invokePrimitiveAsync(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + t + ".class)";
        if (nullable) {
            return "    public Optional<" + t + "> execute() {\n"
                 + "        return Optional.ofNullable(" + call + ");\n"
                 + "    }\n\n"
                 + "    public CompletableFuture<Optional<" + t + ">> executeAsync() {\n"
                 + "        return " + asyncCall + ".thenApply(Optional::ofNullable);\n"
                 + "    }\n\n";
        }
        return "    public " + t + " execute() {\n"
             + "        return " + call + ";\n"
             + "    }\n\n"
             + "    public CompletableFuture<" + t + "> executeAsync() {\n"
             + "        return " + asyncCall + ";\n"
             + "    }\n\n";
    }

    private static String primitiveCollectionMethods(String elementType, String verb, String bodyArg) {
        return "    public List<" + elementType + "> execute() {\n"
             + "        return EntityOperations.invokePrimitiveCollectionSync(context, contextPath,\n"
             + "                HttpMethod." + verb + ", " + bodyArg + ", "
             + elementType + ".class);\n"
             + "    }\n\n";
    }

    private String objectSingleMethods(ResultClass rc, boolean nullable, String verb, String bodyArg) {
        String call = "EntityOperations.invokeSync(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + rc.classRef() + ")";
        String asyncCall = "EntityOperations.invokeAsync(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + rc.classRef() + ")";
        if (nullable) {
            return "    public Optional<" + rc.simpleName() + "> execute() {\n"
                 + "        return Optional.ofNullable(" + call + ");\n"
                 + "    }\n\n"
                 + "    public CompletableFuture<Optional<" + rc.simpleName() + ">> executeAsync() {\n"
                 + "        return " + asyncCall + ".thenApply(Optional::ofNullable);\n"
                 + "    }\n\n";
        }
        return "    public " + rc.simpleName() + " execute() {\n"
             + "        return " + call + ";\n"
             + "    }\n\n"
             + "    public CompletableFuture<" + rc.simpleName() + "> executeAsync() {\n"
             + "        return " + asyncCall + ";\n"
             + "    }\n\n";
    }

    private static String objectCollectionMethods(ResultClass rc) {
        return "    public List<" + rc.simpleName() + "> execute() {\n"
             + "        CollectionPage<" + rc.simpleName() + "> page = EntityOperations.executeAndGetCollection(\n"
             + "                context, contextPath, " + rc.classRef() + ");\n"
             + "        return page.currentPage();\n"
             + "    }\n\n";
    }

    // ------------------------------------------------------------------
    // Type mapping (lessons 93 / 136: unwrap Collection() first, then typedefs)
    // ------------------------------------------------------------------

    private Kind resultKind(ResolvedOp op) {
        ReturnTypeModel rt = op.returnType();
        if (rt == null || rt.type() == null || rt.type().isBlank()) {
            return Kind.VOID;
        }
        if (Names.isCollectionType(rt.type())) {
            String element = resolveTypeDefinition(Names.unwrapCollectionType(rt.type()), op.owner());
            return Names.isPrimitiveType(element)
                    ? Kind.PRIMITIVE_COLLECTION : Kind.OBJECT_COLLECTION;
        }
        String resolved = resolveTypeDefinition(rt.type(), op.owner());
        return Names.isPrimitiveType(resolved) ? Kind.PRIMITIVE_SINGLE : Kind.OBJECT_SINGLE;
    }

    private static boolean resultNullable(ResolvedOp op) {
        return op.returnType() != null && op.returnType().nullable();
    }

    /** Raw (unresolved, possibly Collection-wrapped) return type string. */
    private static String returnTypeOf(ResolvedOp op) {
        return op.returnType().type();
    }

    private static String collectionElementType(ResolvedOp op) {
        return Names.unwrapCollectionType(op.returnType().type());
    }

    private String primitiveResultJavaType(ResolvedOp op) {
        return resolveSingleJavaType(
                resolveTypeDefinition(returnTypeOf(op), op.owner()), op.owner(), true);
    }

    private String primitiveElementJavaType(ResolvedOp op) {
        return resolveSingleJavaType(
                resolveTypeDefinition(collectionElementType(op), op.owner()), op.owner(), true);
    }

    /** Java constructor-parameter type: primitives stay unboxed unless the param is nullable. */
    private String parameterJavaType(ParameterModel p, ResolvedOp op) {
        String resolved = resolveTypeDefinition(p.type(), op.owner());
        return resolveSingleJavaType(resolved, op.owner(), p.nullable());
    }

    /**
     * Resolves a structured result type to its generated Java class: the class literal
     * reference, simple name for signatures, and one import line added to {@code imports}.
     */
    private ResultClass objectResult(String unresolvedEdmType, ResolvedOp op, Set<String> imports) {
        String resolved = resolveTypeDefinition(unresolvedEdmType, op.owner());
        Names.TypeKind tk = Names.resolveTypeKind(resolved, effectiveSchemas);
        String className = switch (tk) {
            case ENTITY -> Names.entityClassName(Names.simpleNameFromFullName(resolved));
            case COMPLEX -> Names.complexTypeClassName(Names.simpleNameFromFullName(resolved));
            case ENUM -> Names.enumClassName(Names.simpleNameFromFullName(resolved));
            case UNKNOWN -> throw new IllegalStateException(
                    "Cannot resolve operation return type '" + unresolvedEdmType
                            + "' to a generated class");
        };
        String suffix = switch (tk) {
            case ENTITY -> Names.packageNameSuffixEntity();
            case COMPLEX -> Names.packageNameSuffixComplexType();
            case ENUM -> Names.packageNameSuffixEnum();
            case UNKNOWN -> throw new IllegalStateException("unreachable");
        };
        String pkg = basePackageForType(resolved, op.owner()) + suffix;
        String importLine = pkg + "." + className;
        imports.add(importLine);
        return new ResultClass(className, className + ".class", importLine);
    }

    // ------------------------------------------------------------------
    // Embeddable container-accessor pieces
    // (used by ContainerGenerator; each helper resolves the operation itself)
    // ------------------------------------------------------------------

    private static String classImportLine(String basePkg, String cls) {
        return basePkg + Names.packageNameSuffixOperation() + "." + cls;
    }

    /** Import line for the generated request class of this function import. */
    public String functionImportClassImportLine(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<FunctionModel> owned = resolveFunction(fi.function(), fi.name());
        return classImportLine(basePackageOf(owned.owner()), Names.functionRequestClassName(fi.name()));
    }

    /** Import line for the generated request class of this action import. */
    public String actionImportClassImportLine(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveAction(ai.action(), ai.name());
        return classImportLine(basePackageOf(owned.owner()), Names.actionRequestClassName(ai.name()));
    }

    /** Output package of the generated file for this function import (no suffix). */
    public String functionRequestFilePackage(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        return basePackageOf(resolveFunction(fi.function(), fi.name()).owner());
    }

    public String actionRequestFilePackage(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        return basePackageOf(resolveAction(ai.action(), ai.name()).owner());
    }

    /** Full accessor-method source for the container: typed signature + delegation body. */
    public String functionImportAccessorMethod(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<FunctionModel> owned = resolveFunction(fi.function(), fi.name());
        validateFunctionParameters(owned.model().parameters(), fi.name(), owned.owner());
        return accessorMethodSource(Names.functionRequestClassName(fi.name()),
                Names.toJavaFieldName(fi.name()), owned.model().parameters(), owned.owner());
    }

    public String actionImportAccessorMethod(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveAction(ai.action(), ai.name());
        return accessorMethodSource(Names.actionRequestClassName(ai.name()),
                Names.toJavaFieldName(ai.name()), owned.model().parameters(), owned.owner());
    }

    private <T> String accessorMethodSource(String className, String methodName,
                                            List<ParameterModel> parameters, SchemaModel owner) {
        StringBuilder sig = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (ParameterModel p : parameters) {
            if (sig.length() > 0) {
                sig.append(", ");
            }
            String field = Names.toJavaFieldName(p.name());
            sig.append(resolveSingleJavaType(resolveTypeDefinition(p.type(), owner),
                    owner, p.nullable())).append(' ').append(field);
            if (args.length() > 0) {
                args.append(", ");
            }
            args.append(field);
        }
        return "    public " + className + " " + methodName + "(" + sig + ") {\n"
             + "        return new " + className + "(context" + (args.isEmpty() ? "" : ", " + args) + ");\n"
             + "    }\n\n";
    }

    private String basePackageOf(SchemaModel owner) {
        String ns = owner.namespace();
        return schemaPackages.getOrDefault(ns,
                defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(ns));
    }
}
