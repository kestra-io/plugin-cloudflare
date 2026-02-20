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
    title = "Get DNS record details",
    description = "Retrieve full details of a specific DNS record by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get DNS record details",
            full = true,
            code = """
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

    @NotNull
    private Property<String> zoneId;

    @NotNull
    private Property<String> recordId;

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String id = runContext.render(recordId).as(String.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Fetching DNS record '{}' from zone '{}'", id, zone);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(base + "/zones/" + zone + "/dns_records/" + id));

        HttpResponse<CloudflareEnvelope<RecordResponse>> response =
            this.request(runContext, requestBuilder,
                new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

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
        private final String recordId;
        private final String name;
        private final String type;
        private final String content;
        private final Integer ttl;
        private final Boolean proxied;
    }
}