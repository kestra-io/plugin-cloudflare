package io.kestra.plugin.cloudflare.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudflareEnvelope<T>(
    boolean success,
    List<ApiMessage> errors,
    List<ApiMessage> messages,
    T result,
    ResultInfo result_info) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiMessage(
        int code,
        String message,
        String documentation_url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultInfo(String cursor) {
    }
}
