package io.kestra.plugin.cloudflare.waf.accessrules;

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
    title = "List IP Access Rules",
    description = "Lists all IP access rules configured for a specific Cloudflare zone."
)
@Plugin(
    examples = {
        @Example(
            title = "List IP access rules for a zone",
            full = true,
            code = """
                id: list_ip_access_rules
                namespace: company.team

                tasks:
                  - id: list_access_rules
                    type: io.kestra.plugin.cloudflare.waf.accessrules.List
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "your_zone_id"
                """
        )
    }
)
public class List extends AbstractCloudflareTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Zone ID",
        description = "Unique identifier of the Cloudflare zone."
    )
    @NotNull
    private Property<String> zoneId;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        logger.info("Listing IP access rules for zone '{}'", rZoneId);

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(rBaseUrl + "/zones/" + rZoneId + "/firewall/access_rules/rules"));

        HttpResponse<CloudflareEnvelope<java.util.List<Create.AccessRuleResponse>>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<java.util.List<Create.AccessRuleResponse>>>() {});

        CloudflareEnvelope<java.util.List<Create.AccessRuleResponse>> envelope = response.getBody();

        if (envelope == null || !envelope.success()) {
            throw new IllegalStateException("Failed to list IP access rules.");
        }

        return Output.builder()
            .rules(envelope.result())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Access Rules",
            description = "List of IP access rules configured for the zone."
        )
        private final java.util.List<Create.AccessRuleResponse> rules;
    }
}
