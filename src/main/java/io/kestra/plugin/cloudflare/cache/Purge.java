package io.kestra.plugin.cloudflare.cache;

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
import jakarta.validation.constraints.AssertTrue;
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
@Schema(
    title = "Purge Cloudflare Cache",
    description = "Purges Cloudflare cache for a zone. You can purge the entire cache, specific files, or by cache tags."
)
@Plugin(
    examples = {
        @Example(
            title = "Purge entire zone cache",
            full = true,
            code = """
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
        description = "Unique identifier of the Cloudflare zone whose cache should be purged."
    )
    @NotNull
    private Property<String> zoneId;

    @Builder.Default
    @Schema(
        title = "Purge all cache",
        description = "If true, purges the entire cache for the zone."
    )
    private Property<Boolean> purgeAll = Property.ofValue(false);

    @Schema(
        title = "Files",
        description = "List of specific file URLs to purge from cache."
    )
    private Property<List<String>> files;

    @Schema(
        title = "Cache tags",
        description = "List of cache tags to purge."
    )
    private Property<List<String>> tags;

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        Boolean purgeAllValue = runContext.render(purgeAll).as(Boolean.class).orElse(false);
        List<String> renderedFiles = files != null ? runContext.render(files).asList(String.class) : null;
        List<String> renderedTags = tags != null ? runContext.render(tags).asList(String.class) : null;

        boolean hasFiles = renderedFiles != null && !renderedFiles.isEmpty();
        boolean hasTags = renderedTags != null && !renderedTags.isEmpty();

        if (!purgeAllValue && !hasFiles && !hasTags) {
            throw new IllegalArgumentException(
                "Invalid purge configuration. Provide either 'purgeAll: true', non-empty 'files', or non-empty 'tags'."
            );
        }

        Map<String, Object> bodyContent;

        if (purgeAllValue) {
            logger.info("Purging entire cache for zone '{}'", zone);
            bodyContent = Map.of("purge_everything", true);

        } else if (hasFiles) {
            logger.info("Purging {} file(s) for zone '{}'", renderedFiles.size(), zone);
            bodyContent = Map.of("files", renderedFiles);

        } else {
            logger.info("Purging {} tag(s) for zone '{}'", renderedTags.size(), zone);
            bodyContent = Map.of("tags", renderedTags);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(base + "/zones/" + zone + "/purge_cache"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(bodyContent)
                .build());

        HttpResponse<CloudflareEnvelope<PurgeResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<PurgeResponse>>() {});

        CloudflareEnvelope<PurgeResponse> envelope = response.getBody();

        if (envelope == null || !envelope.success() || envelope.result() == null) {
            throw new IllegalStateException("Cache purge failed: " + envelope);
        }

        return Output.builder()
            .requestId(envelope.result().id())
            .build();
    }

    public record PurgeResponse(
        String id
    ) {}

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