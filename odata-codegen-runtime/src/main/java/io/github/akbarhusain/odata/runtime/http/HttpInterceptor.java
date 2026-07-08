package io.github.akbarhusain.odata.runtime.http;

public interface HttpInterceptor {
    HttpResponse intercept(HttpRequest request, HttpTransport delegate);
}
