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
class DeleteTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDeleteDnsRecord() throws Exception {

        stubFor(delete(urlEqualTo("/zones/test-zone/dns_records/abc123"))
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

        Delete task = Delete.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .recordId(Property.ofValue("abc123"))
            .build();

        Delete.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("abc123", output.getDeletedId());

        verify(deleteRequestedFor(urlEqualTo("/zones/test-zone/dns_records/abc123")));
    }
}