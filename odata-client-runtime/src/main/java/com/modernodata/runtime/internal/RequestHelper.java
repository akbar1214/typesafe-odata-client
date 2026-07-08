package com.modernodata.runtime.internal;

import com.modernodata.runtime.client.EntityOperations;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import com.modernodata.runtime.http.*;
import com.modernodata.runtime.paging.CollectionPage;

import java.util.concurrent.CompletableFuture;
import java.util.Map;

@Deprecated
public class RequestHelper {

    private RequestHelper() {}

    public static HttpResponse executeSync(Context context, HttpMethod method, ContextPath path,
                                            byte[] body, Map<String, String> extraHeaders) {
        return EntityOperations.executeSync(context, method, path, body, extraHeaders);
    }

    public static CompletableFuture<HttpResponse> executeAsync(Context context, HttpMethod method,
                                                                ContextPath path, byte[] body,
                                                                Map<String, String> extraHeaders) {
        return EntityOperations.executeAsync(context, method, path, body, extraHeaders);
    }

    public static <T> T executeAndGetEntity(Context context, ContextPath path, Class<T> type) {
        return EntityOperations.executeAndGetEntity(context, path, type);
    }

    public static <T> T executePostEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        return EntityOperations.executePostEntity(context, path, entity, responseType);
    }

    public static <T> T executePatchEntity(Context context, ContextPath path, Object entity, Class<T> responseType) {
        return EntityOperations.executePatchEntity(context, path, entity, responseType);
    }

    public static <T> T executePatchEntityWithETag(Context context, ContextPath path, Object entity,
                                                     Class<T> responseType, String etag) {
        return EntityOperations.executePatchEntityWithETag(context, path, entity, responseType, etag);
    }

    public static void executeDelete(Context context, ContextPath path) {
        EntityOperations.executeDelete(context, path);
    }

    public static void executeDeleteWithETag(Context context, ContextPath path, String etag) {
        EntityOperations.executeDeleteWithETag(context, path, etag);
    }

    public static void addRef(Context context, ContextPath navigationPath, String targetEntityUrl) {
        EntityOperations.addRef(context, navigationPath, targetEntityUrl);
    }

    public static void removeRef(Context context, ContextPath navigationPath, String targetKey) {
        EntityOperations.removeRef(context, navigationPath, targetKey);
    }

    public static <T> CollectionPage<T> executeAndGetCollection(Context context, ContextPath path,
                                                                  Class<T> elementType) {
        return EntityOperations.executeAndGetCollection(context, path, elementType);
    }

    public static void checkResponse(HttpResponse response) {
        EntityOperations.checkResponse(response);
    }

    public static HttpTransport buildTransportChain(Context context, HttpTransport real) {
        return EntityOperations.buildTransportChain(context, real);
    }
}
