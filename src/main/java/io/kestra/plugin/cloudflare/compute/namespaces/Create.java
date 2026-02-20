package io.kestra.plugin.cloudflare.compute.namespaces;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.cloudflare.AbstractCloudflareTask;
import io.kestra.plugin.cloudflare.models.CloudflareEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Create KV Namespace",
    description = "Creates a new Workers KV namespace under an account."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a Workers KV namespace",
            full = true,
            code = """
                tasks:
                  - id: create_namespace
                    type: io.kestra.plugin.cloudflare.compute.namespaces.Create
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    title: "my-namespace"
                """
        )
    }
)
public class Create extends AbstractCloudflareTask implements RunnableTask<Create.Output> {

    @Schema(
        title = "Account ID",
        description = "Cloudflare account identifier."
    )
    @NotNull
    private Property<String> accountId;

    @Schema(
        title = "Namespace Title",
        description = "Human-readable title for the namespace."
    )
    @NotNull
    private Property<String> title;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        String rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        String rTitle = runContext.render(title).as(String.class).orElseThrow();

        logger.info("Creating KV namespace '{}' in account '{}'", rTitle, rAccountId);

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/accounts/" + rAccountId + "/storage/kv/namespaces"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(Map.of("title", rTitle))
                .build());

        HttpResponse<CloudflareEnvelope<NamespaceResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<NamespaceResponse>>() {});

        var body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to create namespace: " + rTitle);
        }

        logger.info("Namespace '{}' created with ID '{}'", rTitle, body.result().id());

        return Output.builder()
            .namespaceId(body.result().id())
            .title(body.result().title())
            .build();
    }

    public record NamespaceResponse(String id, String title) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Namespace ID", description = "Unique namespace identifier.")
        private final String namespaceId;

        @Schema(title = "Title", description = "Namespace title.")
        private final String title;
    }
}
