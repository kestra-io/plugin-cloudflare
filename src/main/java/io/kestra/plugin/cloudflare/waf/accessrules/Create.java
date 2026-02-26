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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Create IP Access Rule",
    description = "Creates a Cloudflare IP Access rule at zone or account level."
)
@Plugin(
    examples = {
        @Example(
            title = "Block an IP",
            full = true,
            code = """
                id: create_ip_access_rule
                namespace: company.team

                tasks:
                  - id: block_ip
                    type: io.kestra.plugin.cloudflare.waf.accessrules.Create
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "zone123"
                    mode: block
                    target: ip
                    value: "1.2.3.4"
                """
        )
    }
)
public class Create extends AbstractCloudflareTask implements RunnableTask<Create.Output> {

    @Schema(
        title = "Zone ID",
        description = "Zone ID where the rule should apply. Mutually exclusive with accountId."
    )
    private Property<String> zoneId;

    @Schema(
        title = "Account ID",
        description = "Account ID where the rule should apply. Mutually exclusive with zoneId."
    )
    private Property<String> accountId;

    @Schema(
        title = "Mode",
        description = "Action to apply.",
        allowableValues = {"block", "challenge", "whitelist", "js_challenge", "managed_challenge"}
    )
    @NotNull
    private Property<Mode> mode;

    @Schema(
        title = "Target Type",
        description = "Type of entity to match.",
        allowableValues = {"ip", "ip_range", "asn", "country"}
    )
    @NotNull
    private Property<Target> target;

    @Schema(
        title = "Target Value",
        description = "Value of the target (IP, CIDR, ASN, or country code)."
    )
    @NotNull
    private Property<String> value;

    @Schema(
        title = "Notes",
        description = "Optional description for this rule."
    )
    private Property<String> notes;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        Mode rMode = runContext.render(mode).as(Mode.class).orElseThrow();
        Target rTarget = runContext.render(target).as(Target.class).orElseThrow();
        String rValue = runContext.render(value).as(String.class).orElseThrow();
        String rNotes = notes != null ? runContext.render(notes).as(String.class).orElse(null) : null;

        String scopePath;

        if (zoneId != null) {
            String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
            scopePath = "/zones/" + rZoneId;
            logger.info("Creating zone-level access rule for target '{}' with mode '{}'", rValue, rMode);
        } else {
            String rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
            scopePath = "/accounts/" + rAccountId;
            logger.info("Creating account-level access rule for target '{}' with mode '{}'", rValue, rMode);
        }

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("mode", rMode.name().toLowerCase(Locale.ROOT));
        payload.put("configuration", Map.of(
            "target", rTarget.name().toLowerCase(Locale.ROOT),
            "value", rValue
        ));

        if (rNotes != null && !rNotes.isBlank()) {
            payload.put("notes", rNotes);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rBaseUrl + scopePath + "/firewall/access_rules/rules"))
            .body(HttpRequest.JsonRequestBody.builder()
                .content(payload)
                .build());

        HttpResponse<CloudflareEnvelope<AccessRuleResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<AccessRuleResponse>>() {});

        CloudflareEnvelope<AccessRuleResponse> envelope = response.getBody();

        if (envelope == null || !envelope.success() || envelope.result() == null) {
            logger.error("Failed to create IP access rule.");
            throw new IllegalStateException("Cloudflare API call failed.");
        }

        logger.info("Access rule '{}' created successfully", envelope.result().id());

        return Output.builder()
            .ruleId(envelope.result().id())
            .mode(envelope.result().mode())
            .target(envelope.result().configuration().target())
            .value(envelope.result().configuration().value())
            .build();
    }

    public record AccessRuleResponse(
        String id,
        String mode,
        Configuration configuration
    ) {}

    public record Configuration(
        String target,
        String value
    ) {}


    public enum Mode {
        BLOCK,
        CHALLENGE,
        WHITELIST,
        JS_CHALLENGE,
        MANAGED_CHALLENGE
    }

    public enum Target {
        IP,
        IP_RANGE,
        ASN,
        COUNTRY
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Rule ID", description = "Unique identifier of the rule.")
        private final String ruleId;

        @Schema(title = "Mode", description = "Action applied.")
        private final String mode;

        @Schema(title = "Target", description = "Target type.")
        private final String target;

        @Schema(title = "Value", description = "Target value.")
        private final String value;
    }
}
