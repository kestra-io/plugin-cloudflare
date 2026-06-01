package io.kestra.plugin.cloudflare.d1;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class ImportTest {

    private static final String DB_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String IMPORT_PATH = "/accounts/test-account/d1/database/" + DB_UUID + "/import";
    private static final String UPLOAD_PATH = "/upload/test-file.sql";
    private static final String UPLOAD_URL = "http://localhost:28282" + UPLOAD_PATH;
    private static final String FILENAME = "test-file.sql";

    private static final String COMPLETE_RESPONSE = """
        {
          "success": true,
          "errors": [],
          "messages": [],
          "result": {
            "status": "complete",
            "at_bookmark": "bookmark-final",
            "messages": [],
            "result": {
              "final_bookmark": "bookmark-final",
              "num_queries": 2,
              "meta": {
                "duration": 5.5,
                "last_row_id": 10,
                "changes": 2,
                "rows_read": 0,
                "rows_written": 2
              }
            }
          }
        }
        """;

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldImportFromInlineSql() throws Exception {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "upload_url": "%s",
                        "filename": "%s"
                      }
                    }
                    """.formatted(UPLOAD_URL, FILENAME)))
        );

        stubFor(put(urlEqualTo(UPLOAD_PATH)).willReturn(aResponse().withStatus(200)));

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
                .willReturn(okJson(COMPLETE_RESPONSE))
        );

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("CREATE TABLE t (id INTEGER); INSERT INTO t VALUES (1);"))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(FILENAME, output.getFilename());
        assertNotNull(output.getEtag());
        assertTrue(output.getEtag().matches("[0-9a-f]{32}"), "etag should be lowercase hex MD5: " + output.getEtag());
        assertEquals("bookmark-final", output.getFinalBookmark());
        assertEquals(2, output.getNumQueries());
        assertNotNull(output.getMeta());
        assertEquals(2L, output.getMeta().rowsWritten());

        verify(
            postRequestedFor(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .withRequestBody(matchingJsonPath("$.etag"))
        );
        verify(
            postRequestedFor(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
        );
        verify(putRequestedFor(urlEqualTo(UPLOAD_PATH)));
    }

    @Test
    void shouldImportFromStorageUri() throws Exception {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "upload_url": "%s",
                        "filename": "%s"
                      }
                    }
                    """.formatted(UPLOAD_URL, FILENAME)))
        );

        stubFor(put(urlEqualTo(UPLOAD_PATH)).willReturn(aResponse().withStatus(200)));

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
                .willReturn(okJson(COMPLETE_RESPONSE))
        );

        var sqlContent = "CREATE TABLE from_storage (id INTEGER PRIMARY KEY);";
        var runContext = runContextFactory.of();
        var storageUri = runContext.storage().putFile(
            new ByteArrayInputStream(sqlContent.getBytes(StandardCharsets.UTF_8)),
            "from-storage.sql"
        );

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .from(Property.ofValue(storageUri.toString()))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertEquals(FILENAME, output.getFilename());
        verify(putRequestedFor(urlEqualTo(UPLOAD_PATH)));
    }

    @Test
    void shouldPollUntilComplete() throws Exception {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "upload_url": "%s",
                        "filename": "%s"
                      }
                    }
                    """.formatted(UPLOAD_URL, FILENAME)))
        );

        stubFor(put(urlEqualTo(UPLOAD_PATH)).willReturn(aResponse().withStatus(200)));

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-1",
                        "messages": []
                      }
                    }
                    """))
        );

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("poll")))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-1")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-2",
                        "messages": []
                      }
                    }
                    """))
        );

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("poll")))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-2")))
                .willReturn(okJson(COMPLETE_RESPONSE))
        );

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("INSERT INTO t VALUES (42);"))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("bookmark-final", output.getFinalBookmark());

        List<LoggedRequest> pollRequests = findAll(
            postRequestedFor(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("poll")))
        );
        assertTrue(pollRequests.size() >= 2, "Expected at least 2 poll requests, got: " + pollRequests.size());

        verify(
            postRequestedFor(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-1")))
        );
        verify(
            postRequestedFor(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-2")))
        );
    }

    @Test
    void shouldThrowOnErrorStatus() throws Exception {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "upload_url": "%s",
                        "filename": "%s"
                      }
                    }
                    """.formatted(UPLOAD_URL, FILENAME)))
        );

        stubFor(put(urlEqualTo(UPLOAD_PATH)).willReturn(aResponse().withStatus(200)));

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "error",
                        "error": "schema mismatch",
                        "messages": ["table not found", "import aborted"]
                      }
                    }
                    """))
        );

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("INSERT INTO missing_table VALUES (1);"))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("schema mismatch"), "Expected error in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("table not found"), "Expected messages in message: " + ex.getMessage());
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(404)
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

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT 1;"))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "Expected error code in: " + message);
        assertTrue(message.contains("Database not found"), "Expected error message in: " + message);
    }

    @Test
    void shouldThrowWhenDeadlineExceeded() throws Exception {
        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("init")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "upload_url": "%s",
                        "filename": "%s"
                      }
                    }
                    """.formatted(UPLOAD_URL, FILENAME)))
        );

        stubFor(put(urlEqualTo(UPLOAD_PATH)).willReturn(aResponse().withStatus(200)));

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("ingest")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-stuck",
                        "messages": []
                      }
                    }
                    """))
        );

        stubFor(
            post(urlEqualTo(IMPORT_PATH))
                .withRequestBody(matchingJsonPath("$.action", equalTo("poll")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-stuck",
                        "messages": []
                      }
                    }
                    """))
        );

        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT 1;"))
            .maxDuration(Property.ofValue(Duration.ofMillis(600)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("did not complete"), "Expected timeout message: " + ex.getMessage());
    }

    @Test
    void shouldThrowWhenBothSqlAndFromSet() {
        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .sql(Property.ofValue("SELECT 1;"))
            .from(Property.ofValue("kestra:///some/file.sql"))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("not both"), "Expected mutual exclusion message: " + ex.getMessage());
    }

    @Test
    void shouldThrowWhenNeitherSqlNorFromSet() {
        var task = Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("must be set"), "Expected required field message: " + ex.getMessage());
    }
}
