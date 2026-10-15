package io.kestra.plugin.cloudflare.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@WireMockTest(httpPort = 28206)
@KestraTest(startRunner = true)
class EvaluateRunnerTest {

    private static final String CLEF_FLASH_PATH = "/accounts/test-account/ai/run/@cf/cloudflare/clef-flash";

    @BeforeEach
    void stubCloudflare() {
        stubFor(post(urlEqualTo(CLEF_FLASH_PATH)).willReturn(okJson("""
            {
              "success": true,
              "errors": [],
              "messages": [],
              "result": {
                "model": "clef-flash",
                "answers": {
                  "urgent": {"type": "noul", "noul": 0.95},
                  "team": {
                    "type": "choice",
                    "choice": "technical",
                    "probabilities": {"billing": 0.05, "technical": 0.9, "other": 0.05},
                    "confidence": 0.8
                  },
                  "severity": {
                    "type": "score",
                    "score": 2.6,
                    "legend": {"0": "No impact", "1": "Minor", "2": "Major", "3": "Critical"},
                    "probabilities": {"0": 0.0, "1": 0.05, "2": 0.3, "3": 0.65},
                    "confidence": 0.7
                  }
                },
                "usage": {"input_tokens": 120, "output_tokens": 3}
              }
            }
            """)));
    }

    @Test
    @ExecuteFlow("flows/ai_evaluate.yaml")
    void shouldRouteOnAnswersFromAFlow(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.findTaskRunsByTaskId("route").getFirst().getOutputs().get("value"), is("technical"));

        verify(
            postRequestedFor(urlEqualTo(CLEF_FLASH_PATH))
                .withRequestBody(equalToJson("""
                    {
                      "model": "clef-flash",
                      "state": {"customer": "acme", "message": "Checkout is down for company.team"},
                      "questions": {
                        "urgent": {
                          "type": "noul",
                          "instructions": "Is this incident urgent?",
                          "criteria": {"true": "Customers are blocked", "false": "Cosmetic issue"}
                        },
                        "team": {
                          "type": "choice",
                          "instructions": "Which team should handle this request?",
                          "criteria": {
                            "billing": "Payments, invoices, and refunds",
                            "technical": "Outages, errors, and configuration",
                            "other": null
                          }
                        },
                        "severity": {
                          "type": "score",
                          "instructions": "How severe is the customer impact?",
                          "criteria": ["No impact", "Minor", "Major", "Critical"]
                        }
                      }
                    }
                    """))
        );
    }

    @Test
    @Disabled("requires a live Cloudflare account id and a CLOUDFLARE_API_TOKEN secret with Workers AI permission")
    @ExecuteFlow("sanity-checks/ai_evaluate.yaml")
    void evaluate(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }
}
