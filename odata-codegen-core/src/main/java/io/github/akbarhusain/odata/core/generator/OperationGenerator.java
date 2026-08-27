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
 *
 * <p>OData identifies an unbound function overload by its PARAMETER NAMES, so a
 * single FunctionImport exposing several same-name unbound overloads generates
 * one request class ({@code <Name>By<Params>FunctionRequest}) and one container
 * accessor per overload — the invocation URL's parameter names select the overload
 * ({@code IsSiteAdmin(username=...)} vs {@code IsSiteAdmin(userId=...)}).
 */
public class OperationGenerator extends AbstractTypeGenerator {

    private enum Kind { VOID, PRIMITIVE_SINGLE, PRIMITIVE_COLLECTION, OBJECT_SINGLE, OBJECT_COLLECTION }

    /** A model plus the schema that owns it (needed for type/package resolution). */
    private record Owned<T>(T model, SchemaModel owner) {}

    private record ResolvedOp(String name, List<ParameterModel> parameters,
                              ReturnTypeModel returnType, boolean isFunction, SchemaModel owner) {}

    /** Java type + class-literal reference + required import for structured result types. */
    private record ResultClass(String simpleName, String classRef, String importLine) {}

    /** One generated operation-request source file: its class name (within {@code .operation}) and source. */
    public record GeneratedOperationRequest(String className, String code) {}

