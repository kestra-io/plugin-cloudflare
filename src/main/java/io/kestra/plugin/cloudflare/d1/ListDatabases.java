package io.kestra.plugin.cloudflare.d1;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
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
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Schema(
    title = "List Cloudflare D1 databases",
    description = """
        Lists all D1 databases in the account. Supports optional name filtering and fetches all pages automatically.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "List all D1 databases",
            full = true,
            code = """
                id: list_d1_databases
                namespace: company.team

                tasks:
                  - id: list_databases
                    type: io.kestra.plugin.cloudflare.d1.ListDatabases
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                """
        ),
        @Example(
            title = "List D1 databases filtered by name prefix",
            full = true,
            code = """
                id: list_filtered_d1_databases
                namespace: company.team

                tasks:
                  - id: list_databases
                    type: io.kestra.plugin.cloudflare.d1.ListDatabases
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    nameFilter: "prod-"
                """
        )
    }
)
public class ListDatabases extends AbstractCloudflareTask implements RunnableTask<ListDatabases.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(
        title = "Name filter",
        description = "Optional prefix filter; only databases whose name starts with this value are returned"
    )
    @PluginProperty(group = "processing")
    private Property<String> nameFilter;

    @Schema(
        title = "Page size",
        description = "Number of databases per page (1-100, Cloudflare default is 20)"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Integer> perPage = Property.ofValue(100);

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rNameFilter = runContext.render(nameFilter).as(String.class).orElse(null);
        var rPerPage = runContext.render(perPage).as(Integer.class).orElse(100);

        logger.info("Listing D1 databases in account '{}'", rAccountId);

        var allDatabases = new ArrayList<DatabaseResponse>();
        var baseEndpoint = rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/d1/database";
        int page = 1;

        while (true) {
            var uriBuilder = new StringBuilder(baseEndpoint)
                .append("?per_page=").append(rPerPage)
                .append("&page=").append(page);
            if (rNameFilter != null && !rNameFilter.isBlank()) {
                uriBuilder.append("&name=").append(encodePathSegment(rNameFilter));
            }

            var requestBuilder = HttpRequest.builder()
                .method("GET")
                .uri(URI.create(uriBuilder.toString()));

            var response = this.request(
                runContext, requestBuilder, new TypeReference<CloudflareEnvelope<List<DatabaseResponse>>>() {
                }
            );

            var body = response.getBody();
            if (body == null || !body.success()) {
                throw new IllegalStateException("Failed to list D1 databases: " + body);
            }

            int pageSize = body.result() == null ? 0 : body.result().size();
            if (pageSize > 0) {
                allDatabases.addAll(body.result());
            }

            var info = body.result_info();
            int returned = info != null && info.count() != null ? info.count() : pageSize;
            Integer total = info != null ? info.totalCount() : null;
            if (returned < rPerPage || (total != null && allDatabases.size() >= total)) {
                break;
            }
            page++;
        }

        logger.info("Found {} D1 databases", allDatabases.size());

        return Output.builder()
            .databases(List.copyOf(allDatabases))
            .total(allDatabases.size())
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatabaseResponse(
        String uuid,
        String name,
        String version,
        @JsonProperty("num_tables") Integer numTables) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Databases", description = "List of D1 databases in the account")
        private final List<DatabaseResponse> databases;

        @Schema(title = "Total count", description = "Total number of databases returned")
        private final int total;
    }
}
