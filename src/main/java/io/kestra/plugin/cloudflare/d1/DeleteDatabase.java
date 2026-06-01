package io.kestra.plugin.cloudflare.d1;

import java.net.URI;

import org.slf4j.Logger;

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
    title = "Delete a Cloudflare D1 database",
    description = "Permanently deletes a D1 database by its UUID. This action is irreversible."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a D1 database",
            full = true,
            code = """
                id: delete_d1_database
                namespace: company.team

                tasks:
                  - id: delete_db
                    type: io.kestra.plugin.cloudflare.d1.DeleteDatabase
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                """
        )
    }
)
public class DeleteDatabase extends AbstractCloudflareTask implements RunnableTask<DeleteDatabase.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to delete")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rDatabaseId = runContext.render(databaseId).as(String.class).orElseThrow();

        logger.info("Deleting D1 database '{}' from account '{}'", rDatabaseId, rAccountId);

        var requestBuilder = HttpRequest.builder()
            .method("DELETE")
            .uri(
                URI.create(
                    rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/d1/database/" + encodePathSegment(rDatabaseId)
                )
            );

        HttpResponse<CloudflareEnvelope<Object>> response = this.request(
            runContext, requestBuilder, new TypeReference<CloudflareEnvelope<Object>>() {
            }
        );

        var body = response.getBody();
        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to delete D1 database: " + body);
        }

        logger.info("D1 database '{}' deleted successfully", rDatabaseId);

        return Output.builder()
            .databaseId(rDatabaseId)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Deleted database ID", description = "UUID of the database that was deleted")
        private final String databaseId;
    }
}
