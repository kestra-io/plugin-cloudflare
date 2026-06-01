package io.kestra.plugin.cloudflare.d1;

import java.net.URI;

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
    title = "Get a Cloudflare D1 database",
    description = "Retrieves metadata for a specific D1 database by its UUID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a D1 database by ID",
            full = true,
            code = """
                id: get_d1_database
                namespace: company.team

                tasks:
                  - id: get_db
                    type: io.kestra.plugin.cloudflare.d1.GetDatabase
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                """
        )
    }
)
public class GetDatabase extends AbstractCloudflareTask implements RunnableTask<GetDatabase.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to retrieve")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rDatabaseId = runContext.render(databaseId).as(String.class).orElseThrow();

        logger.info("Fetching D1 database '{}' from account '{}'", rDatabaseId, rAccountId);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(
                URI.create(
                    rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/d1/database/" + encodePathSegment(rDatabaseId)
                )
            );

        HttpResponse<CloudflareEnvelope<DatabaseResponse>> response = this.request(
            runContext, requestBuilder, new TypeReference<CloudflareEnvelope<DatabaseResponse>>() {
            }
        );

        var body = response.getBody();
        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to fetch D1 database: " + body);
        }

        var result = body.result();
        return Output.builder()
            .databaseId(result.uuid())
            .name(result.name())
            .version(result.version())
            .numTables(result.numTables())
            .fileSize(result.fileSize())
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatabaseResponse(
        String uuid,
        String name,
        String version,
        @JsonProperty("num_tables") Integer numTables,
        @JsonProperty("file_size") Long fileSize) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Database ID", description = "UUID of the database")
        private final String databaseId;

        @Schema(title = "Database name", description = "Name of the database")
        private final String name;

        @Schema(title = "Database version", description = "D1 engine version")
        private final String version;

        @Schema(title = "Number of tables", description = "Number of tables in the database")
        private final Integer numTables;

        @Schema(title = "File size", description = "Database file size in bytes")
        private final Long fileSize;
    }
}
