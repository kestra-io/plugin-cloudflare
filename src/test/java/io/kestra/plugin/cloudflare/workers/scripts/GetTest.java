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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 28203)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class GetTest {

    private static final String BASE_URL = "http://localhost:28203";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldFetchModuleScriptFromMultipartResponse() throws Exception {
        var boundary = "----kestratestboundary";
        var moduleSource = "export default { async fetch() { return new Response('hello'); } };";
        var body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n"
            + "Content-Type: application/javascript+module\r\n"
            + "\r\n"
            + moduleSource + "\r\n"
            + "--" + boundary + "--\r\n";

        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .withHeader("ETag", "etag-xyz")
                        .withBody(body)
                )
        );

        var task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("hello"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(moduleSource, output.getScript());
        assertEquals(Deploy.ScriptFormat.MODULE, output.getFormat());
        // Jetty rewrites the ETag with a `--gzip` suffix; assert prefix instead of exact match.
        assertTrue(output.getEtag() != null && output.getEtag().startsWith("etag-xyz"));

        verify(
            getRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
        );
    }

    @Test
    void shouldFetchServiceWorkerAsPlainJavascript() throws Exception {
        var source = "addEventListener('fetch', e => e.respondWith(new Response('legacy')));";

        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts/legacy"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/javascript")
                        .withHeader("ETag", "etag-legacy")
                        .withBody(source)
                )
        );

        var task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("legacy"))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(source, output.getScript());
        assertEquals(Deploy.ScriptFormat.SERVICE_WORKER, output.getFormat());
        assertTrue(output.getEtag() != null && output.getEtag().startsWith("etag-legacy"));
    }

    @Test
    void shouldFailOnServerError() {
        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts/missing"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            { "success": false, "errors": [{ "code": 10007, "message": "Script not found" }] }
                            """)
                )
        );

        var task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("missing"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("[10007]"), "missing code in: " + message);
        assertTrue(message.contains("Script not found"), "missing detail in: " + message);
        assertTrue(message.contains("HTTP 404"), "missing status in: " + message);
    }

    @Test
    void shouldParseMultipartBoundary() throws Exception {
        var boundary = "foo123";
        var body = "--" + boundary + "\r\n"
            + "Content-Type: application/javascript+module\r\n"
            + "\r\n"
            + "export default {};\r\n"
            + "--" + boundary + "--\r\n";

        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts/boundary"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .withBody(body)
                )
        );

        var task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("boundary"))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals("export default {};", output.getScript());
        assertEquals(Deploy.ScriptFormat.MODULE, output.getFormat());
    }
}
