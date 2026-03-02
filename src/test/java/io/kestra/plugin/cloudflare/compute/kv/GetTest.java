package io.kestra.plugin.cloudflare.compute.kv;

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
class GetTest {
    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldGet() throws Exception {
        stubFor(post(urlEqualTo("/accounts/test-account/storage/kv/namespaces/ns_123/bulk/get"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Content-Type", containing("application/json"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "values": {
                      "k1": "v1",
                      "k2": "v2"
                    }
                  }
                }
            """)));

        Get task = Get.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .accountId(Property.ofValue("test-account"))
            .namespaceId(Property.ofValue("ns_123"))
            .keys(Property.ofValue(List.of("k1", "k2")))
            .build();

        Get.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertNotNull(output.getValues());
        assertEquals("v1", output.getValues().get("k1"));
        assertEquals("v2", output.getValues().get("k2"));

        verify(postRequestedFor(urlEqualTo("/accounts/test-account/storage/kv/namespaces/ns_123/bulk/get"))
            .withRequestBody(matchingJsonPath("$.keys[0]", equalTo("k1")))
            .withRequestBody(matchingJsonPath("$.keys[1]", equalTo("k2")))
        );
    }
}