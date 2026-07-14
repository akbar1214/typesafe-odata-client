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
import java.util.List;
import java.util.Map;

public class Generator {

    private static final Logger log = LoggerFactory.getLogger(Generator.class);

    private final Path outputDir;
    private final Map<String, String> schemaPackages = new HashMap<>();
    private final String defaultBasePackage;

    public Generator(Path outputDir, Map<String, String> schemaPackages) {
        this(outputDir, schemaPackages, null);
    }

    public Generator(Path outputDir, Map<String, String> schemaPackages, String defaultBasePackage) {
        this.outputDir = outputDir;
        this.schemaPackages.putAll(schemaPackages);
        this.defaultBasePackage = defaultBasePackage;
    }

    public void generate(CsdlModel model) throws IOException {
        Names.clearTypeKindCache();
        for (SchemaModel schema : model.schemas()) {
            String basePackage = schemaPackages.getOrDefault(schema.namespace(),
                    defaultBasePackage != null ? defaultBasePackage : Names.toPackageName(schema.namespace()));
            generateSchema(schema, basePackage, model.schemas());
        }
    }

    private void generateSchema(SchemaModel schema, String basePackage, List<SchemaModel> allSchemas) throws IOException {
        log.info("Generating schema: {} -> {}", schema.namespace(), basePackage);

        EntityGenerator entityGenerator = new EntityGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        EnumGenerator enumGenerator = new EnumGenerator(basePackage);
        ComplexTypeGenerator complexTypeGenerator = new ComplexTypeGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        RequestGenerator requestGenerator = new RequestGenerator(basePackage, schemaPackages, defaultBasePackage, allSchemas);
        ContainerGenerator containerGenerator = new ContainerGenerator(basePackage, schemaPackages, defaultBasePackage);
        SchemaInfoGenerator schemaInfoGenerator = new SchemaInfoGenerator(basePackage);

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
        }

        String schemaInfoCode = schemaInfoGenerator.generate(schema);
        writeCode(basePackage + Names.packageNameSuffixSchema(), Names.schemaInfoClassName(), schemaInfoCode);
    }

    private void writeCode(String packageName, String className, String code) throws IOException {
        String packageDir = packageName.replace('.', '/');
        Path dir = outputDir.resolve(packageDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(className + ".java");
        Files.writeString(file, code);
        log.debug("Wrote: {}", file);
    }
}
