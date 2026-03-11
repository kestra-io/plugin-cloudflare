package io.kestra.plugin.cloudflare.cache;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

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

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Purge Cloudflare cached content",
    description = "Purges cached assets for a Cloudflare zone. Requires `purgeAll` or at least one file or tag; default keeps the cache unchanged and the task fails if nothing is provided."
)
@Plugin(
    examples = {
        @Example(
            title = "Purge entire zone cache",
            full = true,
            code = """
                id: purge_all_cache
                namespace: company.team

                tasks:
                  - id: purge_all
                    type: io.kestra.plugin.cloudflare.cache.Purge
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    purgeAll: true
                """
        ),
        @Example(
            title = "Purge specific files",
            full = true,
            code = """
                id: purge_specific_files
                namespace: company.team

                tasks:
                  - id: purge_files
                    type: io.kestra.plugin.cloudflare.cache.Purge
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    files:
                      - "https://example.com/app.js"
                """
        )
    }
)
public class Purge extends AbstractCloudflareTask implements RunnableTask<Purge.Output> {

    @Schema(
        title = "Zone ID",
        description = "Identifier of the Cloudflare zone to purge."
    )
    @NotNull
    private Property<String> zoneId;

    @Builder.Default
    @Schema(
        title = "Purge entire cache",
        description = "Set to true to purge the full zone cache; defaults to false and takes precedence over files or tags."
    )
    private Property<Boolean> purgeAll = Property.ofValue(false);

    @Schema(
        title = "Files",
        description = "List of absolute file URLs to purge when not purging everything."
    )
    private Property<List<String>> files;

    @Schema(
        title = "Cache tags",
        description = "Cache tags to purge when tags are configured on the zone."
    )
    private Property<List<String>> tags;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        Boolean rPurgeAll = runContext.render(purgeAll).as(Boolean.class).orElse(false);
        List<String> rFiles = files != null ? runContext.render(files).asList(String.class) : null;
        List<String> rTags = tags != null ? runContext.render(tags).asList(String.class) : null;

        boolean hasFiles = rFiles != null && !rFiles.isEmpty();
        boolean hasTags = rTags != null && !rTags.isEmpty();

        if (!rPurgeAll && !hasFiles && !hasTags) {
            throw new IllegalArgumentException(
                "Invalid purge configuration. Provide either 'purgeAll: true', non-empty 'files', or non-empty 'tags'."
            );
        }

        Map<String, Object> bodyContent;

        if (rPurgeAll) {
            logger.info("Purging entire cache for zone '{}'", rZoneId);
            bodyContent = Map.of("purge_everything", true);

        } else if (hasFiles) {
            logger.info("Purging {} file(s) for zone '{}'", rFiles.size(), rZoneId);
            bodyContent = Map.of("files", rFiles);

        } else {
            logger.info("Purging {} tag(s) for zone '{}'", rTags.size(), rZoneId);
            bodyContent = Map.of("tags", rTags);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/purge_cache"))
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(bodyContent)
                    .build()
            );

        HttpResponse<CloudflareEnvelope<PurgeResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<PurgeResponse>>() {
        });

        CloudflareEnvelope<PurgeResponse> envelope = response.getBody();

        if (envelope == null || !envelope.success() || envelope.result() == null) {
            throw new IllegalStateException("Cache purge failed: " + envelope);
        }

        return Output.builder()
            .requestId(envelope.result().id())
            .build();
    }

    public record PurgeResponse(
        String id) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Request ID",
            description = "Unique identifier of the cache purge request."
        )
        private final String requestId;
    }
}
