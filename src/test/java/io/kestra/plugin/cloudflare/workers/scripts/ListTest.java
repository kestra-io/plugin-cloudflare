package io.kestra.plugin.cloudflare.workers.scripts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 28204)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class ListTest {

    private static final String BASE_URL = "http://localhost:28204";

    @Inject
    RunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    @SuppressWarnings("unchecked")
    void shouldListScriptsAcrossTwoPages() throws Exception {
        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        { "id": "alpha", "etag": "e1" },
                        { "id": "beta",  "etag": "e2" }
                      ],
                      "result_info": { "cursor": "cur-2" }
                    }
                    """))
        );

        stubFor(
            get(urlEqualTo("/accounts/acct-1/workers/scripts?cursor=cur-2"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        { "id": "gamma", "etag": "e3" }
                      ],
                      "result_info": { "cursor": "" }
                    }
                    """))
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("acct-1"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(3L, output.getSize());
        assertNotNull(output.getRows());
        assertEquals(3, output.getRows().size());
        assertEquals("alpha", ((Map<String, Object>) output.getRows().get(0)).get("id"));
        assertEquals("beta", ((Map<String, Object>) output.getRows().get(1)).get("id"));
        assertEquals("gamma", ((Map<String, Object>) output.getRows().get(2)).get("id"));
        assertNull(output.getUri());

        verify(getRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts")));
        verify(getRequestedFor(urlEqualTo("/accounts/acct-1/workers/scripts?cursor=cur-2")));
    }

    @Test
    void shouldReturnEmptyListWhenAccountHasNoScripts() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/empty/workers/scripts"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "result": [],
                      "result_info": { "cursor": null }
                    }
                    """))
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("empty"))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(0L, output.getSize());
        assertNotNull(output.getRows());
        assertTrue(output.getRows().isEmpty());
    }

    @Test
    void shouldFailWhenServerReturnsError() {
        stubFor(
            get(urlPathEqualTo("/accounts/bad/workers/scripts"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            { "success": false, "errors": [{ "code": 10000, "message": "Authentication error" }] }
                            """)
                )
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("bad"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("[10000]"), "missing code in: " + message);
        assertTrue(message.contains("Authentication error"), "missing detail in: " + message);
        assertTrue(message.contains("HTTP 403"), "missing status in: " + message);
    }

    @Test
    void shouldReturnFirstScriptOnFetchOne() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/one/workers/scripts"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "result": [
                        { "id": "first", "etag": "e1" },
                        { "id": "second", "etag": "e2" }
                      ],
                      "result_info": { "cursor": "" }
                    }
                    """))
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("one"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output.getRow());
        assertEquals("first", output.getRow().get("id"));
        assertNull(output.getRows());
    }

    @Test
    void shouldReturnOnlySizeWhenFetchTypeIsNone() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/none/workers/scripts"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "result": [
                        { "id": "x", "etag": "e1" },
                        { "id": "y", "etag": "e2" }
                      ],
                      "result_info": { "cursor": "" }
                    }
                    """))
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("none"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(2L, output.getSize());
        assertNull(output.getRows());
        assertNull(output.getRow());
        assertNull(output.getUri());
    }

    @Test
    void shouldStoreScriptsAsIonFile() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/store/workers/scripts"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "result": [
                        { "id": "a", "etag": "e1" },
                        { "id": "b", "etag": "e2" }
                      ],
                      "result_info": { "cursor": "" }
                    }
                    """))
        );

        var task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("store"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertEquals(2L, output.getSize());
        assertNotNull(output.getUri());
        assertNull(output.getRows());

        try (
            var reader = new BufferedReader(
                new InputStreamReader(
                    storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()), StandardCharsets.UTF_8
                )
            )
        ) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertNotNull(rows);
            assertEquals(2, rows.size());
        }
    }
}
