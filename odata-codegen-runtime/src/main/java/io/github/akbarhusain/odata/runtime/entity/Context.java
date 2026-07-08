package io.github.akbarhusain.odata.runtime.entity;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.batch.BatchRequest;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.util.List;
import java.util.Map;

public record Context(
    String baseUrl,
    Serializer serializer,
    HttpTransport transport,
    AuthProvider authProvider,
    List<SchemaInfo> schemas,
    List<HttpInterceptor> interceptors,
    Map<String, Object> properties
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl = "";
        private Serializer serializer = Serializer.createDefault();
        private HttpTransport transport = HttpTransport.createDefault();
        private AuthProvider authProvider = AuthProvider.none();
        private List<SchemaInfo> schemas = List.of();
        private List<HttpInterceptor> interceptors = List.of();
        private Map<String, Object> properties = Map.of();

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder serializer(Serializer s) { this.serializer = s; return this; }
        public Builder transport(HttpTransport t) { this.transport = t; return this; }
        public Builder authProvider(AuthProvider a) { this.authProvider = a; return this; }
        public Builder schemas(List<SchemaInfo> s) { this.schemas = List.copyOf(s); return this; }
        public Builder interceptors(List<HttpInterceptor> i) { this.interceptors = List.copyOf(i); return this; }
        public Builder properties(Map<String, Object> p) { this.properties = Map.copyOf(p); return this; }

        public Context build() {
            return new Context(baseUrl, serializer, transport, authProvider, schemas, interceptors, properties);
        }
    }

    public ContextPath basePath() {
        return new ContextPath(baseUrl);
    }

    public BatchRequest batch() {
        return new BatchRequest(this);
    }
}
