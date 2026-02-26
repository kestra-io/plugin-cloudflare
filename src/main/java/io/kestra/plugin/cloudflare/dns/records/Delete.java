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
    title = "Delete DNS record",
    description = "Deletes a DNS record from a Cloudflare zone using its record ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a DNS record",
            full = true,
            code = """
                id: delete_dns_record
                namespace: company.team

                tasks:
                  - id: delete_record
                    type: io.kestra.plugin.cloudflare.dns.records.Delete
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordId: "abc123"
                """
        )
    }
)
public class Delete extends AbstractCloudflareTask implements RunnableTask<Delete.Output> {

    @NotNull
    @Schema(title = "Zone ID", description = "Cloudflare zone identifier.")
    private Property<String> zoneId;

    @NotNull
    @Schema(title = "Record ID", description = "The ID of the DNS record to delete.")
    private Property<String> recordId;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rRecordId = runContext.render(recordId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Deleting DNS record '{}' from zone '{}'", rRecordId, rZoneId);

        var requestBuilder = HttpRequest.builder()
            .method("DELETE")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records/" + rRecordId));

        HttpResponse<CloudflareEnvelope<DeleteResult>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<DeleteResult>>() {});

        CloudflareEnvelope<DeleteResult> body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Failed to delete DNS record: " + body);
        }

        return Output.builder()
            .deletedId(body.result().id())
            .build();
    }

    public record DeleteResult(String id) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Deleted Record ID")
        private final String deletedId;
    }
}
