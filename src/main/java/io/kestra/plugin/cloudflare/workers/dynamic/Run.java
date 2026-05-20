package io.kestra.plugin.cloudflare.workers.dynamic;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.cloudflare.AbstractCloudflareHttpTask;

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
    title = "Run inline code on a Cloudflare gateway Worker (Dynamic Workers)",
    description = "POSTs inline JavaScript or TypeScript to a gateway Worker URL that calls `env.LOADER.load(...)` to run the code on the edge."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a JS snippet on the edge via a gateway Worker",
            full = true,
            code = """
                id: run_dynamic_worker
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.cloudflare.workers.dynamic.Run
                    gatewayUrl: "https://loader.acme.workers.dev"
                    headers:
                      Authorization: "Bearer {{ secret('GATEWAY_TOKEN') }}"
                    payload:
                      user: "alice"
                      action: "greet"
                    script: |
                      export default {
                        async fetch(request) {
                          const body = await request.json();
                          return Response.json({ hello: body.user });
                        }
                      };
                """
        )
    }
)
public class Run extends AbstractCloudflareHttpTask implements RunnableTask<Run.Output> {

    @Schema(
        title = "Gateway URL",
        description = "Absolute URL of the gateway Worker. The URL is used as-is; restrict reachable hosts via Kestra worker egress controls."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> gatewayUrl;

    @Schema(
        title = "Inline script",
        description = "JavaScript or TypeScript source to execute on the edge. Sent as the `script` field of the JSON envelope."
    )
    @NotNull
    @PluginProperty(group = "main")
    @ToString.Exclude
    private Property<String> script;

    @Schema(
        title = "Payload",
        description = "Optional JSON payload sent under the `payload` field of the envelope. The gateway Worker forwards it to the loaded module."
    )
    @PluginProperty(group = "main")
    private Property<Object> payload;

    @Schema(
        title = "Extra headers",
        description = "Optional HTTP headers added to the request, typically for auth (e.g. `Authorization`, `CF-Access-Client-Id`)."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<Map<String, String>> headers;

    @Schema(
        title = "Content type",
        description = "Content type sent to the gateway. Defaults to `application/json`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> contentType;

    @Override
    public Output run(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        var logger = runContext.logger();

        var rGatewayUrl = runContext.render(gatewayUrl).as(String.class).orElseThrow();
        var rScript = runContext.render(script).as(String.class).orElseThrow();
        var rContentType = runContext.render(contentType).as(String.class).orElse("application/json");
        var rHeaders = runContext.render(headers).asMap(String.class, String.class);
        var rPayload = parsePayload(runContext.render(payload).as(Object.class).orElse(null));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("script", rScript);
        if (rPayload != null) {
            envelope.put("payload", rPayload);
        }

        String bodyJson;
        try {
            bodyJson = MAPPER.writeValueAsString(envelope);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize gateway request body", e);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(rGatewayUrl))
            .body(
                HttpRequest.StringRequestBody.builder()
                    .content(bodyJson)
                    .contentType(rContentType)
                    .charset(StandardCharsets.UTF_8)
                    .build()
            )
            .addHeader("Accept", "application/json");

        for (var entry : rHeaders.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        var request = requestBuilder.build();

        logger.info("POSTing {} bytes of inline script to gateway '{}'", rScript.getBytes(StandardCharsets.UTF_8).length, rGatewayUrl);

        var response = executeAllowFailed(runContext, request);
        int statusCode = response.getStatus().getCode();
        var responseHeaders = response.getHeaders().map();
        var responseBody = response.getBody();

        logger.info("Gateway Worker responded with HTTP {}", statusCode);

        return Output.builder()
            .statusCode(statusCode)
            .headers(responseHeaders)
            .body(responseBody)
            .build();
    }

    private static Object parsePayload(Object rendered) {
        if (!(rendered instanceof String str))
            return rendered;
        var trimmed = str.trim();
        if (trimmed.isEmpty())
            return null;
        try {
            return MAPPER.readValue(trimmed, Object.class);
        } catch (IOException e) {
            return str;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "HTTP status", description = "Status code returned by the gateway Worker.")
        private final Integer statusCode;

        @Schema(title = "Response headers", description = "Headers returned by the gateway Worker.")
        private final Map<String, List<String>> headers;

        @Schema(
            title = "Response body",
            description = "Raw response body from the gateway Worker, returned as-is."
        )
        private final String body;
    }
}
