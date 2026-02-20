package io.kestra.plugin.cloudflare.dns.records;

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
class BatchTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldCreateDnsRecord() throws Exception {
        stubFor(post(urlEqualTo("/zones/test-zone/dns_records/batch"))
            .willReturn(okJson("""
                {
                  "success": true,
                  "errors": [],
                  "messages": [],
                  "result": {
                    "posts": [],
                    "patches": [],
                    "deletes": []
                  }
                }
            """)));


        Batch task = Batch.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue("http://localhost:28181"))
            .zoneId(Property.ofValue("test-zone"))
            .posts(Property.ofValue(List.of(
                new Batch.RecordInput(
                    "A",
                    "app1.example.com",
                    "1.2.3.4",
                    1,
                    false
                ),
                new Batch.RecordInput(
                    "A",
                    "app2.example.com",
                    "5.6.7.8",
                    1,
                    false
                )
            )))
            .build();

        Batch.Output output = task.run(runContextFactory.of());

        assertNotNull(output);
        assertTrue(output.getSuccess());

        verify(postRequestedFor(urlEqualTo("/zones/test-zone/dns_records/batch"))
            .withRequestBody(matchingJsonPath("$.posts[0].type", equalTo("A")))
            .withRequestBody(matchingJsonPath("$.posts[0].name", equalTo("app1.example.com")))
            .withRequestBody(matchingJsonPath("$.posts[1].name", equalTo("app2.example.com")))
        );
    }
}