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
import jakarta.validation.constraints.AssertTrue;
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
    title = "Delete IP Access Rule",
    description = "Deletes an existing Cloudflare IP Access rule at zone or account level."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete rule at zone level",
            full = true,
            code = """
                tasks:
                  - id: delete_rule
                    type: io.kestra.plugin.cloudflare.waf.accessrules.Create
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
        description = "Zone ID where the rule exists. Mutually exclusive with accountId."
    )
    private Property<String> zoneId;

    @Schema(
        title = "Account ID",
        description = "Account ID where the rule exists. Mutually exclusive with zoneId."
    )
    private Property<String> accountId;

    @Schema(
        title = "Rule ID",
        description = "Unique identifier of the IP Access rule to delete."
    )
    @NotNull
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

        HttpResponse<CloudflareEnvelope<DeleteResponse>> response = this.request(runContext, requestBuilder, new TypeReference<CloudflareEnvelope<DeleteResponse>>() {});

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

    public record DeleteResponse(String id) {}

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Rule ID", description = "Identifier of the deleted rule.")
        private final String ruleId;

        @Schema(title = "Deleted", description = "Whether the rule was successfully deleted.")
        private final Boolean deleted;
    }
}
