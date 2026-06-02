package io.kestra.plugin.cloudflare.workers.scripts;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 28201)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class DeployTest {

    private static final String BASE_URL = "http://localhost:28201";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDeployModuleWorker() throws Exception {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "id": "hello",
                        "etag": "etag-abc",
                        "modified_on": "2025-03-14T00:00:00Z"
                      }
                    }
                    """))
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("hello"))
            .script(Property.ofValue("""
                export default {
                  async fetch() { return new Response("ok"); }
                };
                """))
            .compatibilityDate(Property.ofValue(java.time.LocalDate.parse("2025-03-14")))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("hello", output.getId());
        assertEquals("etag-abc", output.getEtag());

        verify(
            putRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/hello"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBody(containing("name=\"metadata\""))
                .withRequestBody(containing("\"main_module\":\"worker.js\""))
                .withRequestBody(containing("\"compatibility_date\":\"2025-03-14\""))
                .withRequestBody(containing("name=\"worker.js\""))
                .withRequestBody(containing("filename=\"worker.js\""))
                .withRequestBody(containing("application/javascript+module"))
                .withRequestBody(containing("export default"))
        );
    }

    @Test
    void shouldUseCustomModuleFilenameOnBothMetadataAndPartName() throws Exception {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/custom"))
                .willReturn(okJson("""
                    { "success": true, "result": { "id": "custom", "etag": "e1" } }
                    """))
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("custom"))
            .script(Property.ofValue("export default { fetch() {} };"))
            .moduleFilename(Property.ofValue("entry.mjs"))
            .build();

        task.run(runContextFactory.of());

        // The contract being enforced: `metadata.main_module` and the multipart part `name` are sourced
        // from the same `moduleFilename` value. Without this, Cloudflare returns "No such module: ...".
        verify(
            putRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/custom"))
                .withRequestBody(containing("\"main_module\":\"entry.mjs\""))
                .withRequestBody(containing("name=\"entry.mjs\""))
                .withRequestBody(containing("filename=\"entry.mjs\""))
        );
    }

    @Test
    void shouldSerializeBindingsIntoMetadata() throws Exception {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/bound"))
                .willReturn(okJson("""
                    { "success": true, "result": { "id": "bound", "etag": "e1" } }
                    """))
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("bound"))
            .script(Property.ofValue("export default { fetch() {} };"))
            .bindings(
                Property.ofValue(
                    List.of(
                        Map.of("type", "kv_namespace", "name", "MY_KV", "namespace_id", "abc123"),
                        Map.of("type", "plain_text", "name", "API_VERSION", "text", "v1")
                    )
                )
            )
            .tags(Property.ofValue(List.of("prod", "edge")))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals("e1", output.getEtag());

        verify(
            putRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/bound"))
                .withRequestBody(containing("\"bindings\":["))
                .withRequestBody(containing("\"type\":\"kv_namespace\""))
                .withRequestBody(containing("\"name\":\"MY_KV\""))
                .withRequestBody(containing("\"namespace_id\":\"abc123\""))
                .withRequestBody(containing("\"tags\":[\"prod\",\"edge\"]"))
        );
    }

    @Test
    void shouldUseServiceWorkerContentTypeForLegacyFormat() throws Exception {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/legacy"))
                .willReturn(okJson("""
                    { "success": true, "result": { "id": "legacy", "etag": "e1" } }
                    """))
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("legacy"))
            .script(Property.ofValue("addEventListener('fetch', e => e.respondWith(new Response('ok')));"))
            .format(Property.ofValue(Deploy.ScriptFormat.SERVICE_WORKER))
            .build();

        task.run(runContextFactory.of());

        verify(
            putRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/legacy"))
                .withRequestBody(containing("\"body_part\":\"worker.js\""))
                .withRequestBody(containing("name=\"worker.js\""))
                .withRequestBody(containing("filename=\"worker.js\""))
                .withRequestBody(containing("Content-Type: application/javascript; charset=UTF-8"))
        );
    }

    @Test
    void shouldAlignBodyPartAndPartNameForServiceWorkerWithCustomFilename() throws Exception {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/legacy-custom"))
                .willReturn(okJson("""
                    { "success": true, "result": { "id": "legacy-custom", "etag": "e1" } }
                    """))
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("legacy-custom"))
            .script(Property.ofValue("addEventListener('fetch', e => e.respondWith(new Response('ok')));"))
            .format(Property.ofValue(Deploy.ScriptFormat.SERVICE_WORKER))
            .moduleFilename(Property.ofValue("script.js"))
            .build();

        task.run(runContextFactory.of());

        verify(
            putRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts/legacy-custom"))
                .withRequestBody(containing("\"body_part\":\"script.js\""))
                .withRequestBody(containing("name=\"script.js\""))
                .withRequestBody(containing("filename=\"script.js\""))
        );
    }

    @Test
    void shouldFailWhenCloudflareReturnsFailure() {
        stubFor(
            put(urlEqualTo("/accounts/acct-1/workers/scripts/bad"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            { "success": false, "errors": [{ "code": 10021, "message": "Script body parse error" }] }
                            """)
                )
        );

        var task = Deploy.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .scriptName(Property.ofValue("bad"))
            .script(Property.ofValue("not valid js"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("[10021]"), "missing code in: " + message);
        assertTrue(message.contains("Script body parse error"), "missing detail in: " + message);
        assertTrue(message.contains("HTTP 400"), "missing status in: " + message);
    }

}
