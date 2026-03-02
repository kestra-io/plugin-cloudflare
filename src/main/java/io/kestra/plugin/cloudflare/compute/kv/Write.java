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

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Plugin(
    examples = {
        @Example(
            title = "Write multiple KV pairs",
            full = true,
            code = """
                id: bulk_write_kv_flow
                namespace: company.team

                tasks:
                  - id: bulk_write_kv
                    type: io.kestra.plugin.cloudflare.compute.kv.Write
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    namespaceId: "your_namespace_id"
                    keyValues:
                      - key: "feature_flag"
                        value: "enabled"
                      - key: "release_version"
                        value: "v1.2.3"
                """
        )
    }
)
@Schema(
    title = "Bulk write Workers KV items",
    description = "Writes multiple key-value pairs to a Workers KV namespace in one request; existing keys are overwritten and the task fails if Cloudflare reports unsuccessful writes."
)
public class Write extends AbstractCloudflareTask implements RunnableTask<Write.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account ID that owns the namespace")
    @NotNull
    private Property<String> accountId;

    @Schema(title = "Namespace ID", description = "Target Workers KV namespace ID")
    @NotNull
    private Property<String> namespaceId;

    @Schema(
        title = "Key-Value Pairs",
        description = "Entries to write; all are sent in one bulk request and overwrite existing values with the same key"
    )
    @NotNull
    private Property<List<KVPair>> keyValues;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        String rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        String rNamespaceId = runContext.render(namespaceId).as(String.class).orElseThrow();
        List<KVPair> rPairs = runContext.render(keyValues).asList(KVPair.class);

        logger.info("Writing {} KV entries into namespace '{}'", rPairs.size(), rNamespaceId);

        var requestBuilder = HttpRequest.builder()
            .method("PUT")
            .uri(URI.create(rBaseUrl + "/accounts/" + rAccountId +
                "/storage/kv/namespaces/" + rNamespaceId + "/bulk"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(rPairs)
                .build());

        HttpResponse<CloudflareEnvelope<BulkResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<BulkResponse>>() {});

        var body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Bulk write failed.");
        }

        return Output.builder()
            .successfulKeyCount(body.result().successful_key_count())
            .unsuccessfulKeys(body.result().unsuccessful_keys())
            .build();
    }

    public record KVPair(String key, String value) {}

    public record BulkResponse(Integer successful_key_count, List<String> unsuccessful_keys) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Successful Key Count", description = "Number of keys Cloudflare confirmed written")
        private final Integer successfulKeyCount;

        @Schema(title = "Unsuccessful Keys", description = "Keys Cloudflare reported as not written")
        private final List<String> unsuccessfulKeys;
    }
}
