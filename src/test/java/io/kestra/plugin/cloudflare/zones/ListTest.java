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
class ListTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldListZones() throws Exception {

        stubFor(get(urlEqualTo("/zones"))
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

        List task = List.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .build();

        List.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(1, output.getZones().size());
        assertEquals("zone123", output.getZones().get(0).id());
    }
}