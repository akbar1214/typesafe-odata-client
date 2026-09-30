package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.internal.MultipartHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class BatchRequest {
    private final Context context;
    private final List<Object> entries = new ArrayList<>();
    private boolean continueOnError;

    public BatchRequest(Context context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    public BatchRequest continueOnError() {
        this.continueOnError = true;
        return this;
    }

    public BatchRequest add(BatchOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        entries.add(operation);
        return this;
    }

    public BatchRequest addChangeset(Changeset changeset) {
        if (changeset == null) {
            throw new IllegalArgumentException("changeset must not be null");
        }
        entries.add(changeset);
        return this;
    }

    public int size() {
        return entries.stream().mapToInt(entry -> entry instanceof Changeset changeset
                ? changeset.size() : 1).sum();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public BatchResponse execute() {
        if (entries.isEmpty()) {
            return new BatchResponse(List.of());
        }
        try {
            PreparedBatch prepared = prepare();
            String boundary = MultipartHelper.generateBoundary();
            HttpResponse response = submitBatch(prepared, boundary).join();
            return parseResponse(response, prepared);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new ODataException("Batch request failed: interrupted", cause);
            }
            // JdkHttpTransport rewraps InterruptedException as
            // ODataException("HTTP request interrupted", e), so the cause reaching here
            // in production is an ODataException, NOT an InterruptedException. Without
            // this branch the handler above never fired on the real path, `throw runtime`
            // won, and the calling thread's interrupt flag was lost -- the transport only
            // sets the flag on its own worker thread. Decision 160 requires every
            // sync-over-async wrapper to restore it; EntityOperations.rethrowCause
            // already carries both branches, this is the one call site that missed the
            // second. Rethrowing the original ODataException keeps the cause typed.
            if (cause instanceof ODataException odataException
                    && odataException.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw odataException;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ODataException("Batch request failed", cause);
        }
    }

    public CompletableFuture<BatchResponse> executeAsync() {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(new BatchResponse(List.of()));
        }
        try {
            PreparedBatch prepared = prepare();
            String boundary = MultipartHelper.generateBoundary();
            return submitBatch(prepared, boundary)
                    .thenApply(response -> parseResponse(response, prepared));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private PreparedBatch prepare() {
        List<Object> prepared = MultipartHelper.prepareEntries(entries);
        List<Object> resolvedEntries = new ArrayList<>(prepared.size());
        List<PlannedOperation> operations = new ArrayList<>(size());
        int order = 0;
        int group = 0;
        String root = trimTrailingSlash(context.baseUrl());
        for (Object entry : prepared) {
            if (entry instanceof BatchOperation operation) {
                BatchOperation resolved = resolveOperationUrl(operation, root);
                resolvedEntries.add(resolved);
                operations.add(new PlannedOperation(plannedId(resolved), null, order++));
            } else {
                Changeset changeset = (Changeset) entry;
                String groupId = "changeset-" + group++;
                List<BatchOperation> resolved = new ArrayList<>(changeset.size());
                for (BatchOperation operation : changeset.operations()) {
                    BatchOperation operationWithUrl = resolveOperationUrl(operation, root);
                    resolved.add(operationWithUrl);
                    operations.add(new PlannedOperation(plannedId(operationWithUrl), groupId, order++));
                }
                resolvedEntries.add(new Changeset(resolved));
            }
        }
        return new PreparedBatch(List.copyOf(resolvedEntries), List.copyOf(operations));
    }

    private CompletableFuture<HttpResponse> submitBatch(PreparedBatch prepared, String boundary) {
        byte[] body = MultipartHelper.encodeBatchRequest(boundary, prepared.entries());
        ContextPath batchPath = context.basePath().addSegment("$batch");
        String batchUrl = batchPath.toUrl();
        Map<String, List<String>> headers = EntityOperations.requestHeaders(context, batchUrl,
                Map.of("Content-Type", "multipart/mixed; boundary=" + boundary,
                        "Accept", "multipart/mixed"), "multipart/mixed");
        if (continueOnError) {
            setHeaderCaseInsensitive(headers, "Prefer", "continue-on-error=true");
        }
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(batchUrl)
                .headers(headers)
                .body(body)
                .connectTimeout(context.connectTimeout())
                .readTimeout(context.readTimeout())
                .build();
        HttpTransport transport = EntityOperations.buildTransportChain(context, context.transport());
        try {
            CompletableFuture<HttpResponse> result = transport.submit(request);
            return result == null ? CompletableFuture.failedFuture(
                    new NullPointerException("HTTP transport returned null future")) : result;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private BatchResponse parseResponse(HttpResponse response, PreparedBatch prepared) {
        if (response == null) {
            throw new ODataException("Batch request returned a null response");
        }
        if (!response.isSuccessful()) {
            throw ODataException.fromResponse(response);
        }
        try {
            List<String> contentTypes = response.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
            String contentType = contentTypes.isEmpty() ? "" : contentTypes.get(0);
            if (contentTypes.isEmpty() || contentTypes.stream().anyMatch(value -> !MultipartHelper.isMultipartMixed(value))) {
                throw new ODataException(response.statusCode(),
                        "Expected multipart/mixed response but got: " + contentType);
            }
            String responseBoundary;
            try {
                responseBoundary = MultipartHelper.extractBoundary(contentType);
                for (String value : contentTypes) {
                    String otherBoundary = MultipartHelper.extractBoundary(value);
                    if (responseBoundary == null ? otherBoundary != null : !responseBoundary.equals(otherBoundary)) {
                        throw new ODataException(response.statusCode(),
                                "Conflicting multipart response boundaries");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw new ODataException(response.statusCode(), "Invalid multipart response boundary", e);
            }
            if (responseBoundary == null) {
                throw new ODataException(response.statusCode(),
                        "Expected multipart/mixed response with a boundary");
            }
            MultipartHelper.DecodedResponse decoded = MultipartHelper.decodeResponseDetailed(responseBoundary,
                    response.body());
            return correlate(decoded, prepared.operations());
        } catch (ODataException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // Only part-header validation still reaches here (boundary extraction is wrapped
            // in its own catch above). Reporting it as a boundary problem sent triage after
            // the wrong cause — the boundary was already accepted at this point.
            throw new ODataException(response.statusCode(),
                    "Malformed multipart response part header", e);
        }
    }

    private BatchResponse correlate(MultipartHelper.DecodedResponse decoded,
                                     List<PlannedOperation> operations) {
        List<MultipartHelper.DecodedPart> wireParts = decoded.parts();
        Map<String, BatchResult<?>> byId = new LinkedHashMap<>();
        Map<String, List<MultipartHelper.DecodedPart>> byGroup = new LinkedHashMap<>();
        List<MultipartHelper.DecodedPart> unkeyed = new ArrayList<>();
        for (MultipartHelper.DecodedPart part : wireParts) {
            BatchResult<?> result = part.result();
            String id = result.contentId();
            if (id != null) {
                if (byId.putIfAbsent(id, result) != null) {
                    throw new ODataException("Duplicate Content-ID in batch response: " + id);
                }
            } else if (part.groupId() == null) {
                unkeyed.add(part);
            }
            if (part.groupId() != null) {
                byGroup.computeIfAbsent(part.groupId(), ignored -> new ArrayList<>()).add(part);
            }
        }

        Map<String, PlannedOperation> plannedById = new LinkedHashMap<>();
        Map<String, List<PlannedOperation>> plannedGroups = new LinkedHashMap<>();
        for (PlannedOperation operation : operations) {
            if (operation.contentId() != null) {
                if (plannedById.putIfAbsent(operation.contentId(), operation) != null) {
                    throw new ODataException("Duplicate Content-ID in batch plan: " + operation.contentId());
                }
            }
            if (operation.groupId() != null) {
                plannedGroups.computeIfAbsent(operation.groupId(), ignored -> new ArrayList<>()).add(operation);
            }
        }

        Map<String, List<MultipartHelper.DecodedPart>> normalizedGroups = new LinkedHashMap<>();
        Set<String> mappedGroups = new HashSet<>();
        List<String> unmappedGroups = new ArrayList<>();
        for (Map.Entry<String, List<MultipartHelper.DecodedPart>> entry : byGroup.entrySet()) {
            List<MultipartHelper.DecodedPart> observed = entry.getValue();
            List<String> observedIds = observed.stream()
                    .map(part -> part.result().contentId())
                    .filter(Objects::nonNull)
                    .toList();
            String target = null;
            if (!observedIds.isEmpty()) {
                for (String plannedGroup : plannedGroups.keySet()) {
                    if (mappedGroups.contains(plannedGroup)) {
                        continue;
                    }
                    List<PlannedOperation> expected = plannedGroups.get(plannedGroup);
                    if (observedIds.stream().allMatch(id -> expected.stream()
                            .anyMatch(op -> id.equals(op.contentId())))) {
                        target = plannedGroup;
                        break;
                    }
                }
            } else {
                for (String plannedGroup : plannedGroups.keySet()) {
                    if (!mappedGroups.contains(plannedGroup)) {
                        target = plannedGroup;
                        break;
                    }
                }
            }
            if (target != null) {
                normalizedGroups.computeIfAbsent(target, ignored -> new ArrayList<>()).addAll(observed);
                mappedGroups.add(target);
            } else {
                unmappedGroups.add(entry.getKey());
            }
        }
        if (!unmappedGroups.isEmpty()) {
            throw new ODataException("Unexpected changeset group in batch response: " + unmappedGroups.get(0));
        }
        byGroup = normalizedGroups;
        associateKeyedFlatParts(wireParts, byId, byGroup, plannedById);
        associateUnkeyedFlatFailedParts(operations, unkeyed, byGroup, plannedGroups);

        for (String id : byId.keySet()) {
            if (!plannedById.containsKey(id)) {
                throw new ODataException("Unexpected Content-ID in batch response: " + id);
            }
        }
        for (String groupId : byGroup.keySet()) {
            if (!plannedGroups.containsKey(groupId)) {
                throw new ODataException("Unexpected changeset group in batch response: " + groupId);
            }
        }

        Map<String, BatchResult<?>> resolvedById = new HashMap<>();
        Map<String, BatchResult<?>> resolvedUnkeyed = new HashMap<>();
        int unkeyedIndex = 0;
        for (PlannedOperation operation : operations) {
            if (operation.groupId() == null && operation.contentId() != null) {
                BatchResult<?> result = byId.get(operation.contentId());
                if (result == null) {
                    throw new ODataException("Missing Content-ID in batch response: " + operation.contentId());
                }
                resolvedById.put(operation.contentId(), result);
            } else if (operation.groupId() == null && unkeyedIndex < unkeyed.size()) {
                resolvedUnkeyed.put(operation.orderKey(), unkeyed.get(unkeyedIndex++).result());
            } else if (operation.groupId() == null) {
                throw new ODataException("Missing response part for unkeyed batch operation");
            }
        }
        if (unkeyedIndex != unkeyed.size()) {
            throw new ODataException("Unexpected unkeyed response part in batch");
        }

        for (Map.Entry<String, List<PlannedOperation>> group : plannedGroups.entrySet()) {
            List<MultipartHelper.DecodedPart> observed = byGroup.getOrDefault(group.getKey(), List.of());
            List<PlannedOperation> expected = group.getValue();
            if (observed.isEmpty()) {
                throw new ODataException("Missing response part for changeset " + group.getKey());
            }
            // A failed change set is answered with a SINGLE part carrying 424 and NO
            // Content-ID (OData v4.01 Part 1 §11.7.4: the service echoes Content-ID only
            // when the request supplied one, and a collapsed error part has none). For a
            // one-operation change set that shape is indistinguishable from the success
            // shape by part count alone, so it must be recognised BEFORE the equal-count
            // branch — otherwise the missing id is reported as a protocol violation and
            // the collapse handling below is unreachable.
            if (observed.size() == 1 && expected.size() == 1
                    && !observed.get(0).result().isSuccessful()
                    && observed.get(0).result().contentId() == null) {
                Set<String> related = Set.of(expected.get(0).contentId());
                resolvedById.put(expected.get(0).contentId(), observed.get(0).result()
                        .withRelatedContentIds(related, group.getKey()));
            } else if (observed.size() == expected.size()) {
                Set<String> seen = new HashSet<>();
                for (MultipartHelper.DecodedPart part : observed) {
                    String id = part.result().contentId();
                    if (id == null || !expected.stream().anyMatch(op -> id.equals(op.contentId()))) {
                        throw new ODataException("Missing or unexpected Content-ID in changeset " + group.getKey());
                    }
                    if (!seen.add(id)) {
                        throw new ODataException("Duplicate Content-ID in changeset " + group.getKey() + ": " + id);
                    }
                    resolvedById.put(id, part.result().withType(Object.class));
                }
            } else if (observed.size() == 1 && expected.size() > 1
                    && !observed.get(0).result().isSuccessful()) {
                String id = observed.get(0).result().contentId();
                if (id != null && !expected.stream().anyMatch(op -> id.equals(op.contentId()))) {
                    throw new ODataException("Unexpected Content-ID in failed changeset " + group.getKey());
                }
                Set<String> related = new LinkedHashSet<>();
                expected.forEach(op -> related.add(op.contentId()));
                BatchResult<?> collapsed = observed.get(0).result().withRelatedContentIds(related, group.getKey());
                expected.forEach(op -> resolvedById.put(op.contentId(), collapsed));
            } else {
                throw new ODataException("Unexpected number of response parts for changeset " + group.getKey());
            }
        }

        List<BatchResult<?>> ordered = new ArrayList<>(operations.size());
        for (PlannedOperation operation : operations) {
            BatchResult<?> result = operation.groupId() == null
                    ? (operation.contentId() != null ? resolvedById.get(operation.contentId())
                    : resolvedUnkeyed.get(operation.orderKey()))
                    : resolvedById.get(operation.contentId());
            if (result == null) {
                throw new ODataException("No result correlated to batch operation " + operation.order());
            }
            ordered.add(result);
        }
        Map<Integer, BatchResult<?>> byWireIndex = new HashMap<>();
        for (BatchResult<?> result : ordered) {
            byWireIndex.putIfAbsent(result.wireIndex(), result);
        }
        List<BatchResult<?>> wireResults = new ArrayList<>(decoded.results().size());
        for (MultipartHelper.DecodedPart part : decoded.parts()) {
            BatchResult<?> result = byWireIndex.get(part.wireIndex());
            wireResults.add(result != null ? result : part.result());
        }
        return new BatchResponse(ordered, wireResults);
    }

    private static void associateKeyedFlatParts(
            List<MultipartHelper.DecodedPart> wireParts,
            Map<String, BatchResult<?>> byId,
            Map<String, List<MultipartHelper.DecodedPart>> byGroup,
            Map<String, PlannedOperation> plannedById) {
        for (MultipartHelper.DecodedPart part : wireParts) {
            if (part.groupId() != null) {
                continue;
            }
            String id = part.result().contentId();
            PlannedOperation operation = plannedById.get(id);
            if (operation == null || operation.groupId() == null) {
                continue;
            }
            byGroup.computeIfAbsent(operation.groupId(), ignored -> new ArrayList<>()).add(part);
            byId.remove(id);
        }
    }

    /**
     * Associates a flattened (non-nested) collapsed change-set failure part with the change
     * set it belongs to.
     *
     * <p>OData v4.01 Part 1 §11.7.1 requires response parts to appear in the same order as
     * the corresponding request parts, so a change set answered with a single error part
     * occupies the position of its <em>first</em> operation in that ordering. Walking the
     * submitted plan and consuming one pool slot per standalone unkeyed operation and one
     * per change set reproduces that ordering deterministically.
     *
     * <p>Selecting "the first failing part anywhere in the pool" instead — the previous
     * behaviour — is not decidable from the wire, because a coincidentally failing
     * standalone operation is indistinguishable from the collapsed part. Guessing there
     * removed an unrelated operation's part from the positional pool, so every later
     * standalone result shifted by one and was returned with another operation's status
     * code and body, with no error raised. Anything the ordering does not force is left
     * unassociated, which surfaces as a loud "Missing response part for changeset".
     */
    private static void associateUnkeyedFlatFailedParts(
            List<PlannedOperation> operations,
            List<MultipartHelper.DecodedPart> unkeyed,
            Map<String, List<MultipartHelper.DecodedPart>> byGroup,
            Map<String, List<PlannedOperation>> plannedGroups) {
        if (unkeyed.isEmpty()) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, List<PlannedOperation>> entry : plannedGroups.entrySet()) {
            if (byGroup.getOrDefault(entry.getKey(), List.of()).isEmpty()) {
                missing.add(entry.getKey());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<MultipartHelper.DecodedPart> pool = new ArrayList<>(unkeyed);
        Map<String, MultipartHelper.DecodedPart> collapsed = new LinkedHashMap<>();
        int cursor = 0;
        for (PlannedOperation operation : operations) {
            if (operation.groupId() == null) {
                // A standalone unkeyed operation occupies exactly one slot; a standalone
                // operation carrying an explicit Content-ID is correlated by id elsewhere.
                if (operation.contentId() == null) {
                    cursor++;
                }
                continue;
            }
            if (!missing.contains(operation.groupId()) || collapsed.containsKey(operation.groupId())) {
                continue;
            }
            if (cursor >= pool.size()) {
                break;
            }
            MultipartHelper.DecodedPart candidate = pool.get(cursor);
            cursor++;
            if (candidate.result().isSuccessful()) {
                // A successful part at the change set's position is not a collapsed
                // failure; leave the group unassociated rather than mis-attribute it.
                continue;
            }
            collapsed.put(operation.groupId(), candidate);
        }
        if (collapsed.isEmpty()) {
            return;
        }
        for (Map.Entry<String, MultipartHelper.DecodedPart> entry : collapsed.entrySet()) {
            byGroup.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
        }
        Set<MultipartHelper.DecodedPart> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        claimed.addAll(collapsed.values());
        unkeyed.removeIf(claimed::contains);
    }

    private static String plannedId(BatchOperation operation) {
        return operation.contentId() == null ? null
                : BatchOperation.canonicalContentId(operation.contentId());
    }

    private static BatchOperation resolveOperationUrl(BatchOperation operation, String baseUrl) {
        if (ContextPath.isAbsoluteHttpUrl(operation.url())) {
            return operation;
        }
        String value = operation.url();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("batch URL must contain a request target");
        }
        int suffix = firstSuffix(value);
        String path = value.substring(0, suffix);
        String tail = value.substring(suffix);
        List<String> segments = new ArrayList<>();
        for (String segment : splitRequestPath(path)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("batch URL traversal escapes the service root");
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("batch URL must contain a request target");
        }
        return operation.withUrl(baseUrl + "/" + String.join("/", segments) + tail);
    }

    private static List<String> splitRequestPath(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\'') {
                current.append(c);
                if (quoted && i + 1 < path.length() && path.charAt(i + 1) == '\'') {
                    current.append(path.charAt(++i));
                } else {
                    quoted = !quoted;
                }
            } else if (c == '/' && !quoted) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }

    private static int firstSuffix(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && (c == '?' || c == '#')) {
                return i;
            }
        }
        return value.length();
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static void setHeaderCaseInsensitive(Map<String, List<String>> headers, String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, new ArrayList<>(List.of(value)));
    }

    private record PreparedBatch(List<Object> entries, List<PlannedOperation> operations) {
    }

    private record PlannedOperation(String contentId, String groupId, int order) {
        String orderKey() {
            return Integer.toString(order);
        }
    }
}
