package io.kestra.plugin.cloudflare.zones;

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
class GetTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldGetZoneById() throws Exception {

        stubFor(get(urlEqualTo("/zones/zone123"))
            .willReturn(okJson("""
            {
              "success": true,
              "result": {
                "id": "zone123",
                "name": "example.com",
                "status": "active"
              }
            }
        """)));

        Get task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("zone123"))
            .build();

        Get.Output output = task.run(runContextFactory.of());

        assertEquals("zone123", output.getId());
    }

    @Test
    void shouldGetZoneByHostname() throws Exception {

        stubFor(get(urlEqualTo("/zones?name=example.com"))
            .willReturn(okJson("""
            {
              "success": true,
              "result": [
                {
                  "id": "zone123",
                  "name": "example.com",
                  "status": "active"
                }
              ]
            }
        """)));

        Get task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .hostname(Property.ofValue("example.com"))
            .build();

        Get.Output output = task.run(runContextFactory.of());

        assertEquals("zone123", output.getId());
    }
}