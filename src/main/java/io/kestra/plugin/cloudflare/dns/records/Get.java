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
    title = "Fetch Cloudflare DNS record",
    description = "Retrieves DNS record details by ID within a zone; fails if Cloudflare does not return the record."
)
@Plugin(
    examples = {
        @Example(
            title = "Get DNS record details",
            full = true,
            code = """
                id: get_dns_record
                namespace: company.team

                tasks:
                  - id: get_record
                    type: io.kestra.plugin.cloudflare.dns.records.Get
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordId: "abc123"
                """
        )
    }
)
public class Get extends AbstractCloudflareTask implements RunnableTask<Get.Output> {

    @Schema(title = "Zone ID", description = "Cloudflare zone identifier")
    @NotNull
    private Property<String> zoneId;

    @Schema(title = "Record ID", description = "ID of the DNS record to fetch")
    @NotNull
    private Property<String> recordId;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rRecordId = runContext.render(recordId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Fetching DNS record '{}' from zone '{}'", rRecordId, rZoneId);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records/" + rRecordId));

        HttpResponse<CloudflareEnvelope<RecordResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

        CloudflareEnvelope<RecordResponse> body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to fetch DNS record: " + body);
        }

        RecordResponse result = body.result();

        return Output.builder()
            .recordId(result.id())
            .name(result.name())
            .type(result.type())
            .content(result.content())
            .ttl(result.ttl())
            .proxied(result.proxied())
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
        @Schema(title = "Record ID", description = "ID returned by Cloudflare")
        private final String recordId;
        @Schema(title = "Name", description = "Record name")
        private final String name;
        @Schema(title = "Type", description = "Record type")
        private final String type;
        @Schema(title = "Content", description = "Record content value")
        private final String content;
        @Schema(title = "TTL", description = "Record TTL in seconds")
        private final Integer ttl;
        @Schema(title = "Proxied", description = "Whether the record is proxied by Cloudflare")
        private final Boolean proxied;
    }
}
