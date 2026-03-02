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
    title = "Create Cloudflare DNS record",
    description = "Creates a DNS record in a zone. Defaults to automatic TTL (1) and disables proxying; Cloudflare must return a record ID or the task fails."
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
                    type: io.kestra.plugin.cloudflare.dns.records.Create
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
        description = "Cloudflare zone identifier"
    )
    @NotNull
    private Property<String> zoneId;

    @Schema(
        title = "Record Type",
        description = "Type of DNS record. Common values are A, AAAA, CNAME, TXT, MX."
    )
    @NotNull
    private Property<DnsRecordType> recordType;

    @Schema(
        title = "Record name",
        description = "Hostname for the record, e.g. app.example.com"
    )
    @NotNull
    private Property<String> name;

    @Schema(
        title = "Record content",
        description = "Record value such as an IP address (A/AAAA) or target domain (CNAME)"
    )
    @NotNull
    private Property<String> content;

    @Schema(
        title = "TTL",
        description = "TTL in seconds; 1 uses Cloudflare automatic (default)"
    )
    @Builder.Default
    private Property<Integer> ttl = Property.ofValue(1);

    @Schema(
        title = "Proxied",
        description = "Enable Cloudflare proxying (orange cloud); defaults to false"
    )
    @Builder.Default
    private Property<Boolean> proxied = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        DnsRecordType rRecordType = runContext.render(this.recordType).as(DnsRecordType.class).orElseThrow();
        String rName = runContext.render(name).as(String.class).orElseThrow();
        String rContent = runContext.render(content).as(String.class).orElseThrow();
        Integer rTtl = runContext.render(ttl).as(Integer.class).orElseThrow();
        Boolean rProxied = runContext.render(proxied).as(Boolean.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Creating DNS record '{}' of type '{}' in zone '{}'", rName, rRecordType, rZoneId);

        var requestBuilder = HttpRequest.builder()
            .method(POST.name())
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/dns_records"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(
                    java.util.Map.of(
                        "type", rRecordType.name(),
                        "name", rName,
                        "content", rContent,
                        "ttl", rTtl,
                        "proxied", rProxied
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
