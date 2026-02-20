package io.kestra.plugin.cloudflare.models;

import java.util.List;

public record CloudflareEnvelope<T>(
    boolean success,
    List<ApiMessage> errors,
    List<ApiMessage> messages,
    T result
) {
    public record ApiMessage(
        int code,
        String message,
        String documentation_url
    ) {}
}