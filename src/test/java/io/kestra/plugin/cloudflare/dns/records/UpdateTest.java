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
class UpdateTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldUpdateDnsRecord() throws Exception {

        stubFor(patch(urlEqualTo("/zones/test-zone/dns_records/abc123"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "id": "abc123"
                  }
                }
            """)));

        Update task = Update.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .recordId(Property.ofValue("abc123"))
            .content(Property.ofValue("5.6.7.8"))
            .build();

        Update.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("abc123", output.getId());

        verify(patchRequestedFor(urlEqualTo("/zones/test-zone/dns_records/abc123"))
            .withRequestBody(matchingJsonPath("$.content", equalTo("5.6.7.8"))));
    }
}