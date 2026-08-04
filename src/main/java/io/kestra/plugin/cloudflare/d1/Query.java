package io.kestra.plugin.cloudflare.d1;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.cloudflare.AbstractCloudflareTask;
import io.kestra.plugin.cloudflare.models.CloudflareEnvelope;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Run a SQL statement against a Cloudflare D1 database",
    description = """
        Executes a parameterized SQL statement against a D1 database. Supports four fetch modes:
        FETCH_ONE returns the first row as a map, FETCH returns all rows as a list of maps,
        STORE writes all rows to Kestra internal storage as a newline-delimited ION file, and
        NONE returns only the query metadata (rows written, duration).
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a SELECT and store results in Kestra storage",
            full = true,
            code = """
                id: d1_query_store
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.cloudflare.d1.Query
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    sql: "SELECT id, name FROM users WHERE active = ?"
                    params:
                      - true
                    fetchType: STORE
                """
        ),
        @Example(
            title = "Insert a row and capture metadata",
            full = true,
            code = """
                id: d1_query_insert
                namespace: company.team

                tasks:
                  - id: insert
                    type: io.kestra.plugin.cloudflare.d1.Query
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    sql: "INSERT INTO events (name, ts) VALUES (?, ?)"
                    params:
                      - "deploy"
                      - "2024-01-01T00:00:00Z"
                    fetchType: NONE
                """
        )
    }
)
public class Query extends AbstractCloudflareTask implements RunnableTask<Query.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to query")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Schema(title = "SQL statement", description = "Parameterized SQL statement to execute; use ? as positional placeholders")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Schema(
        title = "Query parameters",
        description = "Ordered list of positional parameter values for the ? placeholders in the SQL statement"
    )
    @PluginProperty(group = "main")
    private Property<List<Object>> params;

    @Schema(
        title = "Fetch type",
        description = """
            Controls how result rows are returned:
            FETCH_ONE returns the first row as a map,
            FETCH returns all rows as a list of maps,
            STORE writes all rows to Kestra internal storage and returns a URI,
            NONE returns only query execution metadata.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    public enum FetchType {
        FETCH_ONE,
        FETCH,
        STORE,
        NONE
    }

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rDatabaseId = runContext.render(databaseId).as(String.class).orElseThrow();
        var rSql = runContext.render(sql).as(String.class).orElseThrow();
        var rParams = runContext.render(params).asList(Object.class);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);

        logger.info("Executing D1 query on database '{}'", rDatabaseId);
        logger.debug("SQL: {}", rSql);

        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("sql", rSql);
        if (!rParams.isEmpty()) {
            requestBody.put("params", rParams);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(
                URI.create(
                    rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
                        + "/d1/database/" + encodePathSegment(rDatabaseId) + "/query"
                )
            )
            .body(HttpRequest.JsonRequestBody.builder().content(requestBody).build());

        HttpResponse<CloudflareEnvelope<List<QueryResult>>> response = this.request(
            runContext, requestBuilder, new TypeReference<CloudflareEnvelope<List<QueryResult>>>() {
            }
        );

        var body = response.getBody();
        if (body == null || !body.success()) {
            throw new IllegalStateException("D1 query failed: " + body);
        }

        var results = body.result();
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("D1 returned no result sets");
        }

        var firstResult = results.getFirst();
        var rows = firstResult.results() != null ? firstResult.results() : List.<Map<String, Object>> of();
        var meta = firstResult.meta();

        logger.info("Query returned {} rows in {}ms", rows.size(), meta != null ? meta.duration() : "?");

        return buildOutput(runContext, rows, meta, rFetchType);
    }

    private Output buildOutput(RunContext runContext, List<Map<String, Object>> rows, QueryMeta meta, FetchType rFetchType) {
        return switch (rFetchType) {
            case FETCH_ONE -> Output.builder()
                .row(rows.isEmpty() ? null : rows.getFirst())
                .size(rows.isEmpty() ? 0 : 1)
                .meta(meta)
                .build();
            case FETCH -> Output.builder()
                .rows(rows)
                .size(rows.size())
                .meta(meta)
                .build();
            case STORE -> Output.builder()
                .uri(store(runContext, rows))
                .size(rows.size())
                .meta(meta)
                .build();
            case NONE -> Output.builder()
                .size(rows.size())
                .meta(meta)
                .build();
        };
    }

    private URI store(RunContext runContext, List<Map<String, Object>> rows) {
        try {
            var tempFile = runContext.workingDir().createTempFile(".ion");
            try (var os = java.nio.file.Files.newOutputStream(tempFile)) {
                for (var row : rows) {
                    FileSerde.write(os, row);
                }
            }
            return runContext.storage().putFile(tempFile.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to store D1 query results", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryResult(List<Map<String, Object>> results, QueryMeta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryMeta(
        Double duration,
        @JsonProperty("last_row_id") Long lastRowId,
        Boolean changed,
        Integer changes,
        @JsonProperty("rows_read") Long rowsRead,
        @JsonProperty("rows_written") Long rowsWritten) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Single row", description = "First result row as a map (FETCH_ONE mode only)")
        private final Map<String, Object> row;

        @Schema(title = "All rows", description = "All result rows as a list of maps (FETCH mode only)")
        private final List<Map<String, Object>> rows;

        @Schema(title = "Storage URI", description = "Internal storage URI of the newline-delimited ION file (STORE mode only)")
        private final URI uri;

        @Schema(title = "Row count", description = "Number of rows in the result set")
        private final int size;

        @Schema(title = "Query metadata", description = "Execution metadata returned by D1 (duration, rows read/written, etc.)")
        private final QueryMeta meta;
    }
}
