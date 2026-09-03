package io.github.akbarhusain.odata.core.bench;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Allocation/CPU profiling driver for the code-generation path (parse CSDL +
 * generate a client). Complements the WireMock-based {@code PerfBenchmark},
 * which only exercises request execution.
 *
 * <p>Default model: 5 schemas x 3000 entities (15,000 total) with realistic
 * relations — scalar/complex/enum/collection properties, single + collection
 * navigations (same- and cross-schema), depth-2/3 inheritance chains, a few
 * open types, per-schema containers with entity sets, and bound + unbound
 * operations. Usage: {@code CodegenBenchmark [iterations]}.
 */
public class CodegenBenchmark {

    static final int SCHEMAS = 5;
    static final int ENTITIES_PER_SCHEMA = 3000;

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 6;

        long t0 = System.currentTimeMillis();
        String xml = buildMetadata(SCHEMAS, ENTITIES_PER_SCHEMA);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        System.out.println("MODEL_XML_BYTES=" + xmlBytes.length
                + " buildMs=" + (System.currentTimeMillis() - t0));
        System.out.flush();

        long t1 = System.currentTimeMillis();
        CsdlModel model;
        try (ByteArrayInputStream in = new ByteArrayInputStream(xmlBytes)) {
            model = new StaxCsdlParser().parse(in);
        }
        int entityTotal = model.schemas().stream().mapToInt(s -> s.entityTypes().size()).sum();
        System.out.println("PARSE_MS=" + (System.currentTimeMillis() - t1)
                + " entities=" + entityTotal);
        System.out.flush();

        Map<String, String> schemaPackages = new LinkedHashMap<>();
        for (int j = 0; j < SCHEMAS; j++) {
            schemaPackages.put("Shop.S" + j, "com.shop.s" + j);
        }
        // Help inner-class-heavy outputs stay realistic
        String defaultBasePackage = "com.shop";

        Path root = Files.createTempDirectory("codegen-bench");

