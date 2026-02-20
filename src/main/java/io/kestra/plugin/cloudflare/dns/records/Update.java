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
                tasks:
                  - id: update_record
                    recordType: io.kestra.plugin.cloudflare.dns.records.Patch
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordId: "abc123"
                    content: "5.6.7.8"
                """
        )
    }
)
public class Update extends AbstractCloudflareTask implements RunnableTask<Update.Output> {

    @NotNull
    private Property<String> zoneId;

    @NotNull
    private Property<String> recordId;

    private Property<String> recordType;
    private Property<String> name;
    private Property<String> content;
    private Property<Integer> ttl;
    private Property<Boolean> proxied;

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String id = runContext.render(recordId).as(String.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        Map<String, Object> body = new HashMap<>();

        if (recordType != null) body.put("type", runContext.render(recordType).as(String.class).orElse(null));
        if (name != null) body.put("name", runContext.render(name).as(String.class).orElse(null));
        if (content != null) body.put("content", runContext.render(content).as(String.class).orElse(null));
        if (ttl != null) body.put("ttl", runContext.render(ttl).as(Integer.class).orElse(null));
        if (proxied != null) body.put("proxied", runContext.render(proxied).as(Boolean.class).orElse(null));

        logger.info("Updating DNS record '{}' in zone '{}'", id, zone);

        var requestBuilder = HttpRequest.builder()
            .method("PATCH")
            .uri(URI.create(base + "/zones/" + zone + "/dns_records/" + id))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(body)
                .build()
            );

        HttpResponse<CloudflareEnvelope<RecordResponse>> response =
            this.request(runContext, requestBuilder,
                new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

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
        private final String id;
    }
}