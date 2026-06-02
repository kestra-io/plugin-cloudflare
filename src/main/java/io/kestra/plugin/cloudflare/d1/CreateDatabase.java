package io.kestra.plugin.cloudflare.d1;

import java.net.URI;
import java.util.Map;

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
    title = "Create a Cloudflare D1 database",
    description = "Creates a new D1 serverless SQLite database in the given account."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a D1 database",
            full = true,
            code = """
                id: create_d1_database
                namespace: company.team

                tasks:
                  - id: create_db
                    type: io.kestra.plugin.cloudflare.d1.CreateDatabase
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    name: "my-database"
                """
        )
    }
)
public class CreateDatabase extends AbstractCloudflareTask implements RunnableTask<CreateDatabase.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database name", description = "Name of the D1 database to create")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> name;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rName = runContext.render(name).as(String.class).orElseThrow();

        logger.info("Creating D1 database '{}' in account '{}'", rName, rAccountId);

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/d1/database"))
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("name", rName))
                    .build()
            );

        HttpResponse<CloudflareEnvelope<DatabaseResponse>> response = this.request(
            runContext, requestBuilder, new TypeReference<CloudflareEnvelope<DatabaseResponse>>() {
            }
        );

        var body = response.getBody();
        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to create D1 database: " + body);
        }

        var result = body.result();
        logger.info("D1 database created with uuid '{}'", result.uuid());

        return Output.builder()
            .databaseId(result.uuid())
            .name(result.name())
            .version(result.version())
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatabaseResponse(String uuid, String name, String version) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Database ID", description = "UUID of the created database; use in downstream tasks via {{ outputs.taskId.databaseId }}")
        private final String databaseId;

        @Schema(title = "Database name", description = "Name of the created database")
        private final String name;

        @Schema(title = "Database version", description = "D1 engine version")
        private final String version;
    }
}
