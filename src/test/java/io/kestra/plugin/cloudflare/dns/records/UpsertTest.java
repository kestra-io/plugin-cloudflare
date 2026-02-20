package io.kestra.plugin.cloudflare.dns.records;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28181)
@KestraTest
class UpsertTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldCreateWhenNotExists() throws Exception {

        stubFor(get(urlPathEqualTo("/zones/test-zone/dns_records"))
            .willReturn(okJson("""
                { "success": true, "result": [] }
            """)));

        stubFor(post(urlEqualTo("/zones/test-zone/dns_records"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "result": {
                    "id": "abc123",
                    "name": "app.example.com",
                    "type": "A",
                    "content": "1.2.3.4",
                    "ttl": 1,
                    "proxied": false
                  }
                }
            """)));

        Upsert task = Upsert.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .recordType(Property.ofValue("A"))
            .name(Property.ofValue("app.example.com"))
            .content(Property.ofValue("1.2.3.4"))
            .build();

        Upsert.Output output = task.run(runContextFactory.of());

        assertEquals("created", output.getAction());
        assertEquals("abc123", output.getRecordId());
    }
}