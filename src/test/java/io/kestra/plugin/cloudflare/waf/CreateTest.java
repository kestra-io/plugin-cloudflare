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
class CreateTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldCreateIpAccessRule() throws Exception {

        stubFor(post(urlEqualTo("/zones/test-zone/firewall/access_rules/rules"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "result": {
                    "id": "rule123",
                    "mode": "block",
                    "configuration": {
                      "target": "ip",
                      "value": "1.2.3.4"
                    }
                  }
                }
            """)));

        Create task = Create.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .mode(Property.ofValue(Create.Mode.BLOCK))
            .notes(Property.ofValue("Blocked by automation"))
            .build();

        Create.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("rule123", output.getRuleId());
        assertEquals("block", output.getMode());

        verify(postRequestedFor(urlEqualTo("/zones/test-zone/firewall/access_rules/rules"))
            .withRequestBody(matchingJsonPath("$.mode", equalTo("block")))
            .withRequestBody(matchingJsonPath("$.configuration.value", equalTo("1.2.3.4"))));
    }
}