package io.kestra.plugin.cloudflare.d1;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
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
    title = "Export a Cloudflare D1 database as a SQL dump",
    description = """
        Triggers a D1 database export, polls until the dump is ready, downloads the SQL file,
        and stores it in Kestra internal storage. The storage URI is returned as an output.
        The Cloudflare export endpoint is asynchronous: this task polls with exponential backoff
        (starting at 500ms, capped at 5s per attempt) until the signed download URL is available,
        then streams the file into storage.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Export a D1 database to Kestra storage",
            full = true,
            code = """
                id: export_d1_database
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.cloudflare.d1.Export
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                """
        )
    }
)
public class Export extends AbstractCloudflareTask implements RunnableTask<Export.Output> {

    private static final long BACKOFF_INITIAL_MS = 500L;
    private static final long BACKOFF_CAP_MS = 5_000L;

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to export")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Schema(
        title = "Maximum duration",
        description = """
            Total time budget for the export to complete (ISO-8601 duration, e.g. PT5M).
            Polls use exponential backoff: 500ms initial delay, doubling each attempt, capped at 5s.
            Raise this for large databases.
            """
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(5));

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        Logger logger = runContext.logger();

        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();
        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rDatabaseId = runContext.render(databaseId).as(String.class).orElseThrow();
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofMinutes(5));

        var exportEndpoint = rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
            + "/d1/database/" + encodePathSegment(rDatabaseId) + "/export";

        logger.info("Triggering D1 export for database '{}'", rDatabaseId);

        var postBody = new HashMap<String, Object>();
        postBody.put("output_format", "polling");

        var initRequestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(exportEndpoint))
            .body(HttpRequest.JsonRequestBody.builder().content(postBody).build());

        HttpResponse<CloudflareEnvelope<ExportResponse>> initResponse = this.request(
            runContext, initRequestBuilder, new TypeReference<CloudflareEnvelope<ExportResponse>>() {
            }
        );

        var envelope = initResponse.getBody();
        if (envelope == null || !envelope.success()) {
            throw new IllegalStateException("Failed to initiate D1 export: " + envelope);
        }

        var deadline = Instant.now().plus(rMaxDuration);
        long delayMs = BACKOFF_INITIAL_MS;
        int attempt = 0;

        while (true) {
            var pollResult = envelope.result();

            if (pollResult != null && "complete".equalsIgnoreCase(pollResult.status())) {
                var details = pollResult.result();
                var signedUrl = details != null ? details.signedUrl() : null;
                var filename = details != null ? details.filename() : null;

                if (signedUrl == null) {
                    throw new IllegalStateException("D1 export reported complete but signed_url is missing");
                }

                logger.info("D1 export ready, downloading from signed URL");
                var storageUri = downloadAndStore(runContext, signedUrl, filename != null ? filename : "d1-export.sql");
                logger.info("D1 export stored at '{}'", storageUri);

                return Output.builder()
                    .uri(storageUri)
                    .filename(filename)
                    .build();
            }

            if (pollResult != null && "error".equalsIgnoreCase(pollResult.status())) {
                var msgs = pollResult.messages() != null ? String.join(", ", pollResult.messages()) : "";
                throw new IllegalStateException(
                    "D1 export failed: " + pollResult.error() + (msgs.isBlank() ? "" : " (" + msgs + ")")
                );
            }

            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                    "D1 export did not complete within " + rMaxDuration + " after " + attempt + " poll attempts"
                );
            }

            attempt++;
            logger.debug("Export not ready yet (attempt {}), retrying in {}ms", attempt, delayMs);

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for D1 export", e);
            }

            delayMs = Math.min(delayMs * 2, BACKOFF_CAP_MS);

            var pollBody = new HashMap<String, Object>();
            pollBody.put("output_format", "polling");
            if (pollResult != null && pollResult.atBookmark() != null) {
                pollBody.put("current_bookmark", pollResult.atBookmark());
            }

            var pollRequestBuilder = HttpRequest.builder()
                .method("POST")
                .uri(URI.create(exportEndpoint))
                .body(HttpRequest.JsonRequestBody.builder().content(pollBody).build());

            HttpResponse<CloudflareEnvelope<ExportResponse>> pollResponse = this.request(
                runContext, pollRequestBuilder, new TypeReference<CloudflareEnvelope<ExportResponse>>() {
                }
            );

            var pollEnvelope = pollResponse.getBody();
            if (pollEnvelope == null || !pollEnvelope.success()) {
                var errDetail = pollEnvelope != null && pollEnvelope.errors() != null
                    ? pollEnvelope.errors().stream()
                        .map(e -> "[" + e.code() + "] " + e.message())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("no error detail")
                    : "null envelope";
                logger.warn("Poll attempt {} returned non-success: {}", attempt, errDetail);
                continue;
            }

            envelope = pollEnvelope;
        }
    }

    private URI downloadAndStore(RunContext runContext, String signedUrl, String filename) {
        var config = this.options != null ? this.options : HttpConfiguration.builder().build();
        try (var client = new HttpClient(runContext, config)) {
            var request = HttpRequest.builder()
                .method("GET")
                .uri(URI.create(signedUrl))
                .build();

            var tempFile = runContext.workingDir().createTempFile(".sql");
            client.request(request, response ->
            {
                try (
                    var in = response.getBody();
                    var out = Files.newOutputStream(tempFile)
                ) {
                    in.transferTo(out);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write D1 export to temp file", e);
                }
            });
            return runContext.storage().putFile(tempFile.toFile());
        } catch (IOException | IllegalVariableEvaluationException | HttpClientException e) {
            throw new RuntimeException("Failed to download or store D1 export", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportCompletionDetails(
        String filename,
        @JsonProperty("signed_url") String signedUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportResponse(
        String status,
        @JsonProperty("at_bookmark") String atBookmark,
        String error,
        List<String> messages,
        ExportCompletionDetails result) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Storage URI",
            description = "Kestra internal storage URI of the downloaded SQL dump file"
        )
        private final URI uri;

        @Schema(title = "Filename", description = "Original filename from Cloudflare for the SQL dump")
        private final String filename;
    }
}
