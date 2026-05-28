package io.kestra.plugin.cloudflare.workers.scripts;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;

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
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
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
    title = "List Cloudflare Worker scripts",
    description = "Lists all Worker scripts on the account, following Cloudflare's cursor pagination internally."
)
@Plugin(
    examples = {
        @Example(
            title = "List Worker scripts on an account",
            full = true,
            code = """
                id: list_workers
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.cloudflare.workers.scripts.List
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                """
        ),
        @Example(
            title = "Store all scripts in Kestra internal storage for downstream consumption",
            full = true,
            code = """
                id: store_workers
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.cloudflare.workers.scripts.List
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractCloudflareTask implements RunnableTask<FetchOutput> {

    private static final int MAX_PAGES = 100;

    @Schema(title = "Account ID", description = "Cloudflare account identifier whose scripts are listed.")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(
        title = "Fetch type",
        description = "FETCH (all), FETCH_ONE (first only), STORE (write all to file), NONE (no output). Default FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public FetchOutput run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        var collected = new ArrayList<Map<String, Object>>();

        long total = 0;
        URI storedUri = null;

        try {
            var tempFile = rFetchType == FetchType.STORE ? runContext.workingDir().createTempFile(".ion") : null;
            try (
                OutputStream storeStream = tempFile != null
                    ? new BufferedOutputStream(Files.newOutputStream(tempFile))
                    : null
            ) {

                String cursor = null;
                int pages = 0;
                boolean stop = false;

                do {
                    var uri = rBaseUrl + "/accounts/" + encodePathSegment(rAccountId) + "/workers/scripts"
                        + (cursor != null ? "?cursor=" + encodePathSegment(cursor) : "");

                    var requestBuilder = HttpRequest.builder()
                        .method("GET")
                        .uri(URI.create(uri));

                    HttpResponse<CloudflareEnvelope<java.util.List<Map<String, Object>>>> response = this.request(
                        runContext,
                        requestBuilder,
                        new TypeReference<CloudflareEnvelope<java.util.List<Map<String, Object>>>>() {
                        }
                    );

                    var envelope = response.getBody();
                    if (envelope == null || !envelope.success()) {
                        throw new IllegalStateException("Failed to list Worker scripts: " + envelope);
                    }

                    if (envelope.result() != null) {
                        for (var script : envelope.result()) {
                            total++;
                            if (rFetchType == FetchType.STORE) {
                                FileSerde.write(storeStream, script);
                            } else if (rFetchType == FetchType.FETCH_ONE) {
                                if (collected.isEmpty()) {
                                    collected.add(script);
                                }
                                stop = true;
                                break;
                            } else if (rFetchType == FetchType.FETCH) {
                                collected.add(script);
                            }
                        }
                    }

                    cursor = envelope.result_info() != null ? envelope.result_info().cursor() : null;
                    if (cursor != null && cursor.isBlank()) {
                        cursor = null;
                    }
                    pages++;
                } while (!stop && cursor != null && pages < MAX_PAGES);

                if (pages >= MAX_PAGES && cursor != null) {
                    logger.warn("Stopped paginating Worker scripts after {} pages while a cursor was still returned", MAX_PAGES);
                }
            }

            if (tempFile != null) {
                storedUri = runContext.storage().putFile(tempFile.toFile());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Worker scripts to internal storage", e);
        }

        logger.info("Listed {} Worker script(s) for account '{}' (fetchType={})", total, rAccountId, rFetchType);

        var builder = FetchOutput.builder().size(total);
        switch (rFetchType) {
            case FETCH -> builder.rows(new ArrayList<>(collected));
            case FETCH_ONE -> builder.row(collected.isEmpty() ? null : collected.getFirst());
            case STORE -> builder.uri(storedUri);
            case NONE -> {
            }
        }
        return builder.build();
    }
}
