package com.modernodata.runtime.http;

public interface HttpInterceptor {
    HttpResponse intercept(HttpRequest request, HttpTransport delegate);
}
