package io.kestra.plugin.cloudflare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.IOException;

@SuperBuilder
@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public abstract class AbstractCloudflareTask extends Task {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Schema(
        title = "Cloudflare API token",
        description = "Your Cloudflare API token. Create one in the Cloudflare dashboard with DNS read/write permissions."
    )
    @NotNull
    protected Property<String> apiToken;

    @Schema(
        title = "Cloudflare API base URL",
        description = "Base URL for Cloudflare API. Usually you don’t need to change this."
    )
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://api.cloudflare.com/client/v4");

    @Schema(
        title = "HTTP client options",
        description = "Optional advanced HTTP settings like timeouts or proxy."
    )
    protected HttpConfiguration options;

    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, TypeReference<RES> typeReference) throws HttpClientException, IllegalVariableEvaluationException {
        String token = runContext.render(this.apiToken).as(String.class).orElseThrow();
        String base = runContext.render(this.baseUrl).as(String.class).orElseThrow();

        var request = requestBuilder
            .addHeader("Authorization", "Bearer " + token)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build();

        HttpConfiguration httpConfiguration =
            this.options != null ? this.options : HttpConfiguration.builder().build();

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            HttpResponse<String> response = client.request(request, String.class);

            RES parsed = MAPPER.readValue(response.getBody(), typeReference);

            return HttpResponse.<RES>builder()
                .request(request)
                .body(parsed)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();

        } catch (IOException e) {
            throw new RuntimeException("Failed to call Cloudflare API", e);
        }
    }
}