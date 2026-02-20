package io.kestra.plugin.cloudflare.waf;

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
    void shouldListIpAccessRules() throws Exception {

        stubFor(get(urlEqualTo("/zones/test-zone/firewall/access_rules/rules"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "result": [
                    {
                      "id": "rule123",
                      "mode": "block",
                      "configuration": {
                        "target": "ip",
                        "value": "1.2.3.4"
                      }
                    },
                    {
                      "id": "rule456",
                      "mode": "challenge",
                      "configuration": {
                        "target": "ip",
                        "value": "5.6.7.8"
                      }
                    }
                  ]
                }
            """)));

        io.kestra.plugin.cloudflare.waf.List task = io.kestra.plugin.cloudflare.waf.List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .build();

        List.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(2, output.getRules().size());
        assertEquals("rule123", output.getRules().get(0).id());
        assertEquals("rule456", output.getRules().get(1).id());

        verify(getRequestedFor(urlEqualTo("/zones/test-zone/firewall/access_rules/rules")));
    }
}