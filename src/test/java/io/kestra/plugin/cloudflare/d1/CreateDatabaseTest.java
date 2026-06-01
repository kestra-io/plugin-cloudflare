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
class CreateDatabaseTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldCreateDatabase() throws Exception {
        stubFor(
            post(urlEqualTo("/accounts/test-account/d1/database"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "success": true,
                              "errors": [],
                              "messages": [],
                              "result": {
                                "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                "name": "my-database",
                                "version": "alpha"
                              }
                            }
                            """)
                )
        );

        var task = CreateDatabase.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .name(Property.ofValue("my-database"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", output.getDatabaseId());
        assertEquals("my-database", output.getName());
        assertEquals("alpha", output.getVersion());

        verify(
            postRequestedFor(urlEqualTo("/accounts/test-account/d1/database"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("my-database")))
        );
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            post(urlEqualTo("/accounts/test-account/d1/database"))
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

        var task = CreateDatabase.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .name(Property.ofValue("my-database"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "missing error code in: " + message);
        assertTrue(message.contains("Database not found"), "missing error message in: " + message);
    }
}