        // Warmup: one full generation (JIT + caches), not measured
        new Generator(freshDir(root, "warmup"), schemaPackages, defaultBasePackage)
                .withGenerateWithMethods(true)
                .generate(model);
        System.out.println("WARMUP_COMPLETE");
        System.out.flush();

        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            Path out = freshDir(root, "iter" + i);
            long start = System.nanoTime();
            new Generator(out, schemaPackages, defaultBasePackage)
                    .withGenerateWithMethods(true)
                    .generate(model);
            long ms = (System.nanoTime() - start) / 1_000_000;
            long files;
            try (Stream<Path> walk = Files.walk(out)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).count();
            }
            checksum += files;
            System.out.println("RESULT:iteration=" + i + " genMs=" + ms + " files=" + files);
            System.out.flush();
        }
        System.out.println("RESULT:done iterations=" + iterations + " checksum=" + checksum);
        System.out.flush();
    }

    private static Path freshDir(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
        return Files.createDirectories(dir);
    }

    /**
     * Builds one CSDL document: {@code schemaCount} schemas with
     * {@code entitiesPerSchema} entity types each, cross-linked by navigations
     * and short inheritance chains, plus shared enums/complex types, per-schema
     * containers, and a bound + unbound operation surface.
     */
    static String buildMetadata(int schemaCount, int entitiesPerSchema) {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">");
        sb.append("<edmx:DataServices>");
        for (int j = 0; j < schemaCount; j++) {
            appendSchema(sb, j, schemaCount, entitiesPerSchema);
        }
        sb.append("</edmx:DataServices>");
        sb.append("</edmx:Edmx>");
        return sb.toString();
    }

    private static void appendSchema(StringBuilder sb, int j, int schemaCount, int n) {
        String ns = "Shop.S" + j;
        String nextNs = "Shop.S" + ((j + 1) % schemaCount);
        sb.append("<Schema Namespace=\"").append(ns)
          .append("\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">");

        sb.append("<EnumType Name=\"Category\">");
        sb.append("<Member Name=\"Standard\" Value=\"0\"/>");
        sb.append("<Member Name=\"Premium\" Value=\"1\"/>");
        sb.append("<Member Name=\"Clearance\" Value=\"2\"/>");
        sb.append("</EnumType>");

        sb.append("<ComplexType Name=\"Address\">");
        sb.append("<Property Name=\"Street\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("<Property Name=\"City\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("<Property Name=\"Zip\" Type=\"Edm.String\"/>");
        sb.append("</ComplexType>");

        sb.append("<ComplexType Name=\"Contact\">");
        sb.append("<Property Name=\"Email\" Type=\"Edm.String\"/>");
        sb.append("<Property Name=\"Phone\" Type=\"Edm.String\"/>");
        sb.append("</ComplexType>");

        for (int i = 0; i < n; i++) {
            String entity = "E" + j + "_" + i;
            // Chains within a schema (every 10th extends 10 below it) plus links
            // across schemas for a subset (every 100th extends the same-index
            // entity of the previous schema, qualified and unambiguous). Only
            // j > 0 links across schemas — otherwise S0 -> S4 -> ... -> S0 would
            // form a BaseType cycle. Derived types carry no Key (inherited).
            String baseType = null;
            if (j > 0 && i % 100 == 0 && i > 0) {
                baseType = "Shop.S" + (j - 1) + ".E" + (j - 1) + "_" + i;
            } else if (i % 10 == 0 && i > 0) {
                baseType = ns + ".E" + j + "_" + (i - 10);
            }
            boolean openType = (i % 50 == 0);
            sb.append("<EntityType Name=\"").append(entity).append("\"");
            if (baseType != null) {
                sb.append(" BaseType=\"").append(baseType).append("\"");
            }
            if (openType) {
                sb.append(" OpenType=\"true\"");
            }
            sb.append(">");
            if (baseType == null) {
                sb.append("<Key><PropertyRef Name=\"Id\"/></Key>");
            }
            sb.append("<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
            if (baseType == null) {
                sb.append("<Property Name=\"Name\" Type=\"Edm.String\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Caption\" Type=\"Edm.String\"/>");
                sb.append("<Property Name=\"Price\" Type=\"Edm.Decimal\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Rating\" Type=\"Edm.Double\"/>");
                sb.append("<Property Name=\"Stock\" Type=\"Edm.Int64\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Active\" Type=\"Edm.Boolean\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Created\" Type=\"Edm.DateTimeOffset\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Birthday\" Type=\"Edm.Date\"/>");
                sb.append("<Property Name=\"Sku\" Type=\"Edm.Guid\"/>");
                sb.append("<Property Name=\"Payload\" Type=\"Edm.Binary\"/>");
                sb.append("<Property Name=\"Backorder\" Type=\"Edm.Duration\"/>");
                sb.append("<Property Name=\"ShipAt\" Type=\"Edm.TimeOfDay\"/>");
                sb.append("<Property Name=\"Home\" Type=\"").append(ns).append(".Address\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"Contacts\" Type=\"Collection(").append(ns).append(".Contact)\"/>");
                sb.append("<Property Name=\"Category\" Type=\"").append(ns).append(".Category\" Nullable=\"false\"/>");
                // Single nav (same schema) + collection nav (cross-schema) + single cross-schema nav.
                // Derived types inherit these untouched: per-index targets would
                // otherwise redeclare the inherited navs with incompatible types.
                sb.append("<NavigationProperty Name=\"Primary\" Type=\"").append(ns)
                  .append(".E").append(j).append("_").append((i + 1) % n).append("\"/>");
                sb.append("<NavigationProperty Name=\"Lines\" Type=\"Collection(").append(nextNs)
                  .append(".E").append((j + 1) % schemaCount).append("_").append(i % n).append(")\"/>");
                sb.append("<NavigationProperty Name=\"Supplier\" Type=\"").append(nextNs)
                  .append(".E").append((j + 1) % schemaCount).append("_").append((i + 7) % n).append("\"/>");
            } else {
                sb.append("<Property Name=\"Note\" Type=\"Edm.String\"/>");
                sb.append("<Property Name=\"Level\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
            }
            sb.append("</EntityType>");
        }

        // One bound function on the first entity + one unbound function/action pair
        sb.append("<Function Name=\"Discounted\" IsBound=\"true\">");
        sb.append("<Parameter Name=\"binding\" Type=\"").append(ns).append(".E").append(j).append("_0\"/>");
        sb.append("<Parameter Name=\"percent\" Type=\"Edm.Decimal\" Nullable=\"false\"/>");
        sb.append("<ReturnType Type=\"Edm.Decimal\"/>");
        sb.append("</Function>");
        sb.append("<Action Name=\"Restock\" IsBound=\"true\">");
        sb.append("<Parameter Name=\"binding\" Type=\"").append(ns).append(".E").append(j).append("_0\"/>");
        sb.append("<Parameter Name=\"amount\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
        sb.append("</Action>");

        sb.append("<EntityContainer Name=\"Store").append(j).append("\">");
        for (int i = 0; i < n; i++) {
            sb.append("<EntitySet Name=\"E").append(j).append("_").append(i)
              .append("Set\" EntityType=\"").append(ns).append(".E").append(j).append("_").append(i).append("\"/>");
        }
        sb.append("<FunctionImport Name=\"TopSelling\" Function=\"").append(ns).append(".TopSelling\" EntitySet=\"E")
          .append(j).append("_0Set\"/>");
        sb.append("<ActionImport Name=\"RepriceAll\" Action=\"").append(ns).append(".RepriceAll\"/>");
        sb.append("</EntityContainer>");

        sb.append("<Function Name=\"TopSelling\">");
        sb.append("<Parameter Name=\"count\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
        sb.append("<ReturnType Type=\"Collection(").append(ns).append(".E").append(j).append("_0)\"/>");
        sb.append("</Function>");
        sb.append("<Action Name=\"RepriceAll\">");
        sb.append("<Parameter Name=\"factor\" Type=\"Edm.Double\" Nullable=\"false\"/>");
        sb.append("</Action>");

        sb.append("</Schema>");
    }
}
