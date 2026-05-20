package io.kestra.plugin.cloudflare.workers.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 28202)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class DeleteTest {

    private static final String BASE_URL = "http://localhost:28202";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDeleteScript() throws Exception {
        stubFor(
            delete(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .willReturn(okJson("""
                    { "success": true, "errors": [], "messages": [], "result": null }
                    """))
        );

        var task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("hello"))
            .build();

        task.run(runContextFactory.of());

        verify(
            deleteRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
        );
    }

    @Test
    void shouldDeleteWithForceQueryParam() throws Exception {
        stubFor(
            delete(urlEqualTo("/accounts/acct-1/workers/scripts/hello?force=true"))
                .willReturn(okJson("""
                    { "success": true, "result": null }
                    """))
        );

        var task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("hello"))
            .force(Property.ofValue(true))
            .build();

        task.run(runContextFactory.of());

        verify(deleteRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/hello?force=true")));
    }

    @Test
    void shouldFailOnNotFound() {
        stubFor(
            delete(urlEqualTo("/accounts/acct-1/workers/scripts/missing"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            { "success": false, "errors": [{ "code": 10007, "message": "Script not found" }] }
                            """)
                )
        );

        var task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("missing"))
            .build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
    }

    @Test
    void shouldFormatCloudflareEnvelopeError() {
        stubFor(
            delete(urlEqualTo("/accounts/acct-1/workers/scripts/ghost"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"success":false,"errors":[{"code":10007,"message":"This Worker does not exist on your account."}],"messages":[],"result":null}
                            """)
                )
        );

        var task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("ghost"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("[10007]"), "missing code in: " + message);
        assertTrue(message.contains("This Worker does not exist on your account."), "missing detail in: " + message);
        assertTrue(message.contains("HTTP 404"), "missing status in: " + message);
    }

    @Test
    void shouldFallBackOnNonEnvelopeError() {
        stubFor(
            delete(urlEqualTo("/accounts/acct-1/workers/scripts/badgw"))
                .willReturn(
                    aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>oops</html>")
                )
        );

        var task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("badgw"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("502"), "expected status hint in: " + message);
        // No fabricated code prefix for non-envelope bodies.
        assertFalse(message.contains("[10007]"), "should not invent a code: " + message);
    }
}
