package io.kestra.plugin.cloudflare.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.AbstractMetricEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest(httpPort = 28205)
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class EvaluateTest {

    private static final String BASE_URL = "http://localhost:28205";
    private static final String CLEF_PATH = "/accounts/test-account/ai/run/@cf/cloudflare/clef";
    private static final String CLEF_FLASH_PATH = "/accounts/test-account/ai/run/@cf/cloudflare/clef-flash";

    private static final Question URGENT = Question.builder()
        .type(QuestionType.NOUL)
        .instructions("Is this incident urgent?")
        .build();

    private static final String NOUL_RESPONSE = enveloped("""
        {
          "model": "clef",
          "answers": {
            "urgent": {"type": "noul", "noul": 0.93}
          },
          "usage": {"input_tokens": 42, "output_tokens": 1}
        }
        """);

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldAnswerNoulQuestion() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var output = task("Checkout is down for all customers", Map.of("urgent", URGENT)).run(runContextFactory.of());

        assertEquals("clef", output.getModel());
        var answer = output.getAnswers().get("urgent");
        assertEquals(QuestionType.NOUL, answer.getType());
        assertEquals(0.93, answer.getNoul());
        assertNull(answer.getChoice());
        assertNull(answer.getScore());

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""
                    {
                      "model": "clef",
                      "state": "Checkout is down for all customers",
                      "questions": {
                        "urgent": {"type": "noul", "instructions": "Is this incident urgent?"}
                      }
                    }
                    """))
        );
    }

    @Test
    void shouldAnswerChoiceQuestionWithClefFlash() throws Exception {
        stubFor(post(urlEqualTo(CLEF_FLASH_PATH)).willReturn(okJson(enveloped("""
            {
              "model": "clef-flash",
              "answers": {
                "team": {
                  "type": "choice",
                  "choice": "billing",
                  "probabilities": {"billing": 0.9, "technical": 0.07, "sales": 0.03},
                  "confidence": 0.85
                }
              },
              "usage": {"input_tokens": 60, "output_tokens": 1}
            }
            """))));

        var team = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Which team should handle this request?")
            .criteria(Map.of("billing", "Payments, invoices, and refunds", "technical", "Outages, errors, and configuration", "sales", ""))
            .build();

        var output = builder("I was charged twice", Map.of("team", team))
            .model(Property.ofValue(Evaluate.Model.CLEF_FLASH))
            .build()
            .run(runContextFactory.of());

        assertEquals("clef-flash", output.getModel());
        var answer = output.getAnswers().get("team");
        assertEquals(QuestionType.CHOICE, answer.getType());
        assertEquals("billing", answer.getChoice());
        assertEquals(0.85, answer.getConfidence());
        assertEquals(Map.of("billing", 0.9, "technical", 0.07, "sales", 0.03), answer.getProbabilities());

        verify(
            postRequestedFor(urlEqualTo(CLEF_FLASH_PATH))
                .withRequestBody(equalToJson("""
                    {
                      "model": "clef-flash",
                      "state": "I was charged twice",
                      "questions": {
                        "team": {
                          "type": "choice",
                          "instructions": "Which team should handle this request?",
                          "criteria": {
                            "billing": "Payments, invoices, and refunds",
                            "technical": "Outages, errors, and configuration",
                            "sales": null
                          }
                        }
                      }
                    }
                    """))
        );
    }

    @Test
    void shouldSendChoiceOptionNamesWithoutDescriptions() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(enveloped("""
            {
              "model": "clef",
              "answers": {
                "team": {"type": "choice", "choice": "sales", "probabilities": {"billing": 0.1, "sales": 0.9}, "confidence": 0.8}
              },
              "usage": {"input_tokens": 30, "output_tokens": 1}
            }
            """))));

        var team = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Which team should handle this request?")
            .criteria(List.of("billing", "sales"))
            .build();

        var output = task("Can I upgrade my plan?", Map.of("team", team)).run(runContextFactory.of());

        assertEquals("sales", output.getAnswers().get("team").getChoice());
        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(equalToJson("""
                    {
                      "model": "clef",
                      "state": "Can I upgrade my plan?",
                      "questions": {
                        "team": {
                          "type": "choice",
                          "instructions": "Which team should handle this request?",
                          "criteria": {"billing": null, "sales": null}
                        }
                      }
                    }
                    """))
        );
    }

    @Test
    void shouldAnswerScoreQuestion() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(enveloped("""
            {
              "model": "clef",
              "answers": {
                "severity": {
                  "type": "score",
                  "score": 2.4,
                  "legend": {"0": "No impact", "1": "Minor", "2": "Major", "3": "Critical"},
                  "probabilities": {"0": 0.0, "1": 0.1, "2": 0.4, "3": 0.5},
                  "confidence": 0.6
                }
              },
              "usage": {"input_tokens": 70, "output_tokens": 1}
            }
            """))));

        var severity = Question.builder()
            .type(QuestionType.SCORE)
            .instructions("How severe is the customer impact?")
            .criteria(List.of("No impact", "Minor", "Major", "Critical"))
            .build();

        var output = task("Half of our customers cannot log in", Map.of("severity", severity)).run(runContextFactory.of());

        var answer = output.getAnswers().get("severity");
        assertEquals(QuestionType.SCORE, answer.getType());
        assertEquals(2.4, answer.getScore());
        assertEquals(0.6, answer.getConfidence());
        assertEquals("Major", answer.getLegend().get("2"));
        assertEquals(0.5, answer.getProbabilities().get("3"));

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.questions.severity.type", equalTo("score")))
                .withRequestBody(matchingJsonPath("$.questions.severity.criteria[3]", equalTo("Critical")))
        );
    }

    @Test
    void shouldAcceptResponseWithoutEnvelope() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson("""
            {
              "model": "clef",
              "answers": {"urgent": {"type": "noul", "noul": 0.2, "future_field": true}},
              "usage": {"input_tokens": 10, "output_tokens": 1}
            }
            """)));

        var output = task("All good", Map.of("urgent", URGENT)).run(runContextFactory.of());

        assertEquals(0.2, output.getAnswers().get("urgent").getNoul());
    }

    @Test
    void shouldSendNoulCriteria() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var urgent = Question.builder()
            .type(QuestionType.NOUL)
            .instructions("Is this incident urgent?")
            .criteria(Map.of("true", "Customers are blocked", "false", "Cosmetic issue"))
            .build();

        task("Checkout is down", Map.of("urgent", urgent)).run(runContextFactory.of());

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.questions.urgent.criteria.true", equalTo("Customers are blocked")))
                .withRequestBody(matchingJsonPath("$.questions.urgent.criteria.false", equalTo("Cosmetic issue")))
        );
    }

    @Test
    void shouldEmitTokenUsageMetrics() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var runContext = runContextFactory.of();
        task("Checkout is down", Map.of("urgent", URGENT)).run(runContext);

        assertEquals(42.0, metric(runContext, "input.tokens"));
        assertEquals(1.0, metric(runContext, "output.tokens"));
    }

    @Test
    void shouldRenderStateAndQuestionsFromExpressions() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var task = Evaluate.builder()
            .apiToken(Property.ofExpression("{{ token }}"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofExpression("{{ account }}"))
            .state(Property.ofExpression("{{ message }}"))
            .questions(Property.ofExpression("""
                {"urgent": {"type": "NOUL", "instructions": "Is this {{ kind }} urgent?"}}
                """))
            .build();

        task.run(
            runContextFactory.of(
                Map.of(
                    "token", "rendered-token",
                    "account", "test-account",
                    "message", "Checkout is down",
                    "kind", "incident"
                )
            )
        );

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withHeader("Authorization", equalTo("Bearer rendered-token"))
                .withRequestBody(matchingJsonPath("$.state", equalTo("Checkout is down")))
                .withRequestBody(matchingJsonPath("$.questions.urgent.instructions", equalTo("Is this incident urgent?")))
        );
    }

    @Test
    void shouldSendStructuredStateAsJson() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        task(Map.of("customer", "acme", "errors", List.of(500, 502)), Map.of("urgent", URGENT)).run(runContextFactory.of());
        task(List.of(Map.of("role", "user", "text", "It is broken")), Map.of("urgent", URGENT)).run(runContextFactory.of());

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.state.customer", equalTo("acme")))
                .withRequestBody(matchingJsonPath("$.state.errors[1]", equalTo("502")))
        );
        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state[0].text", equalTo("It is broken"))));
    }

    @Test
    void shouldSendExpressionResultAsTextEvenWhenItIsJson() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var task = builder("unused", Map.of("urgent", URGENT))
            .state(Property.ofExpression("{{ body }}"))
            .build();

        task.run(runContextFactory.of(Map.of("body", "{\"comment\": \"It is broken\"}")));

        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("{\"comment\": \"It is broken\"}"))));
    }

    @Test
    void shouldRenderEachValueOfStructuredStateOnItsOwn() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var task = builder(Map.of("customer", "{{ customer }}", "comment", "{{ comment }}", "tags", List.of("{{ tag }}")), Map.of("urgent", URGENT))
            .build();

        task.run(
            runContextFactory.of(
                Map.of(
                    "customer", "acme",
                    "comment", "hi\", \"admin\": \"yes",
                    "tag", "billing"
                )
            )
        );

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.state.customer", equalTo("acme")))
                .withRequestBody(matchingJsonPath("$.state.comment", equalTo("hi\", \"admin\": \"yes")))
                .withRequestBody(matchingJsonPath("$.state.tags[0]", equalTo("billing")))
                .withRequestBody(notContaining("\"admin\":\"yes\""))
        );
    }

    @Test
    void shouldNotRenderKeysOfStructuredState() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var state = new LinkedHashMap<String, Object>();
        state.put("{{ field }}", "attacker");
        state.put("role", "customer");

        task(state, Map.of("urgent", URGENT)).run(runContextFactory.of(Map.of("field", "role")));

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.state.role", equalTo("customer")))
                .withRequestBody(matchingJsonPath("$.state['{{ field }}']", equalTo("attacker")))
        );
    }

    @Test
    void shouldRejectEmptyStructuredState() {
        var ex = assertThrows(IllegalArgumentException.class, () -> task(Map.of(), Map.of("urgent", URGENT)).run(runContextFactory.of()));

        assertTrue(ex.getMessage().contains("'state'"), ex.getMessage());
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldKeepTextStateThatOnlyLooksLikeJson() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        task("[urgent] site is down", Map.of("urgent", URGENT)).run(runContextFactory.of());
        task("42", Map.of("urgent", URGENT)).run(runContextFactory.of());
        task("[\"P1\"] Checkout is down", Map.of("urgent", URGENT)).run(runContextFactory.of());
        builder("unused", Map.of("urgent", URGENT))
            .state(Property.ofExpression("{} {{ incident }}"))
            .build()
            .run(runContextFactory.of(Map.of("incident", "Checkout is down")));

        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("[urgent] site is down"))));
        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("42"))));
        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("[\"P1\"] Checkout is down"))));
        verify(postRequestedFor(urlEqualTo(CLEF_PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("{} Checkout is down"))));
    }

    @Test
    void shouldSendStructuredInstructions() throws Exception {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var urgent = Question.builder()
            .type(QuestionType.NOUL)
            .instructions(Map.of("question", "Does this breach the SLA?", "sla", Map.of("maxDowntimeMinutes", 5)))
            .build();

        task("Down for 12 minutes", Map.of("urgent", urgent)).run(runContextFactory.of());

        verify(
            postRequestedFor(urlEqualTo(CLEF_PATH))
                .withRequestBody(matchingJsonPath("$.questions.urgent.instructions.sla.maxDowntimeMinutes", equalTo("5")))
        );
    }

    @Test
    void shouldRejectBlankStateWithoutCallingCloudflare() {
        var ex = assertThrows(IllegalArgumentException.class, () -> task("   ", Map.of("urgent", URGENT)).run(runContextFactory.of()));

        assertTrue(ex.getMessage().contains("'state'"), ex.getMessage());
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldRejectInvalidQuestionsWithoutCallingCloudflare() {
        var tooMany = new LinkedHashMap<String, Question>();
        IntStream.range(0, 65).forEach(i -> tooMany.put("q" + i, URGENT));

        assertInvalid(Map.of(), "at least one question");
        assertInvalid(tooMany, "at most 64");
        assertInvalid(Map.of("not valid!", URGENT), "Invalid question id 'not valid!'");
        assertInvalid(Map.of("q", Question.builder().instructions("Urgent?").build()), "'type' is missing");
        assertInvalid(Map.of("q", Question.builder().type(QuestionType.NOUL).instructions(" ").build()), "'instructions' is missing or empty");
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.NOUL).instructions("Urgent?").criteria(Map.of("yes", "Blocked")).build()),
            "only accepts the keys 'true' and 'false'"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.CHOICE).instructions("Team?").build()),
            "must be a map of option name to description, or a list of option names"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.CHOICE).instructions("Team?").criteria(List.of("billing", "billing")).build()),
            "must be unique"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.CHOICE).instructions("Team?").criteria(List.of("billing", " ")).build()),
            "must be non-empty strings"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.CHOICE).instructions("Team?").criteria(Map.of("billing", "Payments")).build()),
            "needs 2 to 255 options"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.SCORE).instructions("Severity?").criteria(Map.of("low", "Minor")).build()),
            "must be an ordered list"
        );
        assertInvalid(
            Map.of("q", Question.builder().type(QuestionType.SCORE).instructions("Severity?").criteria(List.of("Only one")).build()),
            "needs 2 to 10 levels"
        );
        assertInvalid(
            Map.of(
                "q", Question.builder().type(QuestionType.SCORE).instructions("Severity?")
                    .criteria(IntStream.range(0, 11).mapToObj(i -> "Level " + i).toList()).build()
            ),
            "needs 2 to 10 levels"
        );

        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void shouldExplainUnauthorized() {
        stubError(401, 10000, "Authentication error");

        var message = assertFails(Exception.class);

        assertTrue(message.contains("[10000] Authentication error"), message);
        assertTrue(message.contains("HTTP 401"), message);
        assertTrue(message.contains("'apiToken'"), message);
    }

    @Test
    void shouldExplainForbidden() {
        stubError(403, 10000, "Authentication error");

        var message = assertFails(Exception.class);

        assertTrue(message.contains("HTTP 403"), message);
        assertTrue(message.contains("Workers AI permission"), message);
    }

    @Test
    void shouldExplainInvalidQuestion() {
        stubError(422, 5006, "questions.urgent: instructions must be a non-empty string");

        var message = assertFails(Exception.class);

        assertTrue(message.contains("[5006] questions.urgent: instructions must be a non-empty string"), message);
        assertTrue(message.contains("HTTP 422"), message);
        assertTrue(message.contains("'criteria'"), message);
    }

    @Test
    void shouldKeepCloudflareMessageOnOtherErrors() {
        stubError(500, 3040, "Capacity temporarily exceeded");

        var message = assertFails(Exception.class);

        assertTrue(message.contains("[3040] Capacity temporarily exceeded"), message);
        assertTrue(message.contains("HTTP 500"), message);
    }

    @Test
    void shouldFailOnEmptyResponseBody() {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(aResponse().withStatus(200)));

        var message = assertFails(IllegalStateException.class);

        assertTrue(message.contains("empty response body"), message);
    }

    @Test
    void shouldFailWhenEnvelopeReportsFailure() {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson("""
            {"success": false, "errors": [{"code": 3040, "message": "Capacity temporarily exceeded"}], "messages": [], "result": null}
            """)));

        var message = assertFails(IllegalStateException.class);

        assertTrue(message.contains("Capacity temporarily exceeded"), message);
    }

    @Test
    void shouldFailWhenAnAnswerIsMissing() {
        stubFor(post(urlEqualTo(CLEF_PATH)).willReturn(okJson(NOUL_RESPONSE)));

        var ex = assertThrows(
            IllegalStateException.class,
            () -> task("Checkout is down", Map.of("urgent", URGENT, "severe", URGENT)).run(runContextFactory.of())
        );

        assertTrue(ex.getMessage().contains("no answer for question(s): severe"), ex.getMessage());
    }

    @Test
    void shouldNotLeakApiTokenInToString() {
        var task = builder("state", Map.of("urgent", URGENT))
            .apiToken(Property.ofValue("super-secret-token"))
            .build();

        assertFalse(task.toString().contains("super-secret-token"));
    }

    private static String enveloped(String result) {
        return """
            {"success": true, "errors": [], "messages": [], "result": %s}
            """.formatted(result);
    }

    private static void stubError(int status, int code, String message) {
        stubFor(
            post(urlEqualTo(CLEF_PATH)).willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {"success": false, "errors": [{"code": %d, "message": "%s"}], "messages": [], "result": null}
                        """.formatted(code, message))
            )
        );
    }

    private <T extends Exception> String assertFails(Class<T> expected) {
        var ex = assertThrows(expected, () -> task("Checkout is down", Map.of("urgent", URGENT)).run(runContextFactory.of()));
        return ex.getMessage() == null ? "" : ex.getMessage();
    }

    private void assertInvalid(Map<String, Question> questions, String expectedMessage) {
        var ex = assertThrows(IllegalArgumentException.class, () -> task("state", questions).run(runContextFactory.of()));
        assertTrue(ex.getMessage().contains(expectedMessage), ex.getMessage());
    }

    private static double metric(RunContext runContext, String name) {
        return runContext.metrics().stream()
            .filter(entry -> entry.getName().equals(name))
            .map(AbstractMetricEntry::getValue)
            .map(value -> ((Number) value).doubleValue())
            .findFirst()
            .orElseThrow(() -> new AssertionError("metric '" + name + "' was not emitted"));
    }

    private static Evaluate task(Object state, Map<String, Question> questions) {
        return builder(state, questions).build();
    }

    private static Evaluate.EvaluateBuilder<?, ?> builder(Object state, Map<String, Question> questions) {
        return Evaluate.builder()
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(BASE_URL))
            .accountId(Property.ofValue("test-account"))
            .state(Property.ofValue(state))
            .questions(Property.ofValue(questions));
    }
}
