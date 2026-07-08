package io.github.akbarhusain.odata.runtime.internal;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.exception.*;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsFailureTest {

    private WireMockServer server;
    private Context ctx;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        ctx = Context.builder()
                .baseUrl(server.baseUrl())
                .transport(new JdkHttpTransport())
                .build();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void failureStatusCodesMapToTypedExceptions() {
        Object[][] cases = {
                {400, BadRequestException.class},
                {401, UnauthorizedException.class},
                {403, ForbiddenException.class},
                {404, NotFoundException.class},
                {409, ConflictException.class},
                {429, RateLimitException.class},
                {500, ODataException.class},
        };

        for (Object[] c : cases) {
            int status = (int) c[0];
            Class<? extends Throwable> expected = (Class<? extends Throwable>) c[1];

            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(status)));

            assertThrows(expected,
                    () -> EntityOperations.executeAndGetEntity(ctx,
                            ctx.basePath().addSegment("People"), Object.class),
                    "HTTP " + status + " should map to " + expected.getSimpleName());
        }
    }

    @Test
    void collectionFailureStatusCodesMapToTypedExceptions() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(404)));

        assertThrows(NotFoundException.class,
                () -> EntityOperations.executeAndGetCollection(ctx,
                        ctx.basePath().addSegment("People"), Object.class));
    }
}
