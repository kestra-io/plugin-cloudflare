package io.kestra.plugin.cloudflare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
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
public abstract class AbstractCloudflareTask extends AbstractCloudflareHttpTask {

    @Schema(
        title = "Cloudflare API token",
        description = "Your Cloudflare API token. Create one in the Cloudflare dashboard with DNS read/write permissions."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    protected Property<String> apiToken;

    @Schema(
        title = "Cloudflare API base URL",
        description = "Base URL for Cloudflare API. Usually you don’t need to change this."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue("https://api.cloudflare.com/client/v4");

    protected void addAuthHeader(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder)
        throws IllegalVariableEvaluationException {
        requestBuilder.addHeader(
            "Authorization",
            "Bearer " + runContext.render(this.apiToken).as(String.class).orElseThrow()
        );
    }

    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, TypeReference<RES> typeReference)
        throws HttpClientException, IllegalVariableEvaluationException {
        addAuthHeader(runContext, requestBuilder);
        var request = requestBuilder
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build();

        var response = execute(runContext, request);

        try {
            RES parsed = MAPPER.readValue(response.getBody(), typeReference);
            return HttpResponse.<RES> builder()
                .request(request)
                .body(parsed)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Cloudflare API response", e);
        }
    }

    protected HttpResponse<String> execute(RunContext runContext, HttpRequest request) throws HttpClientException, IllegalVariableEvaluationException {
        try {
            return executeOrThrow(runContext, request);
        } catch (HttpClientResponseException e) {
            throw rewriteCloudflareError(e);
        }
    }

    private static HttpClientResponseException rewriteCloudflareError(HttpClientResponseException original) {
        var response = original.getResponse();
        if (response == null) {
            return original;
        }

        if (response.getRequest() == null || response.getStatus() == null) {
            return original;
        }

        var bodyString = bodyAsString(response.getBody());
        var statusCode = response.getStatus().getCode();

        var clean = formatCloudflareError(bodyString, statusCode);
        if (clean != null) {
            return new HttpClientResponseException(clean, response, original);
        }

        var trimmed = trimBody(bodyString);
        if (trimmed == null) {
            return original;
        }

        var message = "Cloudflare API request failed (HTTP " + statusCode + "): " + trimmed;
        return new HttpClientResponseException(message, response, original);
    }

    private static String formatCloudflareError(String body, int statusCode) {
        if (body == null || body.isBlank() || !body.trim().startsWith("{"))
            return null;
        try {
            var envelope = MAPPER.readValue(body.trim(), new TypeReference<CloudflareEnvelope<Object>>() {
            });
            if (envelope == null || envelope.success() || envelope.errors() == null || envelope.errors().isEmpty())
                return null;
            var joined = envelope.errors().stream()
                .map(e -> "[" + e.code() + "] " + Objects.toString(e.message(), ""))
                .collect(Collectors.joining("; "));
            return "Cloudflare API error: " + joined + " (HTTP " + statusCode + ")";
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String bodyAsString(Object body) {
        if (body == null)
            return null;
        if (body instanceof byte[] bytes)
            return new String(bytes, StandardCharsets.UTF_8);
        return body.toString();
    }

    private static String trimBody(String body) {
        var collapsed = StringUtils.normalizeSpace(body);
        return collapsed == null || collapsed.isEmpty() ? null : StringUtils.abbreviate(collapsed, 500);
    }

    public enum DnsRecordType {
        A,
        AAAA,
        CNAME,
        TXT,
        MX,
        NS,
        SRV,
        PTR,
        CAA,
        SOA
    }
}
