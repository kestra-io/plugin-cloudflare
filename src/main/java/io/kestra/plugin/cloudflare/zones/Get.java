package io.kestra.plugin.cloudflare.zones;

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
@Schema(
    title = "Get Zone Details",
    description = "Retrieve details of a Cloudflare zone using either its zone ID or its hostname. If both are provided, `zoneId` takes precedence."
)
@Plugin(
    examples = {
        @Example(
            title = "Get zone by ID",
            full = true,
            code = """
                tasks:
                  - id: get_zone
                    type: io.kestra.plugin.cloudflare.zones.Get
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "zone123"
                """
        ),
        @Example(
            title = "Get zone by hostname",
            full = true,
            code = """
                tasks:
                  - id: get_zone
                    type: io.kestra.plugin.cloudflare.zones.Get
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    hostname: "example.com"
                """
        )
    }
)
public class Get extends AbstractCloudflareTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Zone ID",
        description = "Unique identifier of the Cloudflare zone. If both `zoneId` and `hostname` are provided, `zoneId` takes priority."
    )
    private Property<String> zoneId;

    @Schema(
        title = "Zone hostname",
        description = "The domain name of the Cloudflare zone (for example: example.com). Used to look up the zone if `zoneId` is not provided."
    )
    private Property<String> hostname;

    @AssertTrue(message = "Either 'zoneId' or 'hostname' must be provided.")
    public boolean isValidInput() {
        return zoneId != null || hostname != null;
    }

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();


        if (zoneId != null) {
            String id = runContext.render(zoneId).as(String.class).orElseThrow();
            logger.info("Fetching zone by ID '{}'", id);

            var requestBuilder = HttpRequest.builder()
                .method("GET")
                .uri(URI.create(base + "/zones/" + id));

            HttpResponse<CloudflareEnvelope<ZoneResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<ZoneResponse>>() {});

            CloudflareEnvelope<ZoneResponse> body = response.getBody();

            if (body == null || !body.success() || body.result() == null) {
                throw new IllegalStateException("Failed to fetch zone by ID: " + id);
            }

            return buildOutput(body.result());
        }

        String name = runContext.render(hostname).as(String.class).orElseThrow();
        logger.info("Fetching zone by hostname '{}'", name);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(base + "/zones?name=" + name));

        HttpResponse<CloudflareEnvelope<List<ZoneResponse>>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<List<ZoneResponse>>>() {});

        CloudflareEnvelope<List<ZoneResponse>> body = response.getBody();

        if (body == null || !body.success() || body.result() == null || body.result().isEmpty()) {
            throw new IllegalStateException("Zone not found for hostname: " + name);
        }

        return buildOutput(body.result().getFirst());
    }

    private Output buildOutput(ZoneResponse result) {
        return Output.builder()
            .id(result.id())
            .name(result.name())
            .status(result.status())
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

        @Schema(
            title = "Zone ID",
            description = "Unique identifier of the Cloudflare zone."
        )
        private final String id;

        @Schema(
            title = "Zone Name",
            description = "Domain name associated with the zone."
        )
        private final String name;

        @Schema(
            title = "Zone Status",
            description = "Current status of the zone (for example: active, pending)."
        )
        private final String status;
    }
}