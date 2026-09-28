package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;

import java.util.Map;

final class TripPinCleanupSupport {

    static final int DEFAULT_ATTEMPTS = 10;
    static final long DEFAULT_DELAY_MILLIS = 300L;
    private static final int NO_CONTENT_CONFIRMATIONS = 2;

    private TripPinCleanupSupport() {}

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface CheckedAction {
        void run(Cleanup cleanup) throws Exception;
    }

    static final class Cleanup {
        private final Context context;
        private final ContextPath entityPath;
        private boolean required;
        private String etag;

        private Cleanup(Context context, ContextPath entityPath) {
            this.context = context;
            this.entityPath = entityPath;
        }

        void requireCleanup() {
            required = true;
        }

        void setEtag(String etag) {
            this.etag = etag;
        }

        void complete() {
            required = false;
        }

        private void run(Throwable originalFailure) throws Exception {
            if (!required) {
                return;
            }
            try {
                deletePerson(context, entityPath, etag);
            } catch (Exception | Error cleanupFailure) {
                if (originalFailure == null) {
                    throw cleanupFailure;
                }
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void withCleanup(Context context, ContextPath entityPath, CheckedAction action) throws Exception {
        Cleanup cleanup = new Cleanup(context, entityPath);
        Throwable originalFailure = null;
        try {
            action.run(cleanup);
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            cleanup.run(originalFailure);
        }
    }

    static void deletePerson(Context context, ContextPath entityPath, String etag) throws Exception {
        deletePerson(context, entityPath, etag, DEFAULT_ATTEMPTS, DEFAULT_DELAY_MILLIS, Thread::sleep);
    }

    static void deletePerson(Context context, ContextPath entityPath, String etag,
                             int attempts, long delayMillis, Sleeper sleeper) throws Exception {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        if (sleeper == null) {
            throw new NullPointerException("sleeper must not be null");
        }

        HttpResponse last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            last = EntityOperations.executeSync(context, HttpMethod.GET, entityPath, null, null);
            if (last.statusCode() == 404) {
                return;
            }
            if (last.statusCode() == 204) {
                pause(delayMillis, sleeper);
                continue;
            }
            if (last.statusCode() != 200) {
                throw cleanupFailure(entityPath, last, "could not read entity for cleanup");
            }

            String currentEtag = responseETag(last);
            try {
                EntityOperations.executeDeleteWithETag(context, entityPath,
                        currentEtag == null ? etag : currentEtag);
            } catch (NotFoundException ignored) {
                return;
            }
            awaitDeletion(context, entityPath, attempts, delayMillis, sleeper);
            return;
        }
        throw cleanupFailure(entityPath, last, "could not confirm deletion");
    }

    static void awaitDeletion(Context context, ContextPath entityPath) throws Exception {
        awaitDeletion(context, entityPath, DEFAULT_ATTEMPTS, DEFAULT_DELAY_MILLIS, Thread::sleep);
    }

    private static void awaitDeletion(Context context, ContextPath entityPath,
                                      int attempts, long delayMillis, Sleeper sleeper) throws Exception {
        HttpResponse last = null;
        int consecutiveNoContent = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            last = EntityOperations.executeSync(context, HttpMethod.GET, entityPath, null, null);
            if (last.statusCode() == 404) {
                return;
            }
            if (last.statusCode() == 204) {
                consecutiveNoContent++;
                if (consecutiveNoContent >= NO_CONTENT_CONFIRMATIONS) {
                    return;
                }
                pause(delayMillis, sleeper);
                continue;
            }
            consecutiveNoContent = 0;
            if (last.statusCode() == 200) {
                pause(delayMillis, sleeper);
                continue;
            }
            throw cleanupFailure(entityPath, last, "could not verify deletion");
        }
        throw cleanupFailure(entityPath, last, "could not confirm deletion");
    }

    private static void pause(long delayMillis, Sleeper sleeper) throws InterruptedException {
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static String responseETag(HttpResponse response) {
        for (Map.Entry<String, java.util.List<String>> entry : response.headers().entrySet()) {
            if (entry.getKey() != null
                    && (entry.getKey().equalsIgnoreCase("ETag")
                    || entry.getKey().equalsIgnoreCase("odata.etag"))) {
                return entry.getValue() == null || entry.getValue().isEmpty()
                        ? null : entry.getValue().get(0);
            }
        }
        return null;
    }

    private static IllegalStateException cleanupFailure(ContextPath entityPath, HttpResponse response,
                                                       String reason) {
        String status = response == null ? "no response" : "HTTP " + response.statusCode();
        return new IllegalStateException("TripPin cleanup " + reason + " for " + entityPath.toUrl()
                + " (" + status + ")");
    }

    static boolean isTripPinLinkMutationFault(HttpResponse response) {
        if (response == null || response.statusCode() != 500) {
            return false;
        }
        String text = response.getText();
        return text.contains("Property set method not found")
                || text.isBlank()
                || (text.contains("InternalServerError") && !text.contains("relative URI")
                        && !text.contains("odata.context") && !text.contains("target"));
    }
}
