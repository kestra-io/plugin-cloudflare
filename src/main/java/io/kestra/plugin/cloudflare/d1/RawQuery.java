package io.kestra.plugin.cloudflare.d1;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    title = "Run a raw SQL statement against a Cloudflare D1 database",
    description = """
        Executes a SQL statement using the /raw endpoint, which returns column names and row values
        as separate arrays instead of per-row objects. Useful for bulk export scenarios where
        column-oriented layout is preferred.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a raw query and get column/row arrays",
            full = true,
            code = """
                id: d1_raw_query
                namespace: company.team

                tasks:
                  - id: raw
                    type: io.kestra.plugin.cloudflare.d1.RawQuery
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    sql: "SELECT id, name FROM users LIMIT 100"
                """
        )
    }
)
public class RawQuery extends AbstractCloudflareTask implements RunnableTask<RawQuery.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to query")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Schema(title = "SQL statement", description = "SQL statement to execute; use ? as positional placeholders")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Schema(
        title = "Query parameters",
        description = "Ordered list of positional parameter values for the ? placeholders in the SQL statement"
    )
    @PluginProperty(group = "main")
    private Property<List<Object>> params;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rDatabaseId = runContext.render(databaseId).as(String.class).orElseThrow();
        var rSql = runContext.render(sql).as(String.class).orElseThrow();
        var rParams = runContext.render(params).asList(Object.class);

        logger.info("Executing D1 raw query on database '{}'", rDatabaseId);
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
                        + "/d1/database/" + encodePathSegment(rDatabaseId) + "/raw"
                )
            )
            .body(HttpRequest.JsonRequestBody.builder().content(requestBody).build());

        HttpResponse<CloudflareEnvelope<List<RawResult>>> response = this.request(
            runContext, requestBuilder, new TypeReference<CloudflareEnvelope<List<RawResult>>>() {
            }
        );

        var body = response.getBody();
        if (body == null || !body.success()) {
            throw new IllegalStateException("D1 raw query failed: " + body);
        }

        var results = body.result();
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("D1 returned no result sets");
        }

        var first = results.getFirst();
        var columns = first.results() != null ? first.results().columns() : List.<String> of();
        var rows = first.results() != null ? first.results().rows() : List.<List<Object>> of();

        logger.info("Raw query returned {} columns, {} rows", columns.size(), rows.size());

        return Output.builder()
            .columns(columns)
            .rows(rows)
            .size(rows.size())
            .meta(first.meta())
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawResult(RawResultSet results, Query.QueryMeta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawResultSet(List<String> columns, List<List<Object>> rows) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Column names", description = "Ordered list of column names from the result set")
        private final List<String> columns;

        @Schema(title = "Rows", description = "Result rows as ordered arrays of values, aligned to the column list")
        private final List<List<Object>> rows;

        @Schema(title = "Row count", description = "Number of rows returned")
        private final int size;

        @Schema(title = "Query metadata", description = "Execution metadata returned by D1")
        private final Query.QueryMeta meta;
    }
}
