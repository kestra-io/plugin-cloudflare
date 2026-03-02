package io.kestra.plugin.cloudflare.compute.namespaces;

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
    void shouldCreateNamespace() throws Exception {
        stubFor(post(urlEqualTo("/accounts/test-account/storage/kv/namespaces"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Content-Type", containing("application/json"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "id": "ns_123",
                    "title": "my-namespace"
                  }
                }
            """)));

        Create task = Create.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .accountId(Property.ofValue("test-account"))
            .title(Property.ofValue("my-namespace"))
            .build();

        Create.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals("ns_123", output.getNamespaceId());
        assertEquals("my-namespace", output.getTitle());

        verify(postRequestedFor(urlEqualTo("/accounts/test-account/storage/kv/namespaces"))
            .withRequestBody(matchingJsonPath("$.title", equalTo("my-namespace")))
        );
    }
}