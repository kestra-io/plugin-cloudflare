package io.kestra.plugin.cloudflare.workers.scripts;

import java.net.URI;
import java.util.Locale;

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
    title = "Get a Cloudflare Worker script source",
    description = "Fetches the source of a Worker script. Handles both module-format (multipart) and legacy service-worker responses; detected format is reported in `format`."
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch a Worker script's source",
            full = true,
            code = """
                id: get_worker
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.cloudflare.workers.scripts.Get
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    scriptName: "hello-worker"
                """
        )
    }
)
public class Get extends AbstractCloudflareTask implements RunnableTask<Get.Output> {

    @Schema(title = "Account ID", description = "Cloudflare account identifier that owns the Worker.")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Script name", description = "Name of the Worker script to fetch.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> scriptName;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rScriptName = runContext.render(scriptName).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        var requestBuilder = HttpRequest.builder()
            .method("GET")
            .uri(
                URI.create(
                    rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
                        + "/workers/scripts/" + encodePathSegment(rScriptName)
                )
            )
            .addHeader("Accept", "application/javascript, multipart/form-data");
        addAuthHeader(runContext, requestBuilder);
        var request = requestBuilder.build();

        logger.info("Fetching Worker script '{}' from account '{}'", rScriptName, rAccountId);

        var response = execute(runContext, request);
        var body = response.getBody();
        var headers = response.getHeaders();
        var contentType = headers.firstValue("Content-Type").orElse("");
        var entrypoint = headers.firstValue("cf-entrypoint").orElse(null);
        var handlers = headers.firstValue("cf-handlers").orElse(null);
        var etag = headers.firstValue("etag").orElse(null);

        if (body == null) {
            throw new IllegalStateException("Cloudflare returned an empty body for script '" + rScriptName + "'");
        }

        var isMultipart = contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
        var source = isMultipart ? stripMultipartEnvelope(body) : body;

        return Output.builder()
            .script(source)
            .format(isMultipart ? Deploy.ScriptFormat.MODULE : Deploy.ScriptFormat.SERVICE_WORKER)
            .entrypoint(entrypoint)
            .handlers(handlers)
            .etag(etag)
            .build();
    }

    private static String stripMultipartEnvelope(String body) {
        int sourceStart = body.indexOf("\r\n\r\n");
        int sourceEnd = body.lastIndexOf("\r\n--");
        if (sourceStart < 0 || sourceEnd <= sourceStart)
            return body;
        return body.substring(sourceStart + 4, sourceEnd);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Script source", description = "JavaScript source returned by Cloudflare.")
        private final String script;

        @Schema(title = "Format", description = "Detected script format: `MODULE` or `SERVICE_WORKER`.")
        private final Deploy.ScriptFormat format;

        @Schema(title = "Entrypoint", description = "Main module filename (from `cf-entrypoint` header).")
        private final String entrypoint;

        @Schema(title = "Handlers", description = "Comma-separated handlers registered by the Worker (from `cf-handlers` header, e.g. `fetch,scheduled`).")
        private final String handlers;

        @Schema(title = "ETag", description = "ETag of the script version returned by Cloudflare.")
        private final String etag;
    }
}
