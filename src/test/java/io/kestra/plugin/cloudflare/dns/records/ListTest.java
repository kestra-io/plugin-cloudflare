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
class ListTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldListDnsRecords() throws Exception {

        stubFor(get(urlEqualTo("/zones/test-zone/dns_records"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": [
                    {
                      "id": "abc123",
                      "name": "app.example.com",
                      "type": "A",
                      "content": "1.2.3.4",
                      "ttl": 1,
                      "proxied": false
                    }
                  ]
                }
            """)));

        List task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .build();

        List.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(1, output.getRecords().size());
        assertEquals("abc123", output.getRecords().getFirst().id());
        assertEquals("A", output.getRecords().get(0).type());

        verify(getRequestedFor(urlEqualTo("/zones/test-zone/dns_records")));
    }
}