package io.kestra.plugin.cloudflare.zones;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.cloudflare.AbstractCloudflareTask;
import io.kestra.plugin.cloudflare.models.CloudflareEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;


@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "List Zones",
    description = "List all Cloudflare zones associated with the account."
)
@Plugin(
    examples = {
        @Example(
            title = "List zones",
            full = true,
            code = """
                tasks:
                  - id: list_zones
                    type: io.kestra.plugin.cloudflare.zones.List
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                """
        )
    }
)
public class List extends AbstractCloudflareTask implements RunnableTask<List.Output> {

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Listing Cloudflare zones");

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(base + "/zones"));

        HttpResponse<CloudflareEnvelope<java.util.List<ZoneResponse>>> response =
            this.request(runContext, requestBuilder,
                new TypeReference<CloudflareEnvelope<java.util.List<ZoneResponse>>>() {});

        CloudflareEnvelope<java.util.List<ZoneResponse>> body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to list zones: " + body);
        }

        return Output.builder()
            .zones(body.result())
            .build();
    }

    public record ZoneResponse(
        String id,
        String name,
        String status
    ) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private final java.util.List<ZoneResponse> zones;
    }
}