package io.kestra.plugin.cloudflare.dns.records;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28181)
@KestraTest
class CreateTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldCreateDnsRecord() throws Exception {

        stubFor(post(urlEqualTo("/zones/test-zone/dns_records"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Content-Type", containing("application/json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "id": "abc123",
                    "name": "app.example.com",
                    "recordType": "A",
                    "content": "1.2.3.4",
                    "ttl": 1,
                    "proxied": false
                  }
                }
                """)));


        Create task = Create.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:" + "28181"))
            .zoneId(Property.ofValue("test-zone"))
            .recordType(Property.ofValue("A"))
            .name(Property.ofValue("app.example.com"))
            .content(Property.ofValue("1.2.3.4"))
            .ttl(Property.ofValue(1))
            .proxied(Property.ofValue(false))
            .build();

        Create.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("abc123", output.getRecordId());
        assertEquals("app.example.com", output.getName());
        assertEquals("A", output.getType());

        verify(postRequestedFor(urlEqualTo("/zones/test-zone/dns_records"))
            .withRequestBody(matchingJsonPath("$.type", equalTo("A")))
            .withRequestBody(matchingJsonPath("$.name", equalTo("app.example.com")))
            .withRequestBody(matchingJsonPath("$.content", equalTo("1.2.3.4")))
        );
    }
}