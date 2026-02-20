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
    title = "Upsert DNS record",
    description = "Creates the DNS record if it does not exist, otherwise updates it."
)
@Plugin(
    examples = {
        @Example(
            title = "Ensure A record exists",
            full = true,
            code = """
                tasks:
                  - id: ensure_dns
                    recordType: io.kestra.plugin.cloudflare.dns.records.Upsert
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

    @NotNull
    private Property<String> zoneId;

    @NotNull
    private Property<String> recordType;

    @NotNull
    private Property<String> name;

    @NotNull
    private Property<String> content;

    @Builder.Default
    private Property<Integer> ttl = Property.ofValue(1);

    @Builder.Default
    private Property<Boolean> proxied = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String type = runContext.render(recordType).as(String.class).orElseThrow();
        String recordName = runContext.render(name).as(String.class).orElseThrow();
        String recordContent = runContext.render(content).as(String.class).orElseThrow();
        Integer recordTtl = runContext.render(ttl).as(Integer.class).orElseThrow();
        Boolean recordProxied = runContext.render(proxied).as(Boolean.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        
        var listRequest = HttpRequest.builder()
            .method(GET.name())
            .uri(URI.create(base + "/zones/" + zone +
                "/dns_records?name=" + recordName + "&type=" + type));

        HttpResponse<CloudflareEnvelope<List<RecordResponse>>> listResponse =
            this.request(runContext, listRequest,
                new TypeReference<CloudflareEnvelope<List<RecordResponse>>>() {});

        List<RecordResponse> existing =
            listResponse.getBody() != null ? listResponse.getBody().result() : null;

        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("name", recordName);
        body.put("content", recordContent);
        body.put("ttl", recordTtl);
        body.put("proxied", recordProxied);

        if (existing != null && !existing.isEmpty()) {

            String recordId = existing.get(0).id();
            logger.info("Record exists (id={}), updating...", recordId);

            var patchRequest = HttpRequest.builder()
                .method(PATCH.name())
                .uri(URI.create(base + "/zones/" + zone + "/dns_records/" + recordId))
                .body(HttpRequest.JsonRequestBody.builder()
                    .content(body)
                    .build());

            HttpResponse<CloudflareEnvelope<RecordResponse>> patchResponse =
                this.request(runContext, patchRequest,
                    new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

            return buildOutput(patchResponse.getBody(), "updated");
        }

        logger.info("Record does not exist, creating...");

        var createRequest = HttpRequest.builder()
            .method(POST.name())
            .uri(URI.create(base + "/zones/" + zone + "/dns_records"))
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
        private final String recordId;
        private final String name;
        private final String type;
        private final String content;
        private final Integer ttl;
        private final Boolean proxied;
        private final String action;
    }
}