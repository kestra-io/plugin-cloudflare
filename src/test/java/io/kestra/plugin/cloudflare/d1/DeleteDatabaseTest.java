package io.kestra.plugin.cloudflare.d1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class DeleteDatabaseTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDeleteDatabase() throws Exception {
        stubFor(
            delete(urlEqualTo("/accounts/test-account/d1/database/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
                .willReturn(
                    okJson("""
                        {
                          "success": true,
                          "errors": [],
                          "messages": [],
                          "result": {}
                        }
                        """)
                )
        );

        var task = DeleteDatabase.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", output.getDatabaseId());

        verify(deleteRequestedFor(urlEqualTo("/accounts/test-account/d1/database/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")));
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            delete(urlEqualTo("/accounts/test-account/d1/database/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
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

        var task = DeleteDatabase.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "missing error code in: " + message);
        assertTrue(message.contains("Database not found"), "missing error message in: " + message);
    }
}
