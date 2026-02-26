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
import java.util.HashMap;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Update DNS record",
    description = "Updates an existing DNS record in Cloudflare."
)
@Plugin(
    examples = {
        @Example(
            title = "Update DNS record content",
            full = true,
            code = """
                id: update_dns_record
                namespace: company.team

                tasks:
                  - id: update_record
                    type: io.kestra.plugin.cloudflare.dns.records.Update
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordId: "abc123"
                    content: "5.6.7.8"
                """
        )
    }
)
public class Update extends AbstractCloudflareTask implements RunnableTask<Update.Output> {

    @Schema(description = "Cloudflare zone identifier.")
    @NotNull
    private Property<String> zoneId;

    @Schema(description = "ID of the DNS record to update.")
    @NotNull
    private Property<String> recordId;

    @Schema(description = "New DNS record type (for example: A, AAAA, CNAME).")
    private Property<String> recordType;
    @Schema(description = "New DNS record name.")
    private Property<String> name;
    @Schema(description = "New DNS record content value.")
    private Property<String> content;
    @Schema(description = "New TTL value in seconds.")
    private Property<Integer> ttl;
    @Schema(description = "Whether Cloudflare proxy should be enabled.")
    private Property<Boolean> proxied;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rRecordId = runContext.render(recordId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        Map<String, Object> body = new HashMap<>();

        runContext.render(recordType).as(String.class).ifPresent(rRecordType -> body.put("type", rRecordType));
        runContext.render(name).as(String.class).ifPresent(rName -> body.put("name", rName));
        runContext.render(content).as(String.class).ifPresent(rContent -> body.put("content", rContent));
        runContext.render(ttl).as(Integer.class).ifPresent(rTtl -> body.put("ttl", rTtl));
        runContext.render(proxied).as(Boolean.class).ifPresent(rProxied -> body.put("proxied", rProxied));

        logger.info("Updating DNS record '{}' in zone '{}'", rRecordId, rZoneId);

        var requestBuilder = HttpRequest.builder()
            .method("PATCH")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records/" + rRecordId))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(body)
                .build()
            );

        HttpResponse<CloudflareEnvelope<RecordResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

        CloudflareEnvelope<RecordResponse> result = response.getBody();

        if (result == null || !result.success()) {
            throw new IllegalStateException("Failed to update DNS record: " + result);
        }

        return Output.builder()
            .id(result.result().id())
            .build();
    }

    public record RecordResponse(String id) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Record ID")
        private final String id;
    }
}
