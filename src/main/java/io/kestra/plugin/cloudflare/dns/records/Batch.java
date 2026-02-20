package io.kestra.plugin.cloudflare.dns.records;

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
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Batch DNS record operations",
    description = "Create, update, or delete multiple DNS records in a single Cloudflare API call."
)
@Plugin(
    examples = {
        @Example(
            title = "Batch create DNS records",
            full = true,
            code = """
                id: batch_dns
                namespace: company.team

                tasks:
                  - id: batch_records
                    type: io.kestra.plugin.cloudflare.dns.BatchDnsRecords
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    posts:
                      - type: "A"
                        name: "app1.example.com"
                        content: "1.2.3.4"
                        ttl: 1
                        proxied: false
                      - type: "A"
                        name: "app2.example.com"
                        content: "5.6.7.8"
                        ttl: 1
                        proxied: false
                """
        )
    }
)
public class Batch extends AbstractCloudflareTask implements RunnableTask<Batch.Output> {

    @Schema(
        title = "Zone ID",
        description = "The unique identifier of your Cloudflare zone."
    )
    @NotNull
    private Property<String> zoneId;

    @Schema(
        title = "Records to create",
        description = "List of DNS records to create."
    )
    private Property<List<RecordInput>> posts;

    @Schema(
        title = "Records to update",
        description = "List of DNS records to update (requires record ID)."
    )
    private Property<List<RecordPatch>> patches;

    @Schema(
        title = "Record IDs to delete",
        description = "List of DNS record IDs to delete."
    )
    private Property<List<RecordDelete>> deletes;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        List<RecordInput> createList = runContext.render(posts).asList(RecordInput.class);
        List<RecordPatch> patchList = runContext.render(patches).asList(RecordPatch.class);
        List<RecordDelete> deleteList = runContext.render(deletes).asList(RecordDelete.class);

        logger.info("Executing batch DNS operation for zone '{}'", zone);

        assert deleteList != null;
        Map<String, Object> body = Map.of(
            "posts", createList,
            "patches", patchList,
            "deletes", deleteList
        );

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(base + "/zones/" + zone + "/dns_records/batch"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(body)
                .build()
            );

        HttpResponse<CloudflareEnvelope<BatchResult>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<BatchResult>>() {});

        CloudflareEnvelope<BatchResult> result = response.getBody();

        if (result == null || !result.success()) {
            throw new IllegalStateException("Batch DNS operation failed: " + result);
        }

        logger.info("Batch DNS operation completed successfully");

        return Output.builder()
            .success(true)
            .build();
    }


    public record RecordInput(
        String type,
        String name,
        String content,
        Integer ttl,
        Boolean proxied
    ) {}

    public record RecordPatch(
        String id,
        String type,
        String name,
        String content,
        Integer ttl,
        Boolean proxied
    ) {}

    public record RecordDelete(String id) {}

    public record BatchResult(
        Object posts,
        Object patches,
        Object deletes
    ) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Success", description = "Whether the batch operation was successful.")
        private final Boolean success;
    }
}