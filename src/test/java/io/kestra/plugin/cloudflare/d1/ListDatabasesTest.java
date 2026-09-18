package io.kestra.plugin.cloudflare.d1;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28282)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class ListDatabasesTest {

    private static final String DATABASE_PATH = "/accounts/test-account/d1/database";

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

    @Test
    void shouldKeepLifecycleStateOutOfTheSerializedTask() throws Exception {
        var task = killableTask();
        task.kill();

        var serialized = JacksonMapper.ofJson().writeValueAsString(task);

        assertFalse(serialized.contains("cancelLatch"), "Lifecycle state leaked into the serialized task: " + serialized);
        assertFalse(task.toString().contains("cancelLatch"), "Lifecycle state leaked into toString(): " + task);
    }

    @Test
    void shouldTreatRepeatedKillsAsIdempotent() {
        var task = killableTask();

        task.kill();
        task.kill();

        var ex = assertThrows(KilledException.class, () -> task.run(runContextFactory.of()));
        assertEquals("D1 database listing was cancelled", ex.getMessage());
        verify(exactly(0), getRequestedFor(urlPathEqualTo(DATABASE_PATH)));
    }

    @Test
    void shouldNotCancelOnStop() {
        // stop() is the graceful-shutdown drain signal and does not set killedState, so ending the task
        // there would be reported as a genuine failure rather than resubmitted. It must stay a no-op.
        stubFor(
            get(urlPathEqualTo(DATABASE_PATH))
                .willReturn(okJson("""
                    {
                      "success": true,
                      "errors": [],
                      "messages": [],
                      "result": [
                        {"uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "name": "prod-db", "version": "alpha", "num_tables": 5}
                      ],
                      "result_info": {"page": 1, "per_page": 100, "count": 1, "total_count": 1}
                    }
                    """))
        );

        var task = killableTask();
        task.stop();

        var output = assertDoesNotThrow(() -> task.run(runContextFactory.of()));
        assertEquals(1, output.getTotal());
    }

    @Test
    void shouldFailWithoutCallingCloudflareWhenKilledBeforeRun() {
        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .build();

        task.kill();

        var ex = assertThrows(KilledException.class, () -> task.run(runContextFactory.of()));
        assertEquals("D1 database listing was cancelled", ex.getMessage());
        verify(exactly(0), getRequestedFor(urlPathEqualTo(DATABASE_PATH)));
    }

    @Test
    void shouldStopPaginatingWhenKilled() {
        // Every page comes back full with the total far ahead, so only a kill ends the loop.
        stubFor(
            get(urlPathEqualTo(DATABASE_PATH))
                .willReturn(
                    okJson("""
                        {
                          "success": true,
                          "errors": [],
                          "messages": [],
                          "result": [
                            {"uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "name": "db-1", "version": "alpha", "num_tables": 1},
                            {"uuid": "11111111-2222-3333-4444-555555555555", "name": "db-2", "version": "alpha", "num_tables": 1}
                          ],
                          "result_info": {"page": 1, "per_page": 2, "count": 2, "total_count": 1000000}
                        }
                        """).withFixedDelay(50)
                )
        );

        var task = ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .perPage(Property.ofValue(2))
            .build();

        var runContext = runContextFactory.of();
        var executor = Executors.newSingleThreadExecutor();

        try {
            var future = executor.submit(() -> task.run(runContext));

            awaitRequests(2);
            task.kill();

            var ex = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(KilledException.class, ex.getCause());
            assertEquals("D1 database listing was cancelled", ex.getCause().getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static ListDatabases killableTask() {
        return ListDatabases.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28282"))
            .accountId(Property.ofValue("test-account"))
            .build();
    }

    private static void awaitRequests(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (findAll(getRequestedFor(urlPathEqualTo(DATABASE_PATH))).size() < expected) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + expected + " requests to " + DATABASE_PATH);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for requests to " + DATABASE_PATH);
            }
        }
    }
}
