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

    public synchronized void generate(CsdlModel model) throws IOException {
        Names.clearTypeKindCache();
        Map<Path, String> previous = new LinkedHashMap<>(written);
        Map<Path, String> pending = new LinkedHashMap<>();
        Map<String, Path> pendingCasePaths = new HashMap<>();
        pendingWritten = pending;
        pendingCasePathsByLowerCase = pendingCasePaths;
        try {
            if (defaultBasePackage != null) {
                validatePackage(defaultBasePackage);
            }
            for (Map.Entry<String, String> e : schemaPackages.entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) {
                    throw new IllegalArgumentException("Schema package mapping for '"
                            + e.getKey() + "' must define a package");
                }
                validatePackage(e.getValue());
            }
            Map<String, List<SchemaModel>> schemasByPackage = new LinkedHashMap<>();
            Map<String, OperationGenerator> operationGenerators = new LinkedHashMap<>();
            for (SchemaModel schema : model.schemas()) {
                String basePackage = schemaPackages.getOrDefault(schema.namespace(),
                        defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(schema.namespace()));
                validatePackage(basePackage);
                OperationGenerator operationGenerator = operationGenerators.computeIfAbsent(basePackage,
                        value -> new OperationGenerator(value, schemaPackages, defaultBasePackage, model.schemas()));
                generateSchema(schema, basePackage, model.schemas(), operationGenerator);
                schemasByPackage.computeIfAbsent(basePackage, k -> new ArrayList<>()).add(schema);
            }
            for (Map.Entry<String, List<SchemaModel>> entry : schemasByPackage.entrySet()) {
                SchemaInfoGenerator schemaInfoGenerator = new SchemaInfoGenerator(entry.getKey());
                writeCode(entry.getKey() + Names.packageNameSuffixSchema(), Names.schemaInfoClassName(),
                        schemaInfoGenerator.generate(entry.getValue()));
            }
            commit(previous, pending);
        } finally {
            pendingWritten = null;
            pendingCasePathsByLowerCase = null;
        }
    }

    private void generateSchema(SchemaModel schema, String basePackage, List<SchemaModel> allSchemas,
                                OperationGenerator operationGenerator) throws IOException {
        log.info("Generating schema: {} -> {}", schema.namespace(), basePackage);

        EntityGenerator entityGenerator = new EntityGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
        entityGenerator.validateTypeDefinitions(schema);
        EnumGenerator enumGenerator = new EnumGenerator(basePackage);
        ComplexTypeGenerator complexTypeGenerator = new ComplexTypeGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas, generateWithMethods);
        RequestGenerator requestGenerator = new RequestGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas,
                operationGenerator);
        ContainerGenerator containerGenerator = new ContainerGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas,
                operationGenerator, requestGenerator);

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

            // Bound-operation request classes (decision 96): packaged by the operation's
            // OWNING schema, mirroring import resolution
            for (OperationGenerator.BoundOp b : operationGenerator.boundOperationsFor(entityType, schema)) {
                writeCode(operationGenerator.boundFilePackage(b), b.className(),
                        operationGenerator.generateBoundOperationRequest(b, entityType, schema));
            }

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

    private record PriorFile(boolean existed, boolean regular, byte[] content) {}

    private void commit(Map<Path, String> previous, Map<Path, String> pending) throws IOException {
        Map<Path, PriorFile> priorFiles = snapshot(previous, pending);
        Set<Path> directoriesCreated = new java.util.LinkedHashSet<>();
        try {
            for (Map.Entry<Path, String> entry : pending.entrySet()) {
                Path file = entry.getKey().toAbsolutePath().normalize();
                ensureParentDirectory(file, directoriesCreated);
                Files.writeString(file, entry.getValue());
            }
            for (Path old : previous.keySet()) {
                if (pending.containsKey(old)) {
                    continue;
                }
                Path oldFile = old.toAbsolutePath().normalize();
                String oldKey = oldFile.toString().toLowerCase(java.util.Locale.ROOT);
                Path replacement = pendingCasePathsByLowerCase.get(oldKey);
                if (!sameFile(oldFile, replacement)) {
                    Files.deleteIfExists(oldFile);
                    log.debug("Deleted stale file: {}", oldFile);
                }
            }
            createdDirectories.addAll(directoriesCreated);
            written.clear();
            written.putAll(pending);
        } catch (IOException | RuntimeException failure) {
            try {
                rollback(priorFiles, directoriesCreated);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            createdDirectories.removeAll(directoriesCreated);
            throw failure;
        }
    }

    private Map<Path, PriorFile> snapshot(Map<Path, String> previous, Map<Path, String> pending)
            throws IOException {
        Set<Path> paths = new java.util.LinkedHashSet<>();
        for (Path path : previous.keySet()) {
            paths.add(path.toAbsolutePath().normalize());
        }
        for (Path path : pending.keySet()) {
            paths.add(path.toAbsolutePath().normalize());
        }
        Map<Path, PriorFile> result = new LinkedHashMap<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                result.put(path, new PriorFile(false, false, null));
            } else if (Files.isRegularFile(path)) {
                result.put(path, new PriorFile(true, true, Files.readAllBytes(path)));
            } else {
                result.put(path, new PriorFile(true, false, null));
            }
        }
        return result;
    }

    private void ensureParentDirectory(Path file, Set<Path> directoriesCreated) throws IOException {
        Path directory = file.getParent();
        if (directory == null) {
            return;
        }
        if (createdDirectories.contains(directory)) {
            if (Files.isDirectory(directory)) {
                return;
            }
            createdDirectories.remove(directory);
        }
        List<Path> missing = new ArrayList<>();
        Path current = directory;
        while (current != null && !Files.exists(current)) {
            missing.add(current);
            current = current.getParent();
        }
        Files.createDirectories(directory);
        createdDirectories.addAll(missing);
        directoriesCreated.addAll(missing);
    }

    private boolean sameFile(Path oldFile, Path replacement) {
        if (replacement == null) {
            return false;
        }
        Path normalizedOld = oldFile.toAbsolutePath().normalize();
        Path normalizedReplacement = replacement.toAbsolutePath().normalize();
        if (normalizedOld.equals(normalizedReplacement)) {
            return true;
        }
        try {
            return Files.exists(normalizedOld) && Files.exists(normalizedReplacement)
                    && Files.isSameFile(normalizedOld, normalizedReplacement);
        } catch (IOException e) {
            log.debug("Could not compare stale file {}", normalizedOld, e);
            return false;
        }
    }

    private void rollback(Map<Path, PriorFile> priorFiles, Set<Path> directoriesCreated) throws IOException {
        IOException failure = null;
        for (Map.Entry<Path, PriorFile> entry : priorFiles.entrySet()) {
            Path file = entry.getKey();
            PriorFile prior = entry.getValue();
            try {
                if (!prior.existed()) {
                    if (Files.isRegularFile(file)) {
                        Files.deleteIfExists(file);
                    }
                } else if (prior.regular()) {
                    Files.createDirectories(file.getParent());
                    Files.write(file, prior.content());
                }
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        for (Path directory : directoriesCreated) {
            try {
                if (Files.isDirectory(directory)) {
                    try (var entries = Files.list(directory)) {
                        if (entries.findAny().isEmpty()) {
                            Files.deleteIfExists(directory);
                        }
                    }
                }
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final Map<Path, String> written = new LinkedHashMap<>();
    private Map<Path, String> pendingWritten;
    private Map<String, Path> pendingCasePathsByLowerCase;

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
            // HARD keywords only: contextual keywords (record, to, open, ...) are legal
            // package segments, and toPackageName lowercases namespaces — rejecting them
            // would abort generation of legal metadata
            if (Names.isHardJavaKeyword(part)) {
                throw new IllegalArgumentException("Invalid package name '" + packageName + "': segment '" + part + "' is a Java keyword");
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
        Path file = dir.resolve(className + ".java");
        Map<Path, String> targetWritten = pendingWritten == null ? written : pendingWritten;
        Map<String, Path> casePaths = pendingCasePathsByLowerCase == null
                ? new HashMap<>() : pendingCasePathsByLowerCase;
        String caseKey = target.toAbsolutePath().normalize().resolve(className + ".java")
                .toString().toLowerCase(java.util.Locale.ROOT);
        Path priorCasePath = casePaths.putIfAbsent(caseKey, file);
        if (priorCasePath != null && !priorCasePath.equals(file)) {
            throw new IllegalStateException("Case-only generated file collision: '" + priorCasePath
                    + "' and '" + file + "' map to the same path on a case-insensitive filesystem");
        }
        String previous = targetWritten.putIfAbsent(file, code);
        if (previous != null && !previous.equals(code)) {
            throw new IllegalStateException("Duplicate generated class " + file + ": two types map to the "
                    + "same output file with different content. Remap one of them via schemaPackages.");
        }
    }
}
