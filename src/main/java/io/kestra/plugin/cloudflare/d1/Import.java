package io.kestra.plugin.cloudflare.d1;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
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
    title = "Import a SQL file into a Cloudflare D1 database",
    description = """
        Uploads SQL into an existing D1 database using Cloudflare's 3-step import protocol:
        init (get a presigned R2 upload URL), PUT the SQL bytes to that URL, then ingest.
        If ingest does not return "complete" immediately, polls with exponential backoff
        (starting at 500ms, capped at 5s) until the import finishes or the deadline is reached.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Import an exported SQL dump back into a D1 database",
            full = true,
            code = """
                id: import_d1_database
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.cloudflare.d1.Export
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"

                  - id: import
                    type: io.kestra.plugin.cloudflare.d1.Import
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy"
                    from: "{{ outputs.export.uri }}"
                """
        ),
        @Example(
            title = "Import inline SQL into a D1 database",
            full = true,
            code = """
                id: import_inline_sql
                namespace: company.team

                tasks:
                  - id: import
                    type: io.kestra.plugin.cloudflare.d1.Import
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "your_account_id"
                    databaseId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                    sql: |
                      CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, name TEXT);
                      INSERT INTO events (name) VALUES ('deploy');
                """
        )
    }
)
public class Import extends AbstractCloudflareTask implements RunnableTask<Import.Output> {

    private static final long BACKOFF_INITIAL_MS = 500L;
    private static final long BACKOFF_CAP_MS = 5_000L;

    @Schema(title = "Account ID", description = "Cloudflare account identifier")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Database ID", description = "UUID of the D1 database to import into")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> databaseId;

    @Schema(
        title = "Source storage URI",
        description = """
            Kestra internal storage URI of a SQL file to import (e.g. the uri output of an Export task).
            Mutually exclusive with sql.
            """
    )
    @PluginProperty(group = "source")
    private Property<String> from;

    @Schema(
        title = "Inline SQL",
        description = "SQL statements to import. Mutually exclusive with from."
    )
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Schema(
        title = "Maximum duration",
        description = """
            Total time budget for the import to complete (ISO-8601 duration, e.g. PT5M).
            Polls use exponential backoff: 500ms initial delay, doubling each attempt, capped at 5s.
            Raise this for large imports.
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

        var rFrom = from != null ? runContext.render(from).as(String.class).orElse(null) : null;
        var rSql = sql != null ? runContext.render(sql).as(String.class).orElse(null) : null;

        if (rFrom != null && rSql != null) {
            throw new IllegalStateException("Only one of 'from' or 'sql' may be set, not both");
        }
        if (rFrom == null && rSql == null) {
            throw new IllegalStateException("One of 'from' or 'sql' must be set");
        }

        var tempFile = buildTempFile(runContext, rFrom, rSql);
        var etag = computeMd5Hex(tempFile);

        logger.info("Initiating D1 import for database '{}' (etag={})", rDatabaseId, etag);

        var importEndpoint = rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
            + "/d1/database/" + encodePathSegment(rDatabaseId) + "/import";

        var initBody = new HashMap<String, Object>();
        initBody.put("action", "init");
        initBody.put("etag", etag);

        HttpResponse<CloudflareEnvelope<ImportResponse>> initResp = this.request(
            runContext,
            HttpRequest.builder().method("POST").uri(URI.create(importEndpoint))
                .body(HttpRequest.JsonRequestBody.builder().content(initBody).build()),
            new TypeReference<CloudflareEnvelope<ImportResponse>>() {
            }
        );

        var initEnvelope = initResp.getBody();
        if (initEnvelope == null || !initEnvelope.success()) {
            throw new IllegalStateException("D1 import init failed: " + initEnvelope);
        }

        var initResult = initEnvelope.result();
        var uploadUrl = initResult != null ? initResult.uploadUrl() : null;
        var filename = initResult != null ? initResult.filename() : null;

        if (uploadUrl == null) {
            throw new IllegalStateException("D1 import init did not return an upload_url");
        }

        logger.info("Uploading SQL to presigned URL (filename={})", filename);
        uploadToR2(runContext, uploadUrl, tempFile);

        logger.info("Ingesting uploaded file");

        var ingestBody = new HashMap<String, Object>();
        ingestBody.put("action", "ingest");
        ingestBody.put("etag", etag);
        ingestBody.put("filename", filename);

        HttpResponse<CloudflareEnvelope<ImportResponse>> ingestResp = this.request(
            runContext,
            HttpRequest.builder().method("POST").uri(URI.create(importEndpoint))
                .body(HttpRequest.JsonRequestBody.builder().content(ingestBody).build()),
            new TypeReference<CloudflareEnvelope<ImportResponse>>() {
            }
        );

        var ingestEnvelope = ingestResp.getBody();
        if (ingestEnvelope == null || !ingestEnvelope.success()) {
            throw new IllegalStateException("D1 import ingest failed: " + ingestEnvelope);
        }

        var ingestResult = ingestEnvelope.result();

        if (ingestResult != null && "complete".equalsIgnoreCase(ingestResult.status())) {
            return buildOutput(etag, filename, ingestResult);
        }

        if (ingestResult != null && "error".equalsIgnoreCase(ingestResult.status())) {
            throwImportError(ingestResult);
        }

        // Poll until complete
        var currentBookmark = ingestResult != null ? ingestResult.atBookmark() : null;
        var deadline = Instant.now().plus(rMaxDuration);
        long delayMs = BACKOFF_INITIAL_MS;
        int attempt = 0;
        var pollEnvelope = ingestEnvelope;

        while (true) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                    "D1 import did not complete within " + rMaxDuration + " after " + attempt + " poll attempts"
                );
            }

            attempt++;
            logger.debug("Import not ready yet (attempt {}), retrying in {}ms", attempt, delayMs);

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for D1 import", e);
            }

            delayMs = Math.min(delayMs * 2, BACKOFF_CAP_MS);

            var pollBody = new HashMap<String, Object>();
            pollBody.put("action", "poll");
            if (currentBookmark != null) {
                pollBody.put("current_bookmark", currentBookmark);
            }

            HttpResponse<CloudflareEnvelope<ImportResponse>> pollResp = this.request(
                runContext,
                HttpRequest.builder().method("POST").uri(URI.create(importEndpoint))
                    .body(HttpRequest.JsonRequestBody.builder().content(pollBody).build()),
                new TypeReference<CloudflareEnvelope<ImportResponse>>() {
                }
            );

            pollEnvelope = pollResp.getBody();
            if (pollEnvelope == null || !pollEnvelope.success()) {
                logger.warn("Poll attempt {} returned non-success", attempt);
                continue;
            }

            var pollResult = pollEnvelope.result();

            if (pollResult != null && "complete".equalsIgnoreCase(pollResult.status())) {
                return buildOutput(etag, filename, pollResult);
            }

            if (pollResult != null && "error".equalsIgnoreCase(pollResult.status())) {
                throwImportError(pollResult);
            }

            if (pollResult != null && pollResult.atBookmark() != null) {
                currentBookmark = pollResult.atBookmark();
            }
        }
    }

    private Path buildTempFile(RunContext runContext, String rFrom, String rSql) {
        try {
            var tempFile = runContext.workingDir().createTempFile(".sql");
            if (rFrom != null) {
                try (
                    var in = runContext.storage().getFile(URI.create(rFrom));
                    var out = Files.newOutputStream(tempFile)
                ) {
                    in.transferTo(out);
                }
            } else {
                Files.writeString(tempFile, rSql);
            }
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare SQL temp file", e);
        }
    }

    private String computeMd5Hex(Path file) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            try (var in = Files.newInputStream(file)) {
                var buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to compute MD5 of SQL file", e);
        }
    }

    private void uploadToR2(RunContext runContext, String uploadUrl, Path file) {
        // The R2 presigned URL is self-authenticating: no Authorization header is needed.
        var config = this.options != null ? this.options : HttpConfiguration.builder().build();
        try (var client = new HttpClient(runContext, config)) {
            var request = HttpRequest.builder()
                .method("PUT")
                .uri(URI.create(uploadUrl))
                .body(
                    HttpRequest.InputStreamRequestBody.builder()
                        .content(Files.newInputStream(file))
                        .build()
                )
                .build();
            client.request(request, String.class);
        } catch (IOException | IllegalVariableEvaluationException | HttpClientException e) {
            throw new RuntimeException("Failed to upload SQL to R2 presigned URL", e);
        }
    }

    private Output buildOutput(String etag, String filename, ImportResponse result) {
        var completion = result.result();
        return Output.builder()
            .filename(filename)
            .etag(etag)
            .finalBookmark(completion != null ? completion.finalBookmark() : null)
            .numQueries(completion != null ? completion.numQueries() : null)
            .meta(completion != null ? completion.meta() : null)
            .build();
    }

    private void throwImportError(ImportResponse result) {
        var msgs = result.messages() != null ? String.join(", ", result.messages()) : "";
        throw new IllegalStateException(
            "D1 import failed: " + result.error() + (msgs.isBlank() ? "" : " (" + msgs + ")")
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportInitResult(
        @JsonProperty("upload_url") String uploadUrl,
        String filename) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportCompletion(
        @JsonProperty("final_bookmark") String finalBookmark,
        @JsonProperty("num_queries") Integer numQueries,
        Query.QueryMeta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportResponse(
        String status,
        @JsonProperty("at_bookmark") String atBookmark,
        @JsonProperty("upload_url") String uploadUrl,
        String filename,
        String error,
        List<String> messages,
        ImportCompletion result) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Filename", description = "Filename assigned by Cloudflare for this import (derived from database ID and etag)")
        private final String filename;

        @Schema(title = "Etag", description = "MD5 hex digest of the uploaded SQL, as sent to Cloudflare")
        private final String etag;

        @Schema(title = "Final bookmark", description = "D1 replication bookmark after the import completed")
        private final String finalBookmark;

        @Schema(title = "Number of queries", description = "Number of SQL statements executed during the import")
        private final Integer numQueries;

        @Schema(title = "Query metadata", description = "Execution metadata for the import (duration, rows written, etc.)")
        private final Query.QueryMeta meta;
    }
}
