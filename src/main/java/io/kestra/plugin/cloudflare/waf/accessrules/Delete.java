package io.kestra.plugin.cloudflare.waf.accessrules;

import java.net.URI;

import org.slf4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.cloudflare.AbstractCloudflareTask;
import io.kestra.plugin.cloudflare.models.CloudflareEnvelope;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "Delete Cloudflare IP access rule",
    description = "Deletes an IP access rule scoped to either a zone or an account (exactly one required); fails if Cloudflare does not confirm deletion."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete rule at zone level",
            full = true,
            code = """
                id: delete_ip_access_rule
                namespace: company.team

                tasks:
                  - id: delete_rule
                    type: io.kestra.plugin.cloudflare.waf.accessrules.Delete
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    zoneId: "zone123"
                    ruleId: "92f17202ed8bd63d69a66b86a49a8f6b"
                """
        )
    }
)
public class Delete extends AbstractCloudflareTask implements RunnableTask<Delete.Output> {

    @Schema(
        title = "Zone ID",
        description = "Zone ID where the rule exists; mutually exclusive with accountId"
    )
    @PluginProperty(group = "advanced")
    private Property<String> zoneId;

    @Schema(
        title = "Account ID",
        description = "Account ID where the rule exists; mutually exclusive with zoneId"
    )
    @PluginProperty(group = "advanced")
    private Property<String> accountId;

    @Schema(
        title = "Rule ID",
        description = "Identifier of the IP access rule to delete"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> ruleId;

    @AssertTrue(message = "Either zoneId or accountId must be provided (but not both).")
    public boolean isValidScope() {
        return (zoneId != null && accountId == null) ||
            (zoneId == null && accountId != null);
    }

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        String rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        String rRuleId = runContext.render(ruleId).as(String.class).orElseThrow();

        String scopePath;

        if (zoneId != null) {
            String rZoneId = runContext.render(zoneId).as(String.class).orElseThrow();
            scopePath = "/zones/" + rZoneId;
            logger.info("Deleting zone-level IP access rule '{}'", rRuleId);
        } else {
            String rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
            scopePath = "/accounts/" + rAccountId;
            logger.info("Deleting account-level IP access rule '{}'", rRuleId);
        }

        var requestBuilder = HttpRequest.builder()
            .method("DELETE")
            .uri(URI.create(rBaseUrl + scopePath + "/firewall/access_rules/rules/" + rRuleId));

        HttpResponse<CloudflareEnvelope<DeleteResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<DeleteResponse>>() {
        });

        CloudflareEnvelope<DeleteResponse> envelope = response.getBody();

        if (envelope == null || !envelope.success()) {
            logger.error("Failed to delete rule '{}'", rRuleId);
            throw new IllegalStateException("Failed to delete access rule.");
        }

        logger.info("Access rule '{}' deleted successfully", rRuleId);

        return Output.builder()
            .ruleId(rRuleId)
            .deleted(true)
            .build();
    }

    public record DeleteResponse(String id) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Rule ID", description = "Identifier of the deleted rule")
        private final String ruleId;

        @Schema(title = "Deleted", description = "Whether Cloudflare confirmed deletion")
        private final Boolean deleted;
    }
}
