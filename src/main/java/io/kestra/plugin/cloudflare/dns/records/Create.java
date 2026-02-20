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

import static org.apache.hc.core5.http.Method.POST;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Create DNS record",
    description = "Creates a new DNS record in a Cloudflare zone. Useful for automating domain provisioning, blue/green deployments, or SaaS onboarding."
)
@Plugin(
    examples = {
        @Example(
            title = "Create an A record",
            full = true,
            code = """
                id: create_dns
                namespace: company.team

                tasks:
                  - id: create_record
                    recordType: io.kestra.plugin.cloudflare.dns.records.Create
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                    recordType: "A"
                    name: "app.example.com"
                    content: "1.2.3.4"
                    proxied: true
                """
        )
    }
)
public class Create extends AbstractCloudflareTask implements RunnableTask<Create.Output> {

    @Schema(
        title = "Zone ID",
        description = "The unique identifier of your domain (zone) in Cloudflare. You can find this in the Cloudflare dashboard."
    )
    @NotNull
    private Property<String> zoneId;

    @Schema(
        title = "Record recordType",
        description = "Type of DNS record. Common values are A, AAAA, CNAME, TXT, MX."
    )
    @NotNull
    private Property<String> recordType;

    @Schema(
        title = "Record name",
        description = "The hostname for the record. Example: app.example.com or just 'app'."
    )
    @NotNull
    private Property<String> name;

    @Schema(
        title = "Record content",
        description = "The value of the record. For example, an IP address (for A record) or a domain (for CNAME)."
    )
    @NotNull
    private Property<String> content;

    @Schema(
        title = "TTL",
        description = "Time to live in seconds. Use 1 for automatic. Default is automatic."
    )
    @Builder.Default
    private Property<Integer> ttl = Property.ofValue(1);

    @Schema(
        title = "Proxied",
        description = "Whether Cloudflare should proxy traffic (orange cloud). Default is false."
    )
    @Builder.Default
    private Property<Boolean> proxied = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext)
        throws IllegalVariableEvaluationException, HttpClientException {

        Logger logger = runContext.logger();

        String zone = runContext.render(zoneId).as(String.class).orElseThrow();
        String recordType = runContext.render(this.recordType).as(String.class).orElseThrow();
        String recordName = runContext.render(name).as(String.class).orElseThrow();
        String recordContent = runContext.render(content).as(String.class).orElseThrow();
        Integer recordTtl = runContext.render(ttl).as(Integer.class).orElseThrow();
        Boolean recordProxied = runContext.render(proxied).as(Boolean.class).orElseThrow();
        String base = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Creating DNS record '{}' of recordType '{}' in zone '{}'", recordName, recordType, zone);

        var requestBuilder = HttpRequest.builder()
            .method(POST.name())
            .uri(URI.create(base + "/zones/" + zone + "/dns_records"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(
                    java.util.Map.of(
                        "type", recordType,
                        "name", recordName,
                        "content", recordContent,
                        "ttl", recordTtl,
                        "proxied", recordProxied
                    )
                )
                .build()
            );

        HttpResponse<CloudflareEnvelope<RecordResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<RecordResponse>>() {});

        CloudflareEnvelope<RecordResponse> body = response.getBody();

        if (body == null || !body.success()) {
            throw new IllegalStateException("Cloudflare API call failed: " + body);
        }

        logger.info("DNS record created successfully with ID '{}'",
            body.result().id());

        return Output.builder()
            .recordId(body.result().id())
            .name(body.result().name())
            .type(body.result().recordType())
            .content(body.result().content())
            .ttl(body.result().ttl())
            .proxied(body.result().proxied())
            .build();
    }

    public record RecordResponse(
        String id,
        String name,
        String recordType,
        String content,
        Integer ttl,
        Boolean proxied
    ) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Record ID", description = "Unique ID of the created DNS record.")
        private final String recordId;

        @Schema(title = "Name", description = "DNS record name.")
        private final String name;

        @Schema(title = "Type", description = "DNS record recordType.")
        private final String type;

        @Schema(title = "Content", description = "DNS record value.")
        private final String content;

        @Schema(title = "TTL", description = "Time to live in seconds.")
        private final Integer ttl;

        @Schema(title = "Proxied", description = "Whether the record is proxied by Cloudflare.")
        private final Boolean proxied;
    }
}