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
import java.util.List;
import java.util.Map;

import static org.apache.hc.core5.http.Method.*;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Upsert Cloudflare DNS record",
    description = "Creates a DNS record if it does not exist, otherwise patches the first match by name and type; defaults to automatic TTL (1) and proxying disabled."
)
@Plugin(
    examples = {
        @Example(
            title = "Ensure A record exists",
            full = true,
            code = """
                id: upsert_dns_record
                namespace: company.team

                tasks:
                  - id: ensure_dns
                    type: io.kestra.plugin.cloudflare.dns.records.Upsert
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordType: "A"
                    name: "app.example.com"
                    content: "1.2.3.4"
                """
        )
    }
)
public class Upsert extends AbstractCloudflareTask implements RunnableTask<Upsert.Output> {

    @Schema(title = "Zone ID", description = "Cloudflare zone identifier")
    @NotNull
    private Property<String> zoneId;

    @Schema(title = "Record type", description = "DNS record type (for example: A, AAAA, CNAME)")
    @NotNull
    private Property<DnsRecordType> recordType;

    @Schema(title = "Record name", description = "DNS record name to create or update")
    @NotNull
    private Property<String> name;

    @Schema(title = "Record content", description = "DNS record content value")
    @NotNull
    private Property<String> content;

    @Builder.Default
    @Schema(title = "TTL", description = "TTL in seconds; 1 uses Cloudflare automatic (default)")
    private Property<Integer> ttl = Property.ofValue(1);

    @Builder.Default
    @Schema(title = "Proxied", description = "Enable Cloudflare proxying; defaults to false")
    private Property<Boolean> proxied = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        DnsRecordType rRecordType = runContext.render(recordType).as(DnsRecordType.class).orElseThrow();
        String rName = runContext.render(name).as(String.class).orElseThrow();
        String rContent = runContext.render(content).as(String.class).orElseThrow();
        Integer rTtl = runContext.render(ttl).as(Integer.class).orElseThrow();
        Boolean rProxied = runContext.render(proxied).as(Boolean.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        var listRequest = HttpRequest.builder()
            .method(GET.name())
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId +
                "/dns_records?name=" + rName + "&type=" + rRecordType.name()));

        HttpResponse<CloudflareEnvelope<List<RecordResponse>>> listResponse =
            this.request(runContext, listRequest,
                new TypeReference<CloudflareEnvelope<List<RecordResponse>>>() {});

        List<RecordResponse> existing =
            listResponse.getBody() != null ? listResponse.getBody().result() : null;

        Map<String, Object> body = new HashMap<>();
        body.put("type", rRecordType);
        body.put("name", rName);
        body.put("content", rContent);
        body.put("ttl", rTtl);
        body.put("proxied", rProxied);

        if (existing != null && !existing.isEmpty()) {

            String recordId = existing.getFirst().id();
            logger.info("Record exists (id={}), updating...", recordId);

            var patchRequest = HttpRequest.builder()
                .method(PATCH.name())
                .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records/" + recordId))
                .body(HttpRequest.JsonRequestBody.builder()
                    .content(body)
                    .build());

            HttpResponse<CloudflareEnvelope<RecordResponse>> patchResponse = this.request(runContext, patchRequest, new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

            return buildOutput(patchResponse.getBody(), "updated");
        }

        logger.info("Record does not exist, creating...");

        var createRequest = HttpRequest.builder()
            .method(POST.name())
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(body)
                .build());

        HttpResponse<CloudflareEnvelope<RecordResponse>> createResponse =
            this.request(runContext, createRequest,
                new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

        return buildOutput(createResponse.getBody(), "created");
    }

    private Output buildOutput(CloudflareEnvelope<RecordResponse> envelope, String action) {
        if (envelope == null || !envelope.success()) {
            throw new IllegalStateException("Upsert failed: " + envelope);
        }

        RecordResponse result = envelope.result();

        return Output.builder()
            .recordId(result.id())
            .name(result.name())
            .type(result.type())
            .content(result.content())
            .ttl(result.ttl())
            .proxied(result.proxied())
            .action(action)
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
        @Schema(title = "Record ID", description = "ID of the created or updated record")
        private final String recordId;
        @Schema(title = "Name", description = "Record name")
        private final String name;
        @Schema(title = "Type", description = "Record type")
        private final String type;
        @Schema(title = "Content", description = "Record content")
        private final String content;
        @Schema(title = "TTL", description = "Record TTL in seconds")
        private final Integer ttl;
        @Schema(title = "Proxied", description = "Whether Cloudflare proxying is enabled")
        private final Boolean proxied;
        @Schema(title = "Action", description = "Whether the record was created or updated")
        private final String action;
    }
}
