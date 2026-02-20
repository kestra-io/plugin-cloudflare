package io.kestra.plugin.cloudflare.cache;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28181)
@KestraTest
class PurgeTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldPurgeEverything() throws Exception {

        stubFor(post(urlEqualTo("/zones/test-zone/purge_cache"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "result": {
                    "id": "req123"
                  }
                }
            """)));

        Purge task = Purge.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .purgeAll(Property.ofValue(true))
            .build();

        Purge.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("req123", output.getRequestId());

        verify(postRequestedFor(urlEqualTo("/zones/test-zone/purge_cache"))
            .withRequestBody(matchingJsonPath("$.purge_everything", equalTo("true"))));
    }

    @Test
    void shouldPurgeSpecificFiles() throws Exception {

        stubFor(post(urlEqualTo("/zones/test-zone/purge_cache"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "result": {
                    "id": "req456"
                  }
                }
            """)));

        Purge task = Purge.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .files(Property.ofValue(List.of("https://example.com/app.js")))
            .build();

        Purge.Output output = task.run(runContextFactory.of());

        assertEquals("req456", output.getRequestId());

        verify(postRequestedFor(urlEqualTo("/zones/test-zone/purge_cache"))
            .withRequestBody(matchingJsonPath("$.files[0]", equalTo("https://example.com/app.js"))));
    }
}