    public OperationGenerator(String basePackage, Map<String, String> schemaPackages,
                              String defaultBasePackage, List<SchemaModel> allSchemas) {
        super(basePackage, schemaPackages, defaultBasePackage, allSchemas);
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * One request class per unbound overload of the referenced function: a lone
     * overload keeps the historical unsuffixed {@code <Name>FunctionRequest} name;
     * overloads disambiguate by parameter names ({@code <Name>By<Params>FunctionRequest}).
     */
    public List<GeneratedOperationRequest> generateFunctionImportRequests(
            FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        List<Owned<FunctionModel>> overloads = resolveUnboundFunctionOverloads(fi.function(), fi.name());
        List<String> suffixes = allocateOverloadSuffixes(overloads);
        List<GeneratedOperationRequest> out = new ArrayList<>(overloads.size());
        for (int i = 0; i < overloads.size(); i++) {
            Owned<FunctionModel> owned = overloads.get(i);
            FunctionModel fn = owned.model();
            validateFunctionParameters(fn.parameters(), fi.name(), owned.owner());
            ResolvedOp op = new ResolvedOp(fn.name(), fn.parameters(), fn.returnType(), true, owned.owner());
            String className = Names.functionRequestClassName(fi.name(), suffixes.get(i));
            out.add(new GeneratedOperationRequest(className, render(fi.name(), className, op, suffixes.get(i))));
        }
        return out;
    }

    /** Convenience for non-overloaded imports — renders the first (only) overload. */
    public String generateFunctionImportRequest(FunctionImportModel fi, SchemaModel containerSchema) {
        return generateFunctionImportRequests(fi, containerSchema).get(0).code();
    }

    public String generateActionImportRequest(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveUnboundAction(ai.action(), ai.name());
        ActionModel ac = owned.model();
        ResolvedOp op = new ResolvedOp(ac.name(), ac.parameters(), ac.returnType(), false, owned.owner());
        return render(ai.name(), Names.actionRequestClassName(ai.name()), op, "");
    }

    // ------------------------------------------------------------------
    // Cross-schema operation resolution
    // ------------------------------------------------------------------

    /**
     * Import references are alias-resolved to real namespaces at parse time. Qualified
     * references win over simple-name references; a simple-name reference matching
     * operations in more than one namespace stays a loud ambiguity (one ambiguity
     * policy everywhere — lessons H11/M2/156).
     *
     * <p>Functions may be overloaded: same-name UNBOUND functions with distinct
     * parameter names are distinct overloads and all resolve (the import exposes
     * every overload; parameter names select one on the wire). Bound same-name
     * siblings never compete with an unbound import target. Overloads with identical
     * parameter names cannot be distinguished in a URL — invalid CSDL, fails loudly.
     */
    private List<Owned<FunctionModel>> resolveUnboundFunctionOverloads(String reference, String importLabel) {
        List<Owned<FunctionModel>> qualified = new ArrayList<>();
        List<Owned<FunctionModel>> simple = new ArrayList<>();
        for (SchemaModel s : effectiveSchemas) {
            for (FunctionModel f : s.functions()) {
                classify(new Owned<>(f, s), s.namespace() + "." + f.name(), f.name(),
                        reference, qualified, simple);
            }
        }
        List<Owned<FunctionModel>> unbound = resolveUnbound(qualified, simple, importLabel,
                reference, "FunctionImport", "function", FunctionModel::isBound);
        if (unbound.size() > 1) {
            Set<String> seen = new java.util.HashSet<>();
            for (Owned<FunctionModel> o : unbound) {
                String names = parameterNames(o.model());
                if (!seen.add(names)) {
                    throw new IllegalStateException("FunctionImport '" + importLabel + "': function '"
                            + reference + "' has multiple overloads with identical parameter names "
                            + names + " — OData requires unbound overloads to differ in parameter names");
                }
            }
        }
        return unbound;
    }

    /**
     * Actions cannot be overloaded by parameter names, so multiple same-name unbound
     * actions are invalid CSDL and fail loudly; bound same-name siblings are ignored
     * for an unbound import target.
     */
    private Owned<ActionModel> resolveUnboundAction(String reference, String importLabel) {
        List<Owned<ActionModel>> qualified = new ArrayList<>();
        List<Owned<ActionModel>> simple = new ArrayList<>();
        for (SchemaModel s : effectiveSchemas) {
            for (ActionModel a : s.actions()) {
                classify(new Owned<>(a, s), s.namespace() + "." + a.name(), a.name(),
                        reference, qualified, simple);
            }
        }
        List<Owned<ActionModel>> unbound = resolveUnbound(qualified, simple, importLabel,
                reference, "ActionImport", "action", ActionModel::isBound);
        if (unbound.size() > 1) {
            throw new IllegalStateException("ActionImport '" + importLabel + "': " + unbound.size()
                    + " unbound actions named '" + reference + "' — actions cannot be overloaded by "
                    + "parameter names, so same-name unbound actions are invalid CSDL");
        }
        return unbound.get(0);
    }

    /**
     * Qualified references win over simple-name references; a simple name matching
     * operations in more than one namespace stays a loud ambiguity. Bound same-name
     * siblings never compete with an unbound import target; a reference with no
     * unbound candidate fails with the bound message.
     */
    private <T> List<Owned<T>> resolveUnbound(List<Owned<T>> qualified, List<Owned<T>> simple,
                                              String importLabel, String reference,
                                              String kindLabel, String opLabel,
                                              java.util.function.Predicate<T> isBound) {
        List<Owned<T>> candidates = !qualified.isEmpty() ? qualified : simple;
        if (candidates.isEmpty()) {
            throw new IllegalStateException(kindLabel + " '" + importLabel
                    + "' references unknown operation '" + reference + "'");
        }
        if (qualified.isEmpty()) {
            long namespaces = candidates.stream()
                    .map(Owned::owner).map(SchemaModel::namespace).distinct().count();
            if (namespaces > 1) {
                throw ambiguous(importLabel, reference);
            }
        }
        List<Owned<T>> unbound = new ArrayList<>();
        for (Owned<T> c : candidates) {
            if (!isBound.test(c.model())) {
                unbound.add(c);
            }
        }
        if (unbound.isEmpty()) {
            throw new IllegalStateException(kindLabel + " '" + importLabel
                    + "' references bound " + opLabel + " '" + reference
                    + "' — " + opLabel + " imports must reference UNBOUND operations");
        }
        return unbound;
    }

    /** Order-insensitive parameter-name key: OData URL parameters are named, so the SET of names must identify the overload. */
    private static String parameterNames(FunctionModel f) {
        return f.parameters().stream().map(ParameterModel::name).sorted()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
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

    private IllegalStateException ambiguous(String importLabel, String reference) {
        return new IllegalStateException("Ambiguous operation reference '" + reference
                + "' on import '" + importLabel + "' — use the namespace-qualified form");
    }

    private void validateFunctionParameters(List<ParameterModel> parameters, String importName,
                                            SchemaModel owner) {
        for (ParameterModel p : parameters) {
            if (Names.isCollectionType(p.type())) {
                // Reject the COLLECTION before any element check: validating only the
                // unwrapped element let Collection(Edm.String) through while generation
                // emitted a garbage parameter type — fail loudly instead (lesson 170)
                throw new IllegalStateException("FunctionImport '" + importName
                        + "': parameter '" + p.name() + "' has collection type '" + p.type()
                        + "' — collection function parameters cannot be embedded in an "
                        + "invocation URL by this generator");
            }
            String resolved = resolveTypeDefinition(p.type(), owner);
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
    // Overload disambiguation by parameter names
    // ------------------------------------------------------------------

    /**
     * One suffix per overload: empty for a lone overload (historical unsuffixed class
     * and accessor names), else {@code By<Param>And<Param>}. Hostile parameter names
     * that fold onto the same suffix ({@code user-name} vs {@code user_name}) get
     * deterministic {@code _2}/{@code _3} suffixes (decision 54 policy).
     */
    private static List<String> allocateOverloadSuffixes(List<Owned<FunctionModel>> overloads) {
        if (overloads.size() == 1) {
            return List.of("");
        }
        List<String> out = new ArrayList<>(overloads.size());
        Set<String> used = new java.util.HashSet<>();
        for (Owned<FunctionModel> o : overloads) {
            String suffix = overloadSuffix(o.model().parameters());
            String unique = suffix;
            int n = 2;
            while (!used.add(unique)) {
                unique = suffix + "_" + n++;
            }
            out.add(unique);
        }
        return out;
    }

    private static String overloadSuffix(List<ParameterModel> parameters) {
        StringBuilder sb = new StringBuilder("By");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append("And");
            }
            sb.append(Names.capitalize(Names.toJavaFieldName(parameters.get(i).name())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private String render(String importName, String className, ResolvedOp op, String overloadSuffix) {
        Kind kind = resultKind(op);
        boolean isAction = !op.isFunction();

        Set<String> imports = new TreeSet<>();
        imports.add("io.github.akbarhusain.odata.runtime.client.EntityOperations");
        imports.add("io.github.akbarhusain.odata.runtime.entity.Context");
        imports.add("io.github.akbarhusain.odata.runtime.entity.ContextPath");
        imports.add("io.github.akbarhusain.odata.runtime.entity.OperationPath");
        imports.add("io.github.akbarhusain.odata.runtime.http.HttpMethod");
        // Structured/enum parameter types live in other packages (.enums/.entity/.complex)
        // — without these imports the generated class does not compile
        collectParameterImports(op.parameters(), op.owner(), imports);

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
                // Complex/enum results arrive value-wrapped on the wire (unlike entities,
                // which arrive at the JSON root) — route them to the wrapped variant
                boolean wrapped = structuredResultKind(op) != Names.TypeKind.ENTITY;
                methods.append(objectSingleMethods(rc, nullableResult, verb, bodyArg, wrapped));
            }
            case OBJECT_COLLECTION -> {
                ResultClass rc = objectResult(collectionElementType(op), op, imports);
                methods.append(objectCollectionMethods(rc));
                imports.add("java.util.List");
                imports.add("io.github.akbarhusain.odata.runtime.paging.CollectionPage");
            }
        }
        boolean hasReturn = kind != Kind.VOID;
        if (hasReturn) {
            imports.add("java.util.concurrent.CompletableFuture");
        }
        if (nullableResult && hasReturn) {
            imports.add("java.util.Optional");
        }
        if (kind == Kind.OBJECT_SINGLE || kind == Kind.OBJECT_COLLECTION) {
            // polymorphic @odata.type reads resolve subtypes through the registry
            // (decision 46 parity for operation results)
            imports.add(basePackageOf(op.owner()) + Names.packageNameSuffixSchema()
                    + "." + Names.schemaInfoClassName());
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("package ").append(outputPackage(op)).append(";\n\n");
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n/** Invokes ")
          .append(isAction ? "action" : "function")
          .append(" import \"").append(importName).append("\".");
        if (!overloadSuffix.isEmpty()) {
            sb.append(" Overload of the same-name function, selected by its parameter names (")
              .append(op.parameters().stream().map(ParameterModel::name)
                      .collect(java.util.stream.Collectors.joining(", ")))
              .append(").");
        }
        sb.append("\n */\n");
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

    /**
     * Adds imports for structured parameter types (enums/entities/complexes — always in
     * a different package than .operation) and {@code java.util.List} when any parameter
     * is collection-typed. Edm primitives need nothing: temporal/decimal types map to
     * fully-qualified {@code java.*} names.
     */
    private void collectParameterImports(List<ParameterModel> parameters, SchemaModel owner,
                                         Set<String> imports) {
        boolean anyCollection = false;
        for (ParameterModel p : parameters) {
            if (Names.isCollectionType(p.type())) {
                anyCollection = true;
            }
            String element = Names.isCollectionType(p.type())
                    ? Names.unwrapCollectionType(p.type()) : p.type();
            String resolved = resolveTypeDefinition(element, owner);
            if (!Names.isPrimitiveType(resolved)) {
                imports.add(basePackageForType(resolved, owner) + Names.resolvedSuffix(resolved, effectiveSchemas)
                        + "." + Names.resolvedClassName(resolved, effectiveSchemas));
            }
        }
        if (anyCollection) {
            imports.add("java.util.List");
        }
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
            sb.append(", ").append(parameterJavaType(p, op.owner()))
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
                    appendPairAdd(b, p.name(), field, literalEdmType, "            ");
                    b.append("        }\n");
                } else {
                    appendRequiredGuard(b, p, field, op);
                    appendPairAdd(b, p.name(), field, literalEdmType, "        ");
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

    /** The pair's wire name is the CSDL parameter name; the Java identifier stays local. */
    private static void appendPairAdd(StringBuilder b, String csdlName, String field,
                                      String literalEdmType, String indent) {
        b.append(indent).append("__pairs.add(\"").append(csdlName)
          .append("=\" + OperationPath.parameter(").append(field)
          .append(", \"").append(literalEdmType).append("\"));\n");
    }

    private void appendRequiredGuard(StringBuilder b, ParameterModel p, String field, ResolvedOp op) {
        String javaType = parameterJavaType(p, op.owner());
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

    private String objectSingleMethods(ResultClass rc, boolean nullable, String verb,
                                       String bodyArg, boolean wrapped) {
        String variant = wrapped ? "invokeComplexSync" : "invokeSync";
        String asyncVariant = wrapped ? "invokeComplexAsync" : "invokeAsync";
        String call = "EntityOperations." + variant + "(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + rc.classRef() + ", ServiceSchemaInfo.INSTANCE)";
        String asyncCall = "EntityOperations." + asyncVariant + "(context, contextPath, HttpMethod."
                + verb + ", " + bodyArg + ", " + rc.classRef() + ", ServiceSchemaInfo.INSTANCE)";
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
             + "                context, contextPath, " + rc.classRef() + ", ServiceSchemaInfo.INSTANCE);\n"
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

    /** Structured (non-primitive) result kind: ENTITY reads the JSON root, COMPLEX/ENUM are value-wrapped. */
    private Names.TypeKind structuredResultKind(ResolvedOp op) {
        String element = Names.isCollectionType(op.returnType().type())
                ? Names.unwrapCollectionType(op.returnType().type()) : op.returnType().type();
        return Names.resolveTypeKind(resolveTypeDefinition(element, op.owner()), effectiveSchemas);
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

    /** Java constructor-parameter type: primitives stay unboxed unless nullable; collections map to List<boxed element>. */
    private String parameterJavaType(ParameterModel p, SchemaModel owner) {
        if (Names.isCollectionType(p.type())) {
            return "List<" + resolveSingleJavaType(
                    resolveTypeDefinition(Names.unwrapCollectionType(p.type()), owner),
                    owner, true) + ">";
        }
        return resolveSingleJavaType(resolveTypeDefinition(p.type(), owner), owner, p.nullable());
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

    /** Import lines for the generated request classes of this import's overloads. */
    public List<String> functionImportClassImportLines(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        List<Owned<FunctionModel>> overloads = resolveUnboundFunctionOverloads(fi.function(), fi.name());
        List<String> suffixes = allocateOverloadSuffixes(overloads);
        List<String> lines = new ArrayList<>(overloads.size());
        for (int i = 0; i < overloads.size(); i++) {
            lines.add(classImportLine(basePackageOf(overloads.get(i).owner()),
                    Names.functionRequestClassName(fi.name(), suffixes.get(i))));
        }
        return lines;
    }

    /** Import line for the generated request class of this action import. */
    public String actionImportClassImportLine(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveUnboundAction(ai.action(), ai.name());
        return classImportLine(basePackageOf(owned.owner()), Names.actionRequestClassName(ai.name()));
    }

    /** Output package of the generated files for this function import (no suffix). */
    public String functionRequestFilePackage(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        return basePackageOf(resolveUnboundFunctionOverloads(fi.function(), fi.name()).get(0).owner());
    }

    public String actionRequestFilePackage(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        return basePackageOf(resolveUnboundAction(ai.action(), ai.name()).owner());
    }

    /** Container accessor method NAMES (one per overload) — feeds the collision registry. */
    public List<String> functionImportAccessorNames(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        List<Owned<FunctionModel>> overloads = resolveUnboundFunctionOverloads(fi.function(), fi.name());
        List<String> suffixes = allocateOverloadSuffixes(overloads);
        List<String> names = new ArrayList<>(overloads.size());
        for (String suffix : suffixes) {
            names.add(Names.toJavaFieldName(fi.name()) + suffix);
        }
        return names;
    }

    /** Full accessor-method sources for the container: one per overload. */
    public List<String> functionImportAccessorMethods(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        List<Owned<FunctionModel>> overloads = resolveUnboundFunctionOverloads(fi.function(), fi.name());
        List<String> suffixes = allocateOverloadSuffixes(overloads);
        List<String> methods = new ArrayList<>(overloads.size());
        for (int i = 0; i < overloads.size(); i++) {
            Owned<FunctionModel> owned = overloads.get(i);
            validateFunctionParameters(owned.model().parameters(), fi.name(), owned.owner());
            methods.add(accessorMethodSource(
                    Names.functionRequestClassName(fi.name(), suffixes.get(i)),
                    Names.toJavaFieldName(fi.name()) + suffixes.get(i),
                    owned.model().parameters(), owned.owner()));
        }
        return methods;
    }

    public String actionImportAccessorMethod(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveUnboundAction(ai.action(), ai.name());
        return accessorMethodSource(Names.actionRequestClassName(ai.name()),
                Names.toJavaFieldName(ai.name()), owned.model().parameters(), owned.owner());
    }

    /** Imports the CONTAINER needs for this import's parameter types (H1: accessors live in .container). */
    public java.util.Set<String> functionImportParameterImports(FunctionImportModel fi, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        java.util.Set<String> imports = new java.util.TreeSet<>();
        for (Owned<FunctionModel> owned : resolveUnboundFunctionOverloads(fi.function(), fi.name())) {
            collectParameterImports(owned.model().parameters(), owned.owner(), imports);
        }
        return imports;
    }

    public java.util.Set<String> actionImportParameterImports(ActionImportModel ai, SchemaModel containerSchema) {
        initEffectiveSchemas(containerSchema);
        Owned<ActionModel> owned = resolveUnboundAction(ai.action(), ai.name());
        java.util.Set<String> imports = new java.util.TreeSet<>();
        collectParameterImports(owned.model().parameters(), owned.owner(), imports);
        return imports;
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
            sig.append(parameterJavaType(p, owner)).append(' ').append(field);
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
