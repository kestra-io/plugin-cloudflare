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
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class ListDatabasesTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldListDatabases() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/test-account/d1/database"))
                .willReturn(
                    okJson("""
                        {
                          "success": true,
                          "errors": [],
                          "messages": [],
                          "result": [
                            {
                              "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                              "name": "prod-db",
                              "version": "alpha",
                              "num_tables": 5
                            },
                            {
                              "uuid": "11111111-2222-3333-4444-555555555555",
                              "name": "staging-db",
                              "version": "alpha",
                              "num_tables": 3
                            }
                          ],
                          "result_info": {
                            "page": 1,
                            "per_page": 100,
                            "count": 2,
                            "total_count": 2
                          }
                        }
                        """)
                )
        );

        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(2, output.getTotal());
        assertEquals(2, output.getDatabases().size());
        assertEquals("prod-db", output.getDatabases().getFirst().name());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", output.getDatabases().getFirst().uuid());
        assertThat(output.getDatabases().getFirst().numTables()).isEqualTo(5);
    }

    @Test
    void shouldListDatabasesWithNameFilter() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/test-account/d1/database"))
                .withQueryParam("name", equalTo("prod-"))
                .willReturn(
                    okJson("""
                        {
                          "success": true,
                          "errors": [],
                          "messages": [],
                          "result": [
                            {
                              "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                              "name": "prod-db",
                              "version": "alpha",
                              "num_tables": 5
                            }
                          ],
                          "result_info": {
                            "page": 1,
                            "per_page": 100,
                            "count": 1,
                            "total_count": 1
                          }
                        }
                        """)
                )
        );

        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .nameFilter(Property.ofValue("prod-"))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(1, output.getTotal());
        assertEquals("prod-db", output.getDatabases().getFirst().name());
    }

    @Test
    void shouldPaginateAcrossPages() throws Exception {
        stubFor(
            get(urlPathEqualTo("/accounts/test-account/d1/database"))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("per_page", equalTo("2"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        {"uuid": "aaaa", "name": "db-1", "version": "alpha", "num_tables": 1},
                        {"uuid": "bbbb", "name": "db-2", "version": "alpha", "num_tables": 1}
                      ],
                      "result_info": {"page": 1, "per_page": 2, "count": 2, "total_count": 3}
                    }
                    """))
        );

        stubFor(
            get(urlPathEqualTo("/accounts/test-account/d1/database"))
                .withQueryParam("page", equalTo("2"))
                .withQueryParam("per_page", equalTo("2"))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        {"uuid": "cccc", "name": "db-3", "version": "alpha", "num_tables": 1}
                      ],
                      "result_info": {"page": 2, "per_page": 2, "count": 1, "total_count": 3}
                    }
                    """))
        );

        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .perPage(Property.ofValue(2))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(3, output.getTotal());
        assertEquals("db-1", output.getDatabases().get(0).name());
        assertEquals("db-2", output.getDatabases().get(1).name());
        assertEquals("db-3", output.getDatabases().get(2).name());

        verify(
            getRequestedFor(urlPathEqualTo("/accounts/test-account/d1/database"))
                .withQueryParam("page", equalTo("1"))
        );
        verify(
            getRequestedFor(urlPathEqualTo("/accounts/test-account/d1/database"))
                .withQueryParam("page", equalTo("2"))
        );
    }

    @Test
    void shouldThrowOnCloudflareError() {
        stubFor(
            get(urlPathEqualTo("/accounts/test-account/d1/database"))
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

        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .build();

        var ex = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));
        var message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.contains("7003"), "missing error code in: " + message);
        assertTrue(message.contains("Database not found"), "missing error message in: " + message);
    }
}
