package io.kestra.plugin.cloudflare.d1;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class RawQueryTest {

    private static final String DB_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String RAW_PATH = "/accounts/test-account/d1/database/" + DB_UUID + "/raw";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldReturnColumnsAndRows() throws Exception {
        stubFor(
            post(urlEqualTo(RAW_PATH))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        {
                          "results": {
                            "columns": ["id", "name"],
                            "rows": [
                              [1, "Alice"],
                              [2, "Bob"]
                            ]
                          },
                          "meta": {
                            "duration": 8.2,
                            "last_row_id": 2,
                            "rows_read": 2,
                            "rows_written": 0
                          }
                        }
                      ]
                    }
                    """))
        );

        var task = RawQuery.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT id, name FROM users"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(2, output.getSize());
        assertEquals(List.of("id", "name"), output.getColumns());
        assertEquals(2, output.getRows().size());
        assertEquals(1, output.getRows().getFirst().getFirst());
        assertEquals("Alice", output.getRows().getFirst().get(1));
        assertNotNull(output.getMeta());
        assertEquals(8.2, output.getMeta().duration());
        assertThat(output.getMeta().lastRowId()).isEqualTo(2L);
        assertThat(output.getMeta().rowsRead()).isEqualTo(2L);
        assertThat(output.getMeta().rowsWritten()).isEqualTo(0L);

        verify(
            postRequestedFor(urlEqualTo(RAW_PATH))
                .withRequestBody(matchingJsonPath("$.sql", equalTo("SELECT id, name FROM users")))
        );
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            post(urlEqualTo(RAW_PATH))
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

        var task = RawQuery.builder()
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
