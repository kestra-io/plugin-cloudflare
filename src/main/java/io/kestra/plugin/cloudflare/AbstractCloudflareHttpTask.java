package io.kestra.plugin.cloudflare;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
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
public abstract class AbstractCloudflareHttpTask extends Task {

    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson(false);

    @Schema(
        title = "HTTP client options",
        description = "Optional advanced HTTP settings like timeouts or proxy."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    protected static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected HttpResponse<String> executeAllowFailed(RunContext runContext, HttpRequest request)
        throws HttpClientException, IllegalVariableEvaluationException {
        return send(runContext, request, true);
    }

    protected HttpResponse<String> executeOrThrow(RunContext runContext, HttpRequest request)
        throws HttpClientException, IllegalVariableEvaluationException {
        return send(runContext, request, false);
    }

    private HttpResponse<String> send(RunContext runContext, HttpRequest request, boolean allowFailed)
        throws HttpClientException, IllegalVariableEvaluationException {
        var config = this.options != null ? this.options : HttpConfiguration.builder().build();
        if (allowFailed)
            config = config.toBuilder().allowFailed(Property.ofValue(true)).build();
        try (var client = new HttpClient(runContext, config)) {
            return client.request(request, String.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call HTTP endpoint", e);
        }
    }
}
