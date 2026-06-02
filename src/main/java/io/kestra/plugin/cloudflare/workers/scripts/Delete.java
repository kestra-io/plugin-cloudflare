package io.kestra.plugin.cloudflare.workers.scripts;

import java.net.URI;
import java.util.Map;

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
import io.kestra.core.models.tasks.VoidOutput;
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
    title = "Delete a Cloudflare Worker script",
    description = "Deletes a Worker script by name. Set `force` to true to delete a script still referenced by routes."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a Worker script",
            full = true,
            code = """
                id: delete_worker
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.cloudflare.workers.scripts.Delete
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    scriptName: "hello-worker"
                    force: true
                """
        )
    }
)
public class Delete extends AbstractCloudflareTask implements RunnableTask<VoidOutput> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier that owns the Worker.")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Script name", description = "Name of the Worker script to delete.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> scriptName;

    @Schema(title = "Force", description = "If true, deletes the script even when routes still reference it.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> force = Property.ofValue(false);

    @Override
    public VoidOutput run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rScriptName = runContext.render(scriptName).as(String.class).orElseThrow();
        var rForce = runContext.render(force).as(Boolean.class).orElse(false);
        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        var uri = rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
            + "/workers/scripts/" + encodePathSegment(rScriptName)
            + (rForce ? "?force=true" : "");

        logger.info("Deleting Worker script '{}' from account '{}' (force={})", rScriptName, rAccountId, rForce);

        var requestBuilder = HttpRequest.builder()
            .method("DELETE")
            .uri(URI.create(uri));

        HttpResponse<CloudflareEnvelope<Map<String, Object>>> response = this.request(
            runContext,
            requestBuilder,
            new TypeReference<CloudflareEnvelope<Map<String, Object>>>() {
            }
        );

        var envelope = response.getBody();

        if (envelope == null || !envelope.success()) {
            throw new IllegalStateException("Failed to delete Worker script: " + envelope);
        }

        return new VoidOutput();
    }
}
