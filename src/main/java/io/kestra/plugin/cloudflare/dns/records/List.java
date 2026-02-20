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
import jakarta.validation.constraints.NotNull;
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
    title = "List DNS records",
    description = "Retrieves all DNS records for a Cloudflare zone."
)
@Plugin(
    examples = {
        @Example(
            title = "List DNS records",
            full = true,
            code = """
                tasks:
                  - id: list_records
                    type: io.kestra.plugin.cloudflare.dns.records.List
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                """
        )
    }
)
public class List extends AbstractCloudflareTask implements RunnableTask<List.Output> {

    @NotNull
    @Schema(title = "Zone ID", description = "Cloudflare zone identifier.")
    private Property<String> zoneId;

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Listing DNS records for zone '{}'", zone);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(base + "/zones/" + zone + "/dns_records"));

        HttpResponse<CloudflareEnvelope<java.util.List<RecordResponse>>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<java.util.List<RecordResponse>>>() {});

        CloudflareEnvelope<java.util.List<RecordResponse>> body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to list DNS records: " + body);
        }

        return Output.builder()
            .records(body.result())
            .build();
    }

    public record RecordResponse(
        String id,
        String name,
        String type,
        String content,
        Integer ttl,
        Boolean proxied
    ) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "DNS records")
        private final java.util.List<RecordResponse> records;
    }
}