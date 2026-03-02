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
    title = "Batch mutate DNS records",
    description = "Creates, updates, or deletes multiple DNS records in one request. Cloudflare requires all three arrays; pass empty lists for operations you do not use."
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
                    type: io.kestra.plugin.cloudflare.dns.records.Batch
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
        description = "Cloudflare zone identifier"
    )
    @NotNull
    private Property<String> zoneId;

    @Schema(
        title = "Records to create",
        description = "Records to create; provide an empty list if not creating"
    )
    private Property<List<RecordInput>> posts;

    @Schema(
        title = "Records to update",
        description = "Records to update with IDs; provide an empty list if not updating"
    )
    private Property<List<RecordPatch>> patches;

    @Schema(
        title = "Record IDs to delete",
        description = "DNS record IDs to delete; must be provided (can be empty)"
    )
    private Property<List<RecordDelete>> deletes;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        List<RecordInput> rPosts = runContext.render(posts).asList(RecordInput.class);
        List<RecordPatch> rPatches = runContext.render(patches).asList(RecordPatch.class);
        List<RecordDelete> rDeletes = runContext.render(deletes).asList(RecordDelete.class);

        logger.info("Executing batch DNS operation for zone '{}'", rZoneId);

        Map<String, Object> body = new java.util.HashMap<>();

        if (!rPosts.isEmpty()) {
            body.put("posts", rPosts);
        }

        if (!rPatches.isEmpty()) {
            body.put("patches", rPatches);
        }

        if (!rDeletes.isEmpty()) {
            body.put("deletes", rDeletes);
        }

        if (body.isEmpty()) {
            throw new IllegalArgumentException("At least one of posts, patches, or deletes must be provided.");
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records/batch"))
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
        @Schema(title = "Success", description = "True when Cloudflare accepted the batch request")
        private final Boolean success;
    }
}
