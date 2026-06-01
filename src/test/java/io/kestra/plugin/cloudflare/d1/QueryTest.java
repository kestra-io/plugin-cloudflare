package io.kestra.plugin.cloudflare.d1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class QueryTest {

    private static final String DB_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String QUERY_PATH = "/accounts/test-account/d1/database/" + DB_UUID + "/query";

    private static final String QUERY_RESPONSE = """
        {
          "success": true,
          "errors": [],
          "messages": [],
          "result": [
            {
              "results": [
                {"id": 1, "name": "Alice"},
                {"id": 2, "name": "Bob"}
              ],
              "meta": {
                "duration": 12.5,
                "last_row_id": 2,
                "changed": false,
                "changes": 0,
                "rows_read": 2,
                "rows_written": 0
              }
            }
          ]
        }
        """;

    @Inject
    RunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    void shouldFetchAllRows() throws Exception {
        stubFor(post(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_RESPONSE)));

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT id, name FROM users"))
            .fetchType(Property.ofValue(Query.FetchType.FETCH))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(2, output.getSize());
        assertNotNull(output.getRows());
        assertEquals(2, output.getRows().size());
        assertEquals(1, output.getRows().getFirst().get("id"));
        assertEquals("Alice", output.getRows().getFirst().get("name"));
        assertNotNull(output.getMeta());
        assertEquals(12.5, output.getMeta().duration());
        assertThat(output.getMeta().lastRowId()).isEqualTo(2L);
        assertThat(output.getMeta().rowsRead()).isEqualTo(2L);
        assertThat(output.getMeta().rowsWritten()).isEqualTo(0L);
    }

    @Test
    void shouldFetchOneRow() throws Exception {
        stubFor(post(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_RESPONSE)));

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT id, name FROM users LIMIT 1"))
            .fetchType(Property.ofValue(Query.FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(1, output.getSize());
        assertNotNull(output.getRow());
        assertEquals(1, output.getRow().get("id"));
        assertEquals("Alice", output.getRow().get("name"));
        assertNull(output.getRows());
        assertNull(output.getUri());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStoreResults() throws Exception {
        stubFor(post(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_RESPONSE)));

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT id, name FROM users"))
            .fetchType(Property.ofValue(Query.FetchType.STORE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertNotNull(output);
        assertEquals(2, output.getSize());
        assertNotNull(output.getUri());
        assertNull(output.getRows());
        assertNull(output.getRow());

        try (
            var reader = new BufferedReader(
                new InputStreamReader(
                    storageInterface.get(TenantService.MAIN_TENANT, null, output.getUri()),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertNotNull(rows);
            assertEquals(2, rows.size());
            assertThat(((Map<String, Object>) rows.get(0)).get("name")).isEqualTo("Alice");
            assertThat(((Map<String, Object>) rows.get(1)).get("name")).isEqualTo("Bob");
        }
    }

    @Test
    void shouldReturnNoneMode() throws Exception {
        stubFor(post(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_RESPONSE)));

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("DELETE FROM events WHERE ts < ?"))
            .params(Property.ofValue(List.<Object> of("2023-01-01")))
            .fetchType(Property.ofValue(Query.FetchType.NONE))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertNull(output.getRows());
        assertNull(output.getRow());
        assertNull(output.getUri());
        assertNotNull(output.getMeta());
    }

    @Test
    void shouldSendParamsInRequestBody() throws Exception {
        stubFor(post(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_RESPONSE)));

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT * FROM users WHERE active = ?"))
            .params(Property.ofValue(List.<Object> of(true)))
            .fetchType(Property.ofValue(Query.FetchType.FETCH))
            .build();

        task.run(runContextFactory.of());

        verify(
            postRequestedFor(urlEqualTo(QUERY_PATH))
                .withRequestBody(matchingJsonPath("$.sql", equalTo("SELECT * FROM users WHERE active = ?")))
                .withRequestBody(matchingJsonPath("$.params[0]", equalTo("true")))
        );
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            post(urlEqualTo(QUERY_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "success": false,
                              "errors": [{"code": 7003, "message": "Database not found"}],
                              "messages": [],
                              "result": null
                            }
                            """)
                )
        );

        var task = Query.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT 1"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "missing error code in: " + message);
        assertTrue(message.contains("Database not found"), "missing error message in: " + message);
    }
}
