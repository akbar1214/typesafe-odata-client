package io.github.akbarhusain.odata.runtime.bench;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.akbarhusain.odata.runtime.entity.*;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.http.*;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;
import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class PerfBenchmark {

    public static final class TestEntity {
        public int id;
        public String name;
        public TestEntity() {}
        public int getId() { return id; }
        public String getName() { return name; }
    }

    public static final class TestComplex {
        public int id;
        public String name;
        public double price;
        public boolean active;
        public List<String> tags;
        public Map<String, Object> metadata;
        public TestComplex() {}
    }

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;

        // ---------- WireMock setup ----------
        WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .jettyAcceptors(4)
                .jettyHeaderBufferSize(8192)
                .containerThreads(16));
        wireMock.start();
        int port = wireMock.port();

        // Large collection response (10 items, each with 5 fields)
        String collectionJson = buildCollectionJson();
        wireMock.stubFor(get(urlPathEqualTo("/collection"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(collectionJson)));

        // Empty collection response
        wireMock.stubFor(get(urlPathEqualTo("/empty"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"value\":[]}")));

        // Single entity response
        wireMock.stubFor(get(urlPathEqualTo("/entity"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"TestEntity\"}")));

        // Large entity response
        String largeEntityJson = buildLargeEntityJson();
        wireMock.stubFor(get(urlPathEqualTo("/large-entity"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(largeEntityJson)));

        // Paginated collection (with nextLink)
        String paginatedJson = buildPaginatedJson();
        wireMock.stubFor(get(urlPathEqualTo("/paginated"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(paginatedJson)));

        // ---------- Context ----------
        HttpTransport transport = new JdkHttpTransport();
        Serializer serializer = new JacksonSerializer();
        Context ctx = Context.builder()
                .baseUrl("http://localhost:" + port)
                .transport(transport)
                .serializer(serializer)
                .authProvider(AuthProvider.none())
                .build();

        // ---------- Warmup ----------
        long warmupStart = System.nanoTime();
        int warmupIters = 20_000;
        for (int i = 0; i < warmupIters; i++) {
            int phase = i % 4;
            switch (phase) {
                case 0 -> {
                    ContextPath path = ctx.basePath().addSegment("collection");
                    EntityOperations.executeAndGetCollection(ctx, path, TestEntity.class);
                }
                case 1 -> {
                    ContextPath path = ctx.basePath().addSegment("entity");
                    EntityOperations.executeAndGetEntity(ctx, path, TestEntity.class);
                }
                case 2 -> {
                    ContextPath path = ctx.basePath().addSegment("collection");
                    EntityOperations.executeAndGetCollection(ctx, path, TestComplex.class);
                }
                case 3 -> {
                    ContextPath path = ctx.basePath().addSegment("large-entity");
                    EntityOperations.executeAndGetEntity(ctx, path, TestComplex.class);
                }
            }
        }
        long warmupEnd = System.nanoTime();
        System.out.println("WARMUP_COMPLETE:" + (warmupEnd - warmupStart) / 1_000_000 + "ms");
        System.out.flush();

        // ---------- Benchmark ----------
        long start = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            int phase = i % 5;
            switch (phase) {
                case 0 -> {
                    ContextPath path = ctx.basePath().addSegment("collection");
                    CollectionPage<TestEntity> page = EntityOperations.executeAndGetCollection(
                            ctx, path, TestEntity.class);
                    checksum += page.currentPage().size();
                }
                case 1 -> {
                    ContextPath path = ctx.basePath().addSegment("entity");
                    TestEntity entity = EntityOperations.executeAndGetEntity(
                            ctx, path, TestEntity.class);
                    checksum += entity.getId();
                }
                case 2 -> {
                    ContextPath path = ctx.basePath().addSegment("collection");
                    CollectionPage<TestComplex> page = EntityOperations.executeAndGetCollection(
                            ctx, path, TestComplex.class);
                    checksum += page.currentPage().size();
                }
                case 3 -> {
                    ContextPath path = ctx.basePath().addSegment("large-entity");
                    TestComplex entity = EntityOperations.executeAndGetEntity(
                            ctx, path, TestComplex.class);
                    checksum += entity.id;
                }
                case 4 -> {
                    ContextPath path = ctx.basePath().addSegment("paginated");
                    CollectionPage<TestEntity> page = EntityOperations.executeAndGetCollection(
                            ctx, path, TestEntity.class);
                    checksum += page.currentPage().size();
                    if (page.getNextLink() != null) checksum++;
                }
            }
        }
        long end = System.nanoTime();
        double totalSecs = (end - start) / 1_000_000_000.0;
        double opsPerSec = (iterations * 5) / totalSecs;
        System.out.println("RESULT:iterations=" + iterations
                + " ops=" + (iterations * 5)
                + " time=" + String.format("%.3f", totalSecs) + "s"
                + " ops/s=" + String.format("%.0f", opsPerSec)
                + " checksum=" + checksum);
        System.out.flush();

        wireMock.stop();
    }

    private static String buildCollectionJson() {
        return "{\"value\":["
                + "{\"id\":1,\"name\":\"Alpha\"},"
                + "{\"id\":2,\"name\":\"Beta\"},"
                + "{\"id\":3,\"name\":\"Gamma\"},"
                + "{\"id\":4,\"name\":\"Delta\"},"
                + "{\"id\":5,\"name\":\"Epsilon\"},"
                + "{\"id\":6,\"name\":\"Zeta\"},"
                + "{\"id\":7,\"name\":\"Eta\"},"
                + "{\"id\":8,\"name\":\"Theta\"},"
                + "{\"id\":9,\"name\":\"Iota\"},"
                + "{\"id\":10,\"name\":\"Kappa\"}"
                + "],\"@odata.count\":100}";
    }

    private static String buildLargeEntityJson() {
        return "{\"id\":1,\"name\":\"LargeEntity\",\"price\":99.99,\"active\":true,"
                + "\"tags\":[\"tag1\",\"tag2\",\"tag3\",\"tag4\",\"tag5\"],"
                + "\"metadata\":{\"key1\":\"value1\",\"key2\":\"value2\",\"key3\":\"value3\"}}";
    }

    private static String buildPaginatedJson() {
        return "{\"value\":["
                + "{\"id\":1,\"name\":\"Page1\"},"
                + "{\"id\":2,\"name\":\"Page2\"}"
                + "],\"@odata.nextLink\":\"?$skip=2\",\"@odata.count\":100}";
    }
}
