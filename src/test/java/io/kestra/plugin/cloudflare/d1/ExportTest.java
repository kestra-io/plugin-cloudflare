package io.kestra.plugin.cloudflare.d1;

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
class ExportTest {

    private static final String DB_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String EXPORT_PATH = "/accounts/test-account/d1/database/" + DB_UUID + "/export";
    private static final String SIGNED_URL = "http://localhost:28282/download/dump.sql";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldExportAndStoreDump() throws Exception {
        stubFor(
            post(urlEqualTo(EXPORT_PATH))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "complete",
                        "at_bookmark": "bookmark-abc",
                        "messages": ["Uploaded part 1", "Finished uploading"],
                        "result": {
                          "filename": "dump.sql",
                          "signed_url": "%s"
                        }
                      }
                    }
                    """.formatted(SIGNED_URL)))
        );

        stubFor(
            get(urlEqualTo("/download/dump.sql"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);")
                )
        );

        var task = Export.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertNotNull(output.getUri());
        assertEquals("dump.sql", output.getFilename());

        verify(
            postRequestedFor(urlEqualTo(EXPORT_PATH))
                .withRequestBody(matchingJsonPath("$.output_format", equalTo("polling")))
        );
        verify(getRequestedFor(urlEqualTo("/download/dump.sql")));
    }

    @Test
    void shouldPollUntilReady() throws Exception {
        stubFor(
            post(urlEqualTo(EXPORT_PATH))
                .withRequestBody(matchingJsonPath("$.output_format", equalTo("polling")))
                .withRequestBody(notMatching(".*current_bookmark.*"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-xyz"
                      }
                    }
                    """))
        );

        stubFor(
            post(urlEqualTo(EXPORT_PATH))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-xyz")))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "complete",
                        "at_bookmark": "bookmark-xyz",
                        "messages": ["Uploaded part 1", "Finished uploading"],
                        "result": {
                          "filename": "dump.sql",
                          "signed_url": "%s"
                        }
                      }
                    }
                    """.formatted(SIGNED_URL)))
        );

        stubFor(
            get(urlEqualTo("/download/dump.sql"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("CREATE TABLE events (id INTEGER PRIMARY KEY);")
                )
        );

        long before = System.currentTimeMillis();

        var task = Export.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContextFactory.of());

        long elapsed = System.currentTimeMillis() - before;

        assertNotNull(output);
        assertNotNull(output.getUri());
        assertEquals("dump.sql", output.getFilename());

        verify(
            postRequestedFor(urlEqualTo(EXPORT_PATH))
                .withRequestBody(matchingJsonPath("$.output_format", equalTo("polling")))
        );
        verify(
            postRequestedFor(urlEqualTo(EXPORT_PATH))
                .withRequestBody(matchingJsonPath("$.current_bookmark", equalTo("bookmark-xyz")))
        );

        List<LoggedRequest> postRequests = findAll(postRequestedFor(urlEqualTo(EXPORT_PATH)));
        assertEquals(2, postRequests.size(), "Expected exactly 2 POST requests to the export endpoint");

        assertTrue(elapsed >= 250, "Expected at least 250ms elapsed due to backoff sleep, got " + elapsed + "ms");
    }

    @Test
    void shouldThrowOnErrorStatus() throws Exception {
        stubFor(
            post(urlEqualTo(EXPORT_PATH))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "error",
                        "error": "export quota exceeded",
                        "messages": ["quota limit reached"]
                      }
                    }
                    """))
        );

        var task = Export.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("export quota exceeded"), "Expected error message in exception: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("quota limit reached"), "Expected messages in exception: " + ex.getMessage());
    }

    @Test
    void shouldThrowWhenDeadlineExceeded() {
        stubFor(
            post(urlEqualTo(EXPORT_PATH))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": {
                        "status": "active",
                        "at_bookmark": "bookmark-xyz"
                      }
                    }
                    """))
        );

        var task = Export.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .maxDuration(Property.ofValue(Duration.ofMillis(600)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains("did not complete"), "Expected timeout message in exception: " + ex.getMessage());
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            post(urlEqualTo(EXPORT_PATH))
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

        var task = Export.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue(DB_UUID))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "missing error code in: " + message);
        assertTrue(message.contains("Database not found"), "missing error message in: " + message);
    }
}
