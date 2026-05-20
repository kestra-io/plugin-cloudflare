package io.kestra.plugin.cloudflare.workers.dynamic;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 28192)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class RunTest {

    private static final String GATEWAY_URL = "http://localhost:28192/run";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldPostScriptAndReturnGatewayResponse() throws Exception {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("""
                    { "hello": "alice" }
                    """))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("""
                export default {
                  async fetch(request) {
                    const body = await request.json();
                    return Response.json({ hello: body.user });
                  }
                };
                """))
            .payload(Property.ofValue(Map.of("user", "alice")))
            .build();

        var output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertEquals(200, output.getStatusCode());
        assertTrue(output.getBody().contains("\"hello\""));
        assertTrue(output.getBody().contains("alice"));
        assertNotNull(output.getHeaders());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("\"script\""))
                .withRequestBody(containing("\"payload\""))
                .withRequestBody(containing("\"user\":\"alice\""))
        );
    }

    @Test
    void shouldSerializePayloadAsJsonEnvelope() {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("{\"ok\":true}"))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default { fetch() { return new Response('ok'); } };"))
            .payload(Property.ofValue(Map.of("action", "greet", "count", 3)))
            .build();

        var output = assertDoesNotThrow(task);

        assertEquals(200, output.getStatusCode());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withRequestBody(equalToJson("""
                    {
                      "script": "export default { fetch() { return new Response('ok'); } };",
                      "payload": { "action": "greet", "count": 3 }
                    }
                    """))
        );
    }

    @Test
    void shouldSerializePayloadAsListOnTheWire() {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("{\"ok\":true}"))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default {};"))
            .payload(Property.ofValue(List.of("a", "b", "c")))
            .build();

        var output = assertDoesNotThrow(task);

        assertEquals(200, output.getStatusCode());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withRequestBody(equalToJson("""
                    {
                      "script": "export default {};",
                      "payload": ["a", "b", "c"]
                    }
                    """))
        );
    }

    @Test
    void shouldSerializePayloadAsPrimitiveOnTheWire() {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("{\"ok\":true}"))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default {};"))
            .payload(Property.ofValue(42))
            .build();

        var output = assertDoesNotThrow(task);

        assertEquals(200, output.getStatusCode());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withRequestBody(equalToJson("""
                    {
                      "script": "export default {};",
                      "payload": 42
                    }
                    """))
        );
    }

    @Test
    void shouldOmitPayloadFieldWhenNotSet() {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("{\"ok\":true}"))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default {};"))
            .build();

        var output = assertDoesNotThrow(task);

        assertEquals(200, output.getStatusCode());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withRequestBody(equalToJson("""
                    {
                      "script": "export default {};"
                    }
                    """))
        );
    }

    @Test
    void shouldForwardCustomHeaders() throws Exception {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(okJson("{\"ok\":true}"))
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default {};"))
            .headers(
                Property.ofValue(
                    Map.of(
                        "X-Gateway-Token", "gateway-token",
                        "CF-Access-Client-Id", "client-123"
                    )
                )
            )
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(200, output.getStatusCode());

        verify(
            postRequestedFor(urlEqualTo("/run"))
                .withHeader("X-Gateway-Token", equalTo("gateway-token"))
                .withHeader("CF-Access-Client-Id", equalTo("client-123"))
        );
    }

    @Test
    void shouldReturnBodyOn4xxWithoutThrowing() throws Exception {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"bad script\"}")
                )
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("not valid"))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(400, output.getStatusCode());
        assertTrue(output.getBody().contains("bad script"));
    }

    @Test
    void shouldReturnBodyOn5xxWithoutThrowing() throws Exception {
        stubFor(
            post(urlEqualTo("/run"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("loader crashed")
                )
        );

        var task = Run.builder()
            .gatewayUrl(Property.ofValue(GATEWAY_URL))
            .script(Property.ofValue("export default {};"))
            .build();

        var output = task.run(runContextFactory.of());

        assertEquals(500, output.getStatusCode());
        assertTrue(output.getBody().contains("loader crashed"));
    }

    private Run.Output assertDoesNotThrow(Run task) {
        try {
            return task.run(runContextFactory.of());
        } catch (Exception e) {
            throw new AssertionError("task.run threw unexpectedly", e);
        }
    }
}
