package io.kestra.plugin.cloudflare.workers.scripts;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;

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
    title = "Deploy a Cloudflare Worker script",
    description = "Uploads (or replaces) a Worker script. PUT is idempotent: redeploying with the same `scriptName` updates the existing script."
)
@Plugin(
    examples = {
        @Example(
            title = "Deploy a tiny module-format Worker",
            full = true,
            code = """
                id: deploy_worker
                namespace: company.team

                tasks:
                  - id: deploy
                    type: io.kestra.plugin.cloudflare.workers.scripts.Deploy
                    apiToken: "{{ secret('CLOUDFLARE_API_TOKEN') }}"
                    accountId: "{{ secret('CLOUDFLARE_ACCOUNT_ID') }}"
                    scriptName: "hello-worker"
                    compatibilityDate: "2025-03-14"
                    script: |
                      export default {
                        async fetch(request) {
                          return new Response("hello from kestra");
                        }
                      };
                """
        )
    }
)
public class Deploy extends AbstractCloudflareTask implements RunnableTask<Deploy.Output> {

    private static final String DEFAULT_MODULE_FILENAME = "worker.js";

    @Schema(title = "Account ID", description = "Cloudflare account identifier that owns the Worker.")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> accountId;

    @Schema(title = "Script name", description = "Name of the Worker script. Used as the resource identifier in the URL.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> scriptName;

    @Schema(title = "Script source", description = "JavaScript source uploaded as the main module body.")
    @NotNull
    @PluginProperty(group = "main")
    @ToString.Exclude
    private Property<String> script;

    @Schema(title = "Script format", description = "`MODULE` for module-format scripts (default); `SERVICE_WORKER` for legacy service-worker scripts.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<ScriptFormat> format = Property.ofValue(ScriptFormat.MODULE);

    @Schema(title = "Compatibility date", description = "Cloudflare runtime compatibility date in `YYYY-MM-DD` format (ISO 8601), e.g. `2025-03-14`.")
    @PluginProperty(group = "advanced")
    private Property<LocalDate> compatibilityDate;

    @Schema(title = "Compatibility flags", description = "Optional list of compatibility flags to enable.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> compatibilityFlags;

    @Schema(
        title = "Bindings",
        description = "Optional bindings forwarded as-is into the metadata JSON (e.g. `{type: kv_namespace, name: MY_KV, namespace_id: ...}`)."
    )
    @PluginProperty(group = "advanced")
    private Property<List<Map<String, Object>>> bindings;

    @Schema(title = "Tags", description = "Optional tags attached to the script.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> tags;

    @Schema(title = "Module filename", description = "Filename used for the main module part. Defaults to `worker.js`.")
    @PluginProperty(group = "advanced")
    private Property<String> moduleFilename;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rAccountId = runContext.render(accountId).as(String.class).orElseThrow();
        var rScriptName = runContext.render(scriptName).as(String.class).orElseThrow();
        var rScript = runContext.render(script).as(String.class).orElseThrow();
        var rFormat = runContext.render(format).as(ScriptFormat.class).orElse(ScriptFormat.MODULE);
        var rCompatibilityFlags = runContext.render(compatibilityFlags).asList(String.class);
        var rBindings = coerceBindings(runContext.render(bindings).asList(Map.class));
        var rTags = runContext.render(tags).asList(String.class);
        var rModuleFilename = runContext.render(moduleFilename).as(String.class).orElse(DEFAULT_MODULE_FILENAME);
        var rBaseUrl = runContext.render(this.getBaseUrl()).as(String.class).orElseThrow();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(rFormat == ScriptFormat.MODULE ? "main_module" : "body_part", rModuleFilename);
        runContext.render(compatibilityDate).as(LocalDate.class).ifPresent(d -> metadata.put("compatibility_date", d.toString()));
        if (!rCompatibilityFlags.isEmpty()) {
            metadata.put("compatibility_flags", rCompatibilityFlags);
        }
        if (!rBindings.isEmpty()) {
            metadata.put("bindings", rBindings);
        }
        if (!rTags.isEmpty()) {
            metadata.put("tags", rTags);
        }

        String metadataJson;
        try {
            metadataJson = MAPPER.writeValueAsString(metadata);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Worker metadata", e);
        }

        var moduleContentType = rFormat == ScriptFormat.MODULE
            ? ContentType.create("application/javascript+module", StandardCharsets.UTF_8)
            : ContentType.create("application/javascript", StandardCharsets.UTF_8);

        var scriptBytes = rScript.getBytes(StandardCharsets.UTF_8);

        HttpEntity multipart = MultipartEntityBuilder.create()
            .addTextBody("metadata", metadataJson, ContentType.APPLICATION_JSON)
            .addBinaryBody(
                rModuleFilename,
                scriptBytes,
                moduleContentType,
                rModuleFilename
            )
            .build();

        var requestBuilder = HttpRequest.builder()
            .method("PUT")
            .uri(
                URI.create(
                    rBaseUrl + "/accounts/" + encodePathSegment(rAccountId)
                        + "/workers/scripts/" + encodePathSegment(rScriptName)
                )
            )
            .body(
                HttpRequest.PassthroughRequestBody.builder()
                    .entity(multipart)
                    .contentType(multipart.getContentType())
                    .charset(StandardCharsets.UTF_8)
                    .build()
            )
            .addHeader("Accept", "application/json");
        addAuthHeader(runContext, requestBuilder);
        var request = requestBuilder.build();

        logger.info("Deploying Worker script '{}' to account '{}' ({} bytes, format={})", rScriptName, rAccountId, scriptBytes.length, rFormat);

        var response = execute(runContext, request);

        CloudflareEnvelope<Map<String, Object>> envelope;
        try {
            envelope = MAPPER.readValue(response.getBody(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Cloudflare API response", e);
        }

        if (envelope == null || !envelope.success() || envelope.result() == null) {
            throw new IllegalStateException("Failed to deploy Worker script: " + envelope);
        }

        var result = envelope.result();

        return Output.builder()
            .id(Objects.toString(result.get("id"), null))
            .etag(Objects.toString(result.get("etag"), null))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> coerceBindings(List<? extends Map> rendered) {
        if (rendered == null)
            return List.of();
        return rendered.stream().filter(Objects::nonNull).map(m -> (Map<String, Object>) m).toList();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Script ID", description = "Cloudflare-assigned identifier for the script.")
        private final String id;

        @Schema(title = "ETag", description = "ETag returned by Cloudflare for the deployed script version.")
        private final String etag;
    }

    public enum ScriptFormat {
        MODULE,
        SERVICE_WORKER
    }
}
