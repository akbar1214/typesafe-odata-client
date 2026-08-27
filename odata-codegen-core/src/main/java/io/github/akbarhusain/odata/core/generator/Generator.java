package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Generator {

    private static final Logger log = LoggerFactory.getLogger(Generator.class);

    private final Path outputDir;
    private final Map<String, String> schemaPackages = new HashMap<>();
    private final String defaultBasePackage;
    private boolean generateWithMethods;
    private final Set<Path> createdDirectories = new HashSet<>();

    public Generator(Path outputDir, Map<String, String> schemaPackages) {
        this(outputDir, schemaPackages, null);
    }

    public Generator(Path outputDir, Map<String, String> schemaPackages, String defaultBasePackage) {
        this.outputDir = outputDir;
        this.schemaPackages.putAll(schemaPackages);
        this.defaultBasePackage = defaultBasePackage;
    }

    public Generator withGenerateWithMethods(boolean generateWithMethods) {
        this.generateWithMethods = generateWithMethods;
        return this;
    }

    /** Files written by the most recent {@link #generate(CsdlModel)} call (for stale-file cleanup). */
    public java.util.List<Path> writtenFiles() {
        return List.copyOf(written.keySet());
    }

    public void generate(CsdlModel model) throws IOException {
        Names.clearTypeKindCache();
        java.util.Set<Path> previousFiles = new java.util.HashSet<>(written.keySet());
        written.clear();
        if (defaultBasePackage != null) {
            validatePackage(defaultBasePackage);
        }
        for (Map.Entry<String, String> e : schemaPackages.entrySet()) {
            validatePackage(e.getValue());
        }
        // Schemas sharing an output package must share one aggregate ServiceSchemaInfo,
        // so collect them per package while generating
        Map<String, List<SchemaModel>> schemasByPackage = new LinkedHashMap<>();
        for (SchemaModel schema : model.schemas()) {
            String basePackage = schemaPackages.getOrDefault(schema.namespace(),
                    defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(schema.namespace()));
            validatePackage(basePackage);
            generateSchema(schema, basePackage, model.schemas());
            schemasByPackage.computeIfAbsent(basePackage, k -> new ArrayList<>()).add(schema);
        }
        for (Map.Entry<String, List<SchemaModel>> entry : schemasByPackage.entrySet()) {
            SchemaInfoGenerator schemaInfoGenerator = new SchemaInfoGenerator(entry.getKey());
            writeCode(entry.getKey() + Names.packageNameSuffixSchema(), Names.schemaInfoClassName(),
                    schemaInfoGenerator.generate(entry.getValue()));
        }
        // M13: clean up stale files that were generated in a previous call but not in the current one
        // (e.g., entity renamed from Foo to Bar). Without this, old .java files remain on classpath.
        for (Path old : previousFiles) {
            if (!written.containsKey(old)) {
                try {
                    Files.deleteIfExists(old);
                    log.debug("Deleted stale file: {}", old);
                } catch (IOException e) {
                    // A stale file left on disk silently pollutes the classpath — surface it
                    log.warn("Could not delete stale generated file: {}", old, e);
                }
            }
        }
    }

    private void generateSchema(SchemaModel schema, String basePackage, List<SchemaModel> allSchemas) throws IOException {
        log.info("Generating schema: {} -> {}", schema.namespace(), basePackage);

        EntityGenerator entityGenerator = new EntityGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
        EnumGenerator enumGenerator = new EnumGenerator(basePackage);
        ComplexTypeGenerator complexTypeGenerator = new ComplexTypeGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
        RequestGenerator requestGenerator = new RequestGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        ContainerGenerator containerGenerator = new ContainerGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        OperationGenerator operationGenerator = new OperationGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);

        for (EnumTypeModel enumType : schema.enumTypes()) {
            String code = enumGenerator.generate(enumType);
            writeCode(basePackage + Names.packageNameSuffixEnum(), Names.enumClassName(enumType.name()), code);
        }

        for (ComplexTypeModel complexType : schema.complexTypes()) {
            String code = complexTypeGenerator.generate(complexType, schema);
            writeCode(basePackage + Names.packageNameSuffixComplexType(), Names.complexTypeClassName(complexType.name()), code);
        }

        List<String> entityNames = new ArrayList<>();
        for (EntityTypeModel entityType : schema.entityTypes()) {
            String entityCode = entityGenerator.generate(entityType, schema);
            writeCode(basePackage + Names.packageNameSuffixEntity(), Names.entityClassName(entityType.name()), entityCode);

            String entityRequestCode = requestGenerator.generateEntityRequest(entityType, schema);
            writeCode(basePackage + Names.packageNameSuffixEntityRequest(), Names.entityRequestClassName(entityType.name()), entityRequestCode);

            String collectionRequestCode = requestGenerator.generateCollectionRequest(entityType, schema);
            writeCode(basePackage + Names.packageNameSuffixCollectionRequest(), Names.collectionRequestClassName(entityType.name()), collectionRequestCode);

            entityNames.add(entityType.name());
        }

        for (ContainerModel container : schema.containers()) {
            String code = containerGenerator.generate(container, schema);
            writeCode(basePackage + Names.packageNameSuffixContainer(), Names.containerClassName(container.name()), code);

            // Operation import request classes: one file per import — per OVERLOAD for
            // overloaded functions (OData identifies an unbound overload by its parameter
            // names) — packaged by the operation's OWNING schema (cross-schema imports
            // resolve like type references)
            for (var fi : container.functionImports()) {
                String pkg = operationGenerator.functionRequestFilePackage(fi, schema)
                        + Names.packageNameSuffixOperation();
                for (var req : operationGenerator.generateFunctionImportRequests(fi, schema)) {
                    writeCode(pkg, req.className(), req.code());
                }
            }
            for (var ai : container.actionImports()) {
                String pkg = operationGenerator.actionRequestFilePackage(ai, schema)
                        + Names.packageNameSuffixOperation();
                writeCode(pkg, Names.actionRequestClassName(ai.name()),
                        operationGenerator.generateActionImportRequest(ai, schema));
            }
        }
    }

    private final Map<Path, String> written = new HashMap<>();

    static void validatePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (packageName.contains("/") || packageName.contains("\\") || packageName.contains(":")) {
            throw new IllegalArgumentException("Invalid package name '" + packageName + "': must not contain '/', '\\', ':'");
        }
        if (packageName.startsWith(".") || packageName.endsWith(".")) {
            throw new IllegalArgumentException("Invalid package name '" + packageName + "': must not start or end with '.'");
        }
        for (String part : packageName.split("\\.", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Invalid package name '" + packageName + "': empty segment");
            }
            if (!Character.isJavaIdentifierStart(part.charAt(0))) {
                throw new IllegalArgumentException("Invalid package name '" + packageName + "': segment '" + part + "' is not a valid Java identifier");
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    throw new IllegalArgumentException("Invalid package name '" + packageName + "': segment '" + part + "' contains illegal character '" + part.charAt(i) + "'");
                }
            }
        }
    }

    private void writeCode(String packageName, String className, String code) throws IOException {
        validatePackage(packageName);
        String packageDir = packageName.replace('.', '/');
        Path dir = outputDir.resolve(packageDir).normalize();
        Path out = outputDir.toAbsolutePath().normalize();
        Path target = dir.toAbsolutePath().normalize();
        if (!target.startsWith(out)) {
            throw new IllegalArgumentException("Package '" + packageName + "' escapes output directory");
        }
        if (createdDirectories.add(dir)) {
            Files.createDirectories(dir);
        }
        Path file = dir.resolve(className + ".java");
        // Two types mapping to the same output file (e.g. same-named types from schemas
        // collapsed onto one package) previously overwrote each other silently — fail loudly
        String previous = written.putIfAbsent(file, code);
        if (previous != null && !previous.equals(code)) {
            throw new IllegalStateException("Duplicate generated class " + file + ": two types map to the "
                    + "same output file with different content. Remap one of them via schemaPackages.");
        }
        Files.writeString(file, code);
        log.debug("Wrote: {}", file);
    }
}
