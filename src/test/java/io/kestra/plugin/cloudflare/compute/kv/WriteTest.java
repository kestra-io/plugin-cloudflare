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
class WriteTest {
    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldWrite() throws Exception {
        stubFor(put(urlEqualTo("/accounts/test-account/storage/kv/namespaces/ns_123/bulk"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Content-Type", containing("application/json"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "successful_key_count": 2,
                    "unsuccessful_keys": []
                  }
                }
            """)));

        Write task = Write.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .accountId(Property.ofValue("test-account"))
            .namespaceId(Property.ofValue("ns_123"))
            .pairs(Property.ofValue(List.of(
                new Write.KVPair("k1", "v1"),
                new Write.KVPair("k2", "v2")
            )))
            .build();

        Write.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(2, output.getSuccessfulKeyCount());
        assertNotNull(output.getUnsuccessfulKeys());
        assertTrue(output.getUnsuccessfulKeys().isEmpty());

        verify(putRequestedFor(urlEqualTo("/accounts/test-account/storage/kv/namespaces/ns_123/bulk"))
            .withRequestBody(matchingJsonPath("$[0].key", equalTo("k1")))
            .withRequestBody(matchingJsonPath("$[0].value", equalTo("v1")))
            .withRequestBody(matchingJsonPath("$[1].key", equalTo("k2")))
            .withRequestBody(matchingJsonPath("$[1].value", equalTo("v2")))
        );
    }
}