package io.kestra.plugin.cloudflare.compute.kv;

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
import java.util.List;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Plugin(
    examples = {
        @Example(
            title = "Get multiple KV values",
            full = true,
            code = """
                tasks:
                  - id: bulk_get_kv
                    type: io.kestra.plugin.cloudflare.compute.kv.Get
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    namespaceId: "your_namespace_id"
                    keys:
                      - "feature_flag"
                      - "release_version"
                """
        )
    }
)
@Schema(
    title = "Bulk Get KV Pairs",
    description = "Retrieves multiple key-value pairs from a KV namespace."
)
public class Get extends AbstractCloudflareTask implements RunnableTask<Get.Output> {

    @Schema(description = "Cloudflare account identifier.")
    @NotNull
    private Property<String> accountId;

    @Schema(description = "Workers KV namespace identifier.")
    @NotNull
    private Property<String> namespaceId;

    @Schema(description = "List of keys to retrieve from the namespace.")
    @NotNull
    private Property<List<String>> keys;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        String rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        String rNamespaceId = runContext.render(namespaceId).as(String.class).orElseThrow();
        List<String> rKeys = runContext.render(keys).asList(String.class);

        logger.info("Retrieving {} KV keys from namespace '{}'", rKeys.size(), rNamespaceId);

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/accounts/" + rAccountId + "/storage/kv/namespaces/" + rNamespaceId + "/bulk/get"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(Map.of("keys", rKeys))
                .build());

        HttpResponse<CloudflareEnvelope<BulkGetResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<BulkGetResponse>>() {});

        var body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Bulk get failed.");
        }

        return Output.builder()
            .values(body.result().values())
            .build();
    }

    public record BulkGetResponse(Map<String, Object> values) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Retrieved Values")
        private final Map<String, Object> values;
    }
}